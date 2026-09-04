# DECIDE tick bug hunt — 2026-09-03

## Context

Earlier on 2026-09-03 the step-12,001,280 PPO checkpoint was deployed as the live model
(`b9fa5d1`), trained on ~885 items. The watchlist was then grown from ~300 to ~885 items via the
"Seed watchlist from trained items" panel button. This exposed a long chain of latent bugs in
`DecisionEngine`'s per-tick loop that had never mattered at ~300 items but became severe at ~885.

24 commits were made same-day fixing these, one at a time, reactively, as each fix exposed the
next bottleneck. The user later asked to roll the branch back to `b9fa5d1` (`git reset --hard`)
because the growing pile of fixes felt like it kept making things worse rather than better. After
the rollback, the very first original bug (per-item mapping fetch) immediately resurfaced and was
confirmed live via jstack, so it was re-applied as a single standalone commit (`c7a7073`).

**Current state of the branch as of this writeup:** `c7a7073` (bulk item mapping fetch, fix #1
below) plus `a6e3017` (pure-observability logging: the dedicated diagnostics log file and the
panel's "Last DECIDE tick" status row, re-added afterward with zero behavior change) are live.
Every other fix described below is reverted. This document exists so nothing found today is lost
even though most of the code isn't currently applying it.

## Root causes found, in the order discovered

### 1. Per-item Rs2GrandExchange.getItemMappingData(itemId) — no bulk endpoint exists
**Commit:** `d2efffc` (re-applied standalone as `c7a7073`)
**Found by:** curl testing the wiki API directly, outside the plugin; bytecode inspection of
`Rs2GrandExchange.fetchItemMappingData`.
**What was wrong:** the wiki has no per-item mapping endpoint. Every call to
`getItemMappingData(itemId)` — regardless of which item — downloads the *entire* ~4,700-item,
~860KB mapping file and discards everything except the one entry needed. Its own internal cache is
keyed per-item, so it never benefits from the bulk download it just did. At ~885 watchlisted items,
this meant downloading the same 860KB file ~885 times per tick.
**Result:** **This is the one fix that's currently live and confirmed working.** Fetches the bulk
endpoint once per tick (30-minute cache TTL), reads a plain in-memory map thereafter. Directly
fixed the "stuck 14 minutes inside fetchItemMappingData" hang confirmed via jstack both before this
fix was first written and again immediately after the full rollback (before this fix was
re-applied standalone).

### 2. getTotalGold() scans the whole bank, called once per item
**Commit:** `9033c84` (reverted)
**Found by:** jstack, mid-hang, after fix #1 was already in place.
**What was wrong:** `goldManager.getTotalGold()` → `getBankGold()` → `BankManager.snapshotByItemId()`
does a full `Rs2Bank.bankItems()` scan and re-resolves every noted item's canonical id via
`Rs2ItemManager.getItemIdByName` (itself a client-thread bank-pin-widget check) — for every item in
a per-tick loop, once per watchlisted item.
**Result:** Fixed by computing it once per tick instead. Confirmed via jstack that this really was
adding real time to each tick beyond fix #1 alone.

### 3. getHeldQuantity() has the same bank-scan disease as #2
**Commit:** `c2c1c8d` (reverted)
**Found by:** jstack, a tick stuck 100+ seconds on ONE item inside
`Rs2Bank.isBankPinWidgetVisible` via `getHeldQuantity`.
**What was wrong:** identical shape to #2, different call site (`portfolio.getHeldQuantity(itemId)`
inside `buildRequestItem`).
**Result:** Fixed via one bulk `getAllHoldings()` snapshot per tick instead of a per-item call.
This was a real, severe, confirmed bug — a single item's fetch was seen stuck for 100+ seconds.

