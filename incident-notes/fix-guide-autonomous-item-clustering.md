# Guide: fixing "same items over and over" in autonomous submissions

**Symptom:** autonomous BUY (and sometimes SELL) orders keep landing on the same narrow family of
items — in one real occurrence: arrowtips, bolt tips, longbows/shortbows, herb potions (unf), and
grimy herbs, repeated tick after tick — instead of spreading across the full watchlist. It looks
like the model has a strong, fixed preference for that item family.

**It is very unlikely to actually be a model preference.** Before assuming a training/data bias,
check the mechanism below — it has been the real cause at least once, confirmed live, on this
codebase.

## Root cause

Two things compound:

1. **`DecisionEngine.decide()`** builds its request from `WatchlistManager.getAll()`, which returns
   a `LinkedHashSet` — items come out in **insertion order**. If the watchlist was seeded via the
   "Seed watchlist from trained items" button, that insertion order is the order items appear in
   the `modelTrainedItems` Firestore document, which is **sorted by item id**. So "the order the
   model scores items in" is effectively "ascending item id," not anything meaningful.

2. **`PPOFlipperStarScript.autonomouslySubmit()`** has a hard cap on how many orders it will queue
   per tick (`maxActiveOffers * AUTONOMOUS_QUEUE_DEPTH_MULTIPLIER`, typically ~24) to bound the
   `OrderQueue`'s backlog. The loop that walks `suggestions` and submits them **stops (`break`) the
   moment the backlog hits that cap.**

Put together: once a DECIDE tick is large enough to produce more suggestions than the cap allows in
one go (500-700+ suggestions is normal for an ~800-900 item watchlist), only the suggestions **at
the front of the list** ever get a chance to submit before the loop bails out. If `suggestions`
hasn't been re-sorted by anything meaningful, "front of the list" means "lowest item id" — which is
how a handful of unrelated items (that just happen to share a low-id range, e.g. ammo/ranged supplies
and herblore items in OSRS's item table) can dominate every single tick regardless of whether the
model actually rates them highest that tick.

## How to confirm this is what's happening (do this before changing anything)

1. Check `WatchlistManager.getAll()`'s return type/backing collection — confirm it's a
   `LinkedHashSet` or similarly order-preserving structure, not something re-sorted on each read.
2. Add a temporary log line at the top of `autonomouslySubmit()` printing the first ~10 item ids in
   `suggestions` as received, unsorted. If the same ids show up in the same order every tick, and
   they're numerically close together, that's the insertion-order tell.
3. Check the actual submitted-order log lines (`AUTONOMOUS submit - ...`) over several ticks. Pull
   out the item ids and look them up. If they cluster tightly in one id range regardless of session
   length, that supports "position in list," not "model preference" — a genuine trained preference
   would still show *some* variety as market conditions (price, spread, volatility) change tick to
   tick, whereas a pure ordering artifact will not.
4. If you have `modelConfidenceThreshold` set low enough to see suggestions with a wide confidence
   range, check whether the submitted orders' confidence values are actually the highest in that
   tick's suggestion list, or just whatever happened to be near the front. If they're not
   consistently the highest-confidence ones, that confirms the cap is cutting off better
   suggestions arbitrarily.

## The fix

Sort `suggestions` by something meaningful **before** the queue-depth cap is ever checked, so the
cap — once it does have to cut the list short — drops the *worst* remaining suggestions, not just
whichever ones happened to load last.

In `PPOFlipperStarScript.autonomouslySubmit()`:

```java
List<PPOFlipperDecision> ordered = new ArrayList<>(suggestions);
ordered.sort(Comparator
    .comparingInt((PPOFlipperDecision d) -> d.getGeAction() == GrandExchangeAction.SELL ? 0 : 1)
    .thenComparing(Comparator.comparingDouble(PPOFlipperDecision::getConfidence).reversed()));

for (PPOFlipperDecision decision : ordered) {
    long currentBacklog = queue.countByStatus(PPOFlipperOrder.Status.QUEUED)
        + queue.countByStatus(PPOFlipperOrder.Status.SUBMITTED);
    if (currentBacklog >= maxQueueDepth) {
        break; // now drops the LEAST confident remaining suggestions, not an arbitrary tail
    }
    // ... existing per-decision handling
}
```

Two sort keys, in order:
1. **SELL before BUY** — a SELL represents capital/inventory already committed that could be freed
   up; a missed BUY is just a missed new opportunity. Keep this first key regardless of the second.
2. **Confidence, descending** — this is the actual fix for the clustering symptom. Within each
   action-type group, the model's own confidence score decides who wins the limited slots, not
   list position.

This much (comparator change alone) is genuinely safe to apply in isolation — it doesn't touch
network calls, timeouts, or anything else; it's a plain in-memory sort of a list already fully
built. No other prerequisite fix is required for this specific change to work correctly.

## How to verify the fix actually worked

Same as the confirmation steps above, run again after the change:
- Submitted-order item ids should now spread across a much wider range within a session, not
  cluster in one narrow id band.
- Submitted orders' confidence values should be visibly the top of that tick's range, not just
  "whatever came first."

Concretely, on the one real occurrence this was fixed for, submitted item ids went from clustering
entirely under id ~250 to spanning ids like 121, 351, 1658, 1702, 2007, 5310, 6328, 6705, 10937,
11069, 11943, 19672, 32333, 32349 in the same session, with confidence values correctly ranked
highest-first (0.86 down to 0.65) instead of an arbitrary mix.

## A related, separate bug worth checking at the same time

Sorting alone does not fully solve backlog starvation for SELL orders specifically. The backlog
cap check (`currentBacklog >= maxQueueDepth`) is evaluated **fresh on every loop iteration against
the live queue**, not against a per-tick budget. If prior ticks' still-pending BUY orders have
already pushed the real queue backlog above the cap by the time a new tick runs, **the very first
item checked that tick already fails the cap** — including a SELL that the sort above just placed
at the very front. A real, high-confidence SELL can end up silently withheld for many minutes this
way even after the sort fix, because the loop never gets past the cap check to reach it.

If you see a good SELL suggestion sitting unconfirmed in the panel for an unusually long time even
after applying the sort fix above, this is very likely why. The fix is to exempt SELL from the
backlog cap entirely (only BUY actually grows the backlog, so only BUY needs to be capped by it):

```java
for (PPOFlipperDecision decision : ordered) {
    if (decision.getGeAction() != GrandExchangeAction.SELL) {
        long currentBacklog = queue.countByStatus(PPOFlipperOrder.Status.QUEUED)
            + queue.countByStatus(PPOFlipperOrder.Status.SUBMITTED);
        if (currentBacklog >= maxQueueDepth) {
            break;
        }
    }
    // ... existing per-decision handling
}
```

**Update:** verified live in `PPOFlipperStarScript.autonomouslySubmit()` — both this SELL-exemption
and the confidence-descending sort above are already applied in the current codebase (see lines
~906-974). No further action needed; this section is kept for context on why the code looks the way
it does.