### 4. WikiHistoryBuffer's inline Firestore seed check blocked the tick thread
**Commit:** `2a59c67` (reverted)
**Found by:** noticing that DECIDE suggestions only ever came from a narrow band of low-item-id
watchlist entries (ids ~42-250), and that the same tick number kept re-appearing as HUNG forever
without ever completing.
**What was wrong:** `computeRollingFeatures` called a blocking `maybeSeedFromFirestore` inline, on
the tick thread, for every item — even though a separate background bulk-seeder
(`seedWatchlist`, added earlier the same day in commit `9e20982`) already existed. The watchlist
iterates in insertion order (roughly item-id order), so the tick thread reliably raced ahead of the
background seeder and blocked the instant it reached an unseeded item.
**Result:** Fixed by making the inline check fire-and-forget (queue a background seed, return
immediately, treat as cold-start in the meantime). **This fix directly explained a real, user-
visible symptom** ("it just goes down the list, longbows/shortbows/Raw-prefixed items only") that
was initially and incorrectly suspected to be a training-data bias in the model itself. It was not
— it was this bug.

### 5. Item name resolution fallback produced an unsearchable placeholder
**Commit:** `fa5d81c` (reverted)
**Found by:** log line `"failed to submit order BUY 94x item 21111 @ 1400 gp, will retry next
tick"` repeating dozens of times for the same order, forever.
**What was wrong:** `PPOFlipperStarScript.toDecision` fell back to a synthetic
`"item " + itemId` string whenever `Rs2ItemManager.getItemComposition` returned null (which it did
for some real, tradeable items). That placeholder was stored as the order's real item name and fed
into `Rs2GrandExchange.buyItem(name, ...)`, which searches the GE **by name string** — a placeholder
can never match anything, so the order silently failed and retried the identical doomed order every
tick, forever.
**Result:** Fixed by falling back to the wiki mapping cache (from fix #1) for the name, and by
making `PPOFlipperDecision.isActionable()` require a *real* resolved name — skipping the suggestion
entirely (logged at debug) rather than ever emitting another unsearchable placeholder order.
**User confirmed this fix worked** ("no missing names").

### 6. Confidence-based sorting before the queue-depth cap
**Commit:** `a109ed6` (reverted) — **user singled this one out as a good change worth
keeping in mind for later.**
**Found by:** user observation — "it just goes down the line" for autonomous BUY submissions,
always the same low-item-id family (arrowtips, bolts, longbows/shortbows, herb potions (unf),
grimy herbs).
**What was wrong:** once DECIDE ticks started reaching the *full* watchlist (after fixes #1-4),
a single tick could produce 500-700+ suggestions, but `autonomouslySubmit`'s queue-depth cap
(`maxActiveOffers * 3`, typically ~24) meant only the first ~24 could ever actually submit before
the loop broke. "First" meant *lowest item id* — `suggestions` preserved watchlist insertion order,
which is effectively item-id order (the watchlist is seeded from `modelTrainedItems`, itself sorted
by id). This looked exactly like a strong model preference for a specific item family, but was
pure position-in-list luck, unrelated to how good any given suggestion actually was.
**Result:** Sorted each action-type group (SELL still first, from an earlier, still-live fix) by
confidence descending before applying the cap. **Confirmed live with real evidence**: after this
fix, autonomous submissions immediately showed items spanning nearly the entire id range (121,
351, 1658, 1702, 2007, 5310, 6328, 6705, 10937, 11069, 11943, 19672, 32333, 32349, ...) instead of
clustering under id ~250, with confidence values correctly ranked highest-first (0.86 down to
0.65). **This is a genuinely validated, working improvement** independent of every hang/lag bug —
worth re-applying on its own regardless of what else does or doesn't come back.

### 7. SELL exempted from the queue-depth cap
**Commit:** `5ac03da` (reverted)
**Found by:** user noticed a real, high-confidence (0.94-0.95) SELL suggestion sitting unconfirmed
in the panel's "Suggested offers" for 10+ minutes, looking like it was never being auto-submitted.
**What was wrong:** the queue-depth cap check ran fresh every loop iteration against the *live*
queue, not a per-tick budget. Once BUY volume pushed the backlog chronically above the cap from
prior ticks' still-pending orders, the very first item checked each tick already failed the cap —
including a SELL that fix #6 had just sorted to the very front.
**Result:** SELL orders now skip the backlog-depth check entirely (a SELL shrinks the backlog, so
gating it on backlog size is self-defeating; only BUY, which grows the backlog, is gated).

### 8. decision/request document approaching Firestore's 1MiB limit
**Commit:** `42e1d39` + `452695f` (fix to a rules typo) (both reverted)
**Found by:** jstack showing the DECIDE thread stuck 300+ seconds inside
`PPOFlipperStarFirestoreClient.putDecisionRequest`'s HTTP PATCH call.
**What was wrong:** the single `decision/request` document's `items` array had grown to ~0.9MB for
an ~885-item watchlist — right at Firestore's 1MiB-per-document hard limit, and large/slow enough
to sometimes exceed its own declared HTTP timeout.
**Result:** Split into a small control document (`tickId`/`chunkCount`) plus several
`decision/request_chunk_N` documents (150 items each). **This fix was real and necessary but
incomplete on its own** — see #10.

### 9. HttpRequest.timeout() unreliable on this JDK/environment — found 4 separate times
**Commits:** `961d899`, `dad0892`, parts of `e92d494` (all reverted)
**Found by:** jstack, repeatedly, across totally different call sites (item mapping fetch,
Firestore decision/request write, wiki price per-item fetch, wiki bulk price fetch) — each one
independently found stuck for minutes despite declaring a `.timeout(Duration.ofSeconds(N))` on the
`HttpRequest`.
**What was wrong:** on this specific JDK/environment, `HttpRequest.timeout()` alone did not
reliably bound `HttpClient.send()` calls. This was the single most surprising and time-costly
discovery of the day — every "add a timeout" fix using the standard API kept *not* actually
working, which is why hangs kept recurring even after seemingly being fixed.
**Result:** A shared `sendBounded()` helper (thread submitted to an executor + `Future.get
(timeout)`, an independent JVM-level bound) was applied to the hot-path calls. Worth remembering
for **any future HTTP call in this codebase**: don't trust `HttpRequest.timeout()` alone; wrap it.

### 10. Sequential chunk writes (from #8) added up to 25-30s on their own
**Commit:** `2020fa1` (reverted)
**Found by:** noticing "successful" ticks were landing suspiciously close to exactly 30 seconds
(the tick watchdog's own threshold) even though `decisionResponseTimeoutSeconds` was still 5 —
a dead giveaway of a race between the watchdog and the tick's real completion, not a real 5-30s
model round trip.
**What was wrong:** writing ~6 chunk documents *sequentially* (each a real HTTP round trip, ~2-4s
under ordinary conditions) added up to 25-30+ seconds of wall-clock time by itself, before the
5-second response-polling window even started. A smaller-scale recurrence of the same "cost grows
linearly with watchlist size" problem chunking (#8) was meant to solve in the first place.
**Result:** Chunk writes submitted concurrently to a thread pool and awaited together instead of
in a loop. Total write time dropped to roughly one round trip's worth regardless of chunk count.

### 11. Wiki price fetch flood — "too many concurrent streams"
**Commit:** `e92d494` (reverted)
**Found by:** 6,343 logged wiki price fetch failures in one session, many with the literal message
"too many concurrent streams"; user directly asked "are we making hundreds of requests, are they
blocking."
**What was wrong:** the once-per-tick bulk price cache-warm (`refreshAllPrices`) was itself
unbounded (bug #9's pattern) and failing/hanging silently. Every time it failed, **every item in
that tick's watchlist fell back to its own individual per-item fetch simultaneously** — up to ~880
near-simultaneous connections to the same wiki host, which the server started rejecting outright.
**Result:** Bounded the bulk fetch properly (fails fast instead of hanging) and capped the
per-item fallback to a small shared concurrency limit (4) via a semaphore, so even a total bulk-
fetch failure can never again flood the wiki host.

### 12. Thread count bloat from adding a dedicated pool per fix
**Commit:** `53b88f6` (reverted)
**Found by:** jstack showing ~46 of this plugin's own live threads (4 separate fixed-size pools,
one added per fix above, each sized independently in isolation) when the user asked "how do you
manage to make it worse every time."
**What was wrong:** not a correctness bug — nothing here was individually stuck — but a genuine,
self-inflicted process-health cost: that many live OS threads adds real scheduling/context-switch
overhead, a believable contributor to the "still feels laggy" complaints even after the hangs
themselves were fixed. This was the direct, honest answer to "why does it keep getting worse": each
fix added its own isolated infrastructure instead of sharing what already existed.
**Result:** Consolidated all four pools into one shared 16-thread executor, preserving each
original concurrency cap via a `Semaphore` instead of a separate pool.

### 13. Two other small, real fixes along the way (both reverted)
- **`e658066`** — `Guardrails.checkBuyLimit` was still calling the per-item (un-cached)
  `Rs2GrandExchange.getItemMappingData` directly, missed when fix #1 was first written. Routed
  through the same bulk cache.
- **Seed-watchlist button pulling a stale checkpoint** (`2e297b6`) — the "Seed watchlist from
  trained items" panel button had a hardcoded git-commit constant pointing at the *previous*
  (9.5M-step, 311-item) model instead of the newly-deployed (12M-step, 885-item) one, so it only
  ever added ~300 items. Unrelated to the DECIDE-loop bugs above but found and fixed the same day,
  right before the watchlist was grown to ~885 and this whole chain started.

### 14. Non-code items from the same day
- **Bank PIN** (`d91d2f3`, reverted): added a plugin-config fallback (`Rs2Bank.handleBankPin
  (String)`, masked config field) for when the RuneLite login-profile Bank PIN field isn't set.
  User explicitly declined hardcoding the literal PIN into source.
- **Inventory-space guardrail** (`9836f33`, reverted): `minFreeInventorySlots` config, enforced
  only when `inventoryOnlyMode` is on, since the trained policy has no inventory-space awareness at
  all (would require a full retrain to add as a real observation feature — not done).
- **SELL-confidence-below-threshold logging** (`9836f33`, reverted): visibility into why "no SELL
  suggestions" was happening for freshly-bought positions (answer: likely genuinely low SELL
  confidence on positions only minutes old, not a bug).
- **Diagnostics log** (`0d73aaa`, reverted; **re-added in trimmed form as `a6e3017`**): a dedicated
  `~/.runelite/ppoflipperstar-decide.log`, one line per tick, separate from the noisy shared
  `client.log`. This is what made several of the above races/patterns provable rather than guessed.
  The version re-added afterward drops the `skipped_mapping_timeout`/`skipped_malformed`/`HUNG`
  fields, since those depended on machinery (the bounded mapping fetch, the tick watchdog) that
  stayed reverted - the restored version logs outcome/duration/watchlist size/items scored/
  suggestion count only.
- **Panel health indicators** (`803a63c`, reverted; **"Last DECIDE tick" row re-added as
  `a6e3017`**): a "DECIDE TICK STALLED" banner and a "Last DECIDE tick" status row with staleness
  color-coding, so a hang is visible in the UI without reading logs. Only the staleness row was
  re-added - the "STALLED" banner is driven by `decideTickHangCount`, which only the tick watchdog
  (`800bdb8`) ever increments, and that watchdog stayed reverted.
- **Per-order headroom/GP logging** (`556bc2d`, reverted, not yet re-added): logged buy-limit
  headroom and available gold alongside every autonomous BUY, to distinguish a small computed
  quantity caused by near-exhausted buy-limit headroom from one caused by low available gold.

## What did NOT work (superseded, insufficient, or reverted-then-needed-again)

Several fixes below were real, correctly-targeted responses to genuine evidence at the time, but
turned out to be either wrong, incomplete, or actively counterproductive once more evidence came
in. Recorded here so they aren't tried again the same way without remembering why they fell short.

- **Rate-limiting the per-item mapping fetch (`48ceeb8`) — DID NOT WORK, solved the wrong
  problem.** First response to the mapping-fetch hangs was to reduce concurrency from 8-way to
  serial with a 150ms stagger, on the theory that concurrent requests were overwhelming the wiki
  server. This was wrong: the real problem (found next, fix #1 in the section above) was that
  *every single call*, concurrent or not, re-downloaded the entire ~860KB mapping file - rate-
  limiting just made the same redundant work happen more slowly and predictably instead of fixing
  the redundant work itself. Superseded same-day by `d2efffc`.
- **Bounding the per-item mapping fetch to a 2s timeout (`961d899`) — DID NOT WORK on its own,
  cut off legitimately slow requests.** Once the wiki's real bulk-fetch latency was measured at
  ~2s (not "hung", just slow because it's fetching everything), a 2-second bound was actively
  wrong - it was timing out requests that would have succeeded a moment later, so items kept
  reporting `skipped_mapping_timeout` even under normal conditions. Superseded same-day by
  `d2efffc`, which made the per-item fetch unnecessary altogether rather than trying to bound it
  correctly.
- **`HttpRequest.timeout()` as a bounding mechanism — DID NOT WORK, at all, four separate times.**
  Every attempt to bound a slow HTTP call by declaring `.timeout(Duration.ofSeconds(N))` on the
  request failed to actually bound it in practice on this JDK/environment - confirmed via jstack
  at four independent call sites (item mapping fetch, Firestore decision/request write, wiki price
  per-item fetch, wiki bulk price fetch), each one found stuck for minutes despite a declared
  timeout. This consumed a large fraction of the day's debugging time because each new "add a
  timeout" fix looked correct on paper and still didn't work. The only mechanism that actually
  worked was an independent JVM-level bound (a thread submitted to an executor + `Future.get
  (timeout)`), added in `dad0892` as a shared `sendBounded()` helper.
- **Chunking `decision/request` alone (`42e1d39`) — INCOMPLETE, fixed the size problem but
  introduced a new latency problem.** Splitting one ~0.9MB document into several small ones fixed
  the Firestore-1MiB-limit/oversized-write-timeout problem it targeted, but writing those ~6 chunks
  *sequentially* silently added 25-30 seconds of new wall-clock cost to every tick - confirmed by
  noticing "successful" ticks landing suspiciously close to exactly 30 seconds (the unrelated tick
  watchdog's own threshold) even though the real response timeout was still 5. Not wrong, just not
  sufficient by itself; needed the follow-up concurrent-write fix (`2020fa1`) the same day to
  actually deliver the intended improvement.
- **The full `git reset --hard` rollback itself — arguably DID NOT WORK as hoped.** The rollback
  was requested because the growing pile of fixes felt like it kept making things worse. It
  successfully removed the thread-count bloat (#12) and the complexity churn, but it also
  immediately and predictably reintroduced the single worst bug of the day (#1, the per-item
  mapping fetch, confirmed stuck 14+ minutes per thread within minutes of the rollback) - because
  that fix and the "mess" were the same commits. A rollback of *code* cannot undo the *watchlist
  size* that made the mess necessary in the first place. This is why fix #1 had to be immediately
  re-applied standalone afterward.

## What's actually live right now

`c7a7073` (bulk item mapping fetch, fix #1) and `a6e3017` (pure-observability logging, see above).
Everything else described above is reverted to `b9fa5d1`'s behavior. The ~885-item watchlist is
still large enough that some of the other bugs
above (especially #2, #3, #9, #11) are plausibly still live risks; they just haven't been
re-confirmed since the rollback.

## Recommendation if picking this back up later

In rough priority order, if re-applying fixes one at a time again:
1. Fix #1 is already back.
2. Fix #6 (confidence sorting) — user-validated, no dependency on anything else reverted, safe to
   re-apply standalone the same way #1 was.
3. Fixes #2/#3 (bulk gold/holdings snapshot) — same shape as #1, low-risk, high-value if the
   watchlist stays this large.
4. Everything from #8 onward (chunking, HTTP bounding, wiki concurrency, thread pool
   consolidation) is real but was also the most complex and highest-risk part of the chain - worth
   doing as a single deliberate pass with the `sendBounded` lesson (#9) applied from the start,
   rather than bug-by-bug again.
