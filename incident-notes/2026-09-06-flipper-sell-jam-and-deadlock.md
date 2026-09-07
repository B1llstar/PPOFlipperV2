# Flipper SELL jam and eviction deadlock — 2026-09-06

## Context

Session started from a live report: an item sold ~60k gp above its real value. That led into a
long chain of fixes (15 commits) touching wiki price validation, GE slot eviction, stale-offer
detection, the SELL minimum-margin guardrail, and — unrelated to all of that — a new NMZ auto-repair
feature and some dashboard additions. The user later asked to roll the flipper branch back to
`f0a8c77` (the last commit before this session) because the pile of fixes felt like "too much extra
stuff." This document exists so nothing found today is lost even though the branch no longer applies
most of it.

**Current state:** `ppo-flipper-star` still has all 15 commits (untouched, still pushed to origin).
A new branch, `ppo-flipper-star-reverted`, was created at `f0a8c77` and is what's actually checked
out going forward. Nothing was deleted — every commit below is still reachable by hash from either
branch, or via `git cherry-pick <hash>` onto whatever comes next.

Full commit range: `f0a8c77..18c6f2e` on `ppo-flipper-star` (15 commits, 18 files, +1338/-42 lines).
Live artifact version of this document (nicer to read, has the exact live-config-value table):
`https://claude.ai/code/artifact/859e7a62-eba6-4169-85aa-867d25fcb907`

## Root causes found, in the order discovered

### 1. Wiki price data can be genuinely inverted
**Commit:** `94da979`
**Found by:** live incident — an item sold ~60k gp above its real value; log tracing showed
`insta-sell 67550` vs `insta-buy 494` from the wiki's own `/latest` API.
**What was wrong:** the wiki's "most recent trade" field can lag far behind the current market —
confirmed empirically that ~4.3% of all tradeable items (195/4523) show an inverted spread
(insta-sell above insta-buy) at any given time, caused by a stale one-off trade lingering in that
field.
**Fix:** reject any price where `insta-sell > insta-buy * 2.0` outright (`WikiPriceClient.parsePrice`),
rather than trusting it.

### 2. A rejected price's fallback could be the same tainted data
**Commit:** `710e455`
**Found by:** the same item (Purple robe bottoms) triggered the ~60k-over-value bug a second time,
even after fix #1 was live — because the SELL order's *own* price had already been computed by the
model from the same bad data before the sanity check existed, so "no live price → trust the order's
own price" had nothing safe to fall back to.
**Fix:** `clampToLivePrice` returns a `-1` sentinel when the live price is rejected; `submitNextOrder`
defers the whole submission for that tick instead of trusting an unverified fallback.

### 3. Eviction only ever considered BUY orders
**Commit:** `df1a562`
**Found by:** live report — GE offers sitting 20+ minutes with nothing evicting.
**What was wrong:** `evictForBlockedSell` only looked for a dud *BUY* to sacrifice for a blocked
SELL. When most active slots were SELLs and no BUY was eligible, nothing was evictable at all.
**Fix:** falls back to evicting a dud SELL (never the blocked SELL itself) when no eligible BUY
exists.

### 4. Epoch-zero acquisition timestamp trusted verbatim
**Commit:** `db06d77`
**Found by:** live "held 56+ years" stale-position forced-sell reports.
**What was wrong:** `PortfolioManager.reconcileFromFirestore` trusted a `weightedAcquisitionTimestampMillis`
of 0 (or less) from Firestore literally, which then made the stale-position auto-sell logic treat
the item as held since the Unix epoch — permanently forcing a SELL_100% for it.
**Fix:** falls back to `System.currentTimeMillis()` when the remote value is `<= 0`.

### 5. The dud-detection ramp was too lenient
**Commits:** `773e69c`, `99a8889`
**Found by:** confirmed via math after a user hunch — with defaults (10% threshold, 5min timeout),
a SELL filling 5% early then stalling completely stayed "protected" from dud-eviction until roughly
half the timeout had elapsed.
**Fix:** added fill-velocity tracking (`PPOFlipperOrder.lastFillProgressAtMillis`, bumped only when
`quantityFilled` actually increases) — `isDud` now also flags an order as a dud if it hasn't filled
anything new in `fillStallTimeoutSeconds` (default 45s), independent of the percent-vs-age ramp.
**Follow-up bug in the same fix:** `99a8889` — a plugin restart re-adopting an already-progressing
offer stamped this new clock to "now," making a genuinely healthy 38%-filled SELL look freshly
stalled 51 seconds later and get killed. Fixed by leaving the clock at "unknown" (0) for a
reconciled/adopted order instead of guessing "now."

### 6. `minOrderValueGp` invisible in the config panel
**Commit:** `374b084`
**Found by:** direct user report ("I can't edit that, there's no field").
**What was wrong:** declared as `long` — a type RuneLite's config UI silently can't render at all,
no error, no field.
**Fix:** changed to `int` (its sibling `maxGpPerOrder` was already `int`, which is why that one
worked and this one didn't).

### 7. The real deadlock: stale-dud abort gated on something being queued
**Commit:** `1d77166`
**Found by:** live log tracing — all 8 GE slots sat 15-18 minutes stale (0-2 filled, well past the
5-minute timeout, all flagged as duds), yet nothing was ever aborted.
**What was wrong:** `checkForFinishedOffers`'s abort branch required `queue.nextQueued().isPresent()`
in addition to `isDud`/`isStale` — the original reasoning was "don't force a re-decide on an idle
slot nothing else wants." But fresh DECIDE suggestions were themselves being rejected before ever
reaching `QUEUED` (rapid PPO's own margin-bar gate), so `nextQueued()` was permanently empty. A true
deadlock: nothing queues → nothing evicts → nothing sells → nothing queues.
**Fix:** dropped the `nextQueued()` requirement entirely — a stale, dud offer is aborted on its own
merits now, regardless of whether anything else is waiting for the slot. **This was the single
highest-impact fix of the session** — everything after this point in the log started actually
submitting and cycling again.

### 8. The SELL margin floor was pricing above the live market
**Commits:** `e043cc1` (decay, later dead-on-arrival), config-only change (margin set to 8%, then 0%)
**Found by:** even after fix #7, SELLs kept aborting 0-filled every cycle. Log tracing showed
`minSellProfitMarginPercent` (15%, later 8%) forcing a price well above what the model's own
live-spread price thought the market would pay — e.g. Willow logs raised from 25gp to 29gp,
Broad bolts from 56gp to 73gp — guaranteeing the offer would sit unfilled for the full 5-minute
stale timeout, every single cycle, forever.
**Decay mechanism added but never worked:** `e043cc1` added a per-item margin override that should
decay 1%/stuck-cycle toward a 10% floor. When the starting margin was later manually lowered to 8%
(below the 10% floor), the decay math (`next = max(floor, current - step)`) always clamped back up
to 10% — above the 8% starting point — so the "did it actually go down?" guard silently no-op'd
every time. **Zero decay log lines fired in the entire session.**
**Final fix (config only, no code change needed):** `minSellProfitMarginPercent` set to `0.0`,
which the existing code already treats as "guardrail disabled" — SELLs now go out at whatever price
the model computes from the live spread, unmodified. Confirmed live: after a client restart at
23:01:26, every SELL submission stopped showing a "raised sell price" log line.
**Tradeoff, called out explicitly:** with the margin at 0, nothing stops a SELL from filling below
tracked average cost. A log-only tripwire was added (`18c6f2e`,
`PPOFlipperStarScript.warnIfSoldBelowCost`) that warns after a fill lands if net-of-tax proceeds
came in under cost — doesn't change pricing or block anything, purely visibility.

### 9. Unrelated: a real GE-search bug on "Yak-hide armour (legs)"
**Commit:** `f0ca99d`
**Found by:** a stuck BUY for "Yak-hide armour (legs)" failing to submit on nearly every tick for
7+ minutes, discovered while investigating the SELL jam (turned out unrelated to it).
**What was wrong:** the wiki's own name for this item apparently isn't exactly what the in-game GE
search widget indexes — the same underlying class of problem the existing
`stripWikiDisambiguationSuffix` helper already patched for "(item)"/"(tablet)" suffixes, just not
this one. Confirmed via bytecode: `exact(false)`'s contains-match, the keyboard-typing path, and the
request builder all check out fine in isolation; the mismatch is specifically in what text the wiki
mapping vs. the live game client each call this item.
**Fix:** resolves the GE search string from the live client's own `ItemComposition.getName()` (by
item id, via `Rs2ItemManager.getItemComposition`, cached per id) instead of trusting the wiki's
mapping name verbatim — fixes the general case rather than adding another special-cased suffix regex.

## Also shipped this session, unrelated to the SELL jam

- **`3353396`** — reverted an earlier same-item BUY/SELL guard (a SELL of held stock and a fresh
  BUY are independent, non-contradictory decisions; the guard was overly cautious).
- **`17e772a`** — new feature in `nmz-debug`: detects a broken Dharok's armor piece by item ID,
  logs out of the NMZ instance, walks to Bob at Lumbridge, repairs, walks back. Two assumptions in
  it were never live-verified: Bob's exact coordinate, and the repair-all widget's exact button
  text (both flagged inline in `DharokRepairScript.java`).
- **`fe7b05e`, `6dd6e74`** — four real-time features added to the web dashboard (ActivityTicker,
  PnlSparkline, DecisionHeartbeat, CelebrationToast) plus a FIFO-matched per-trade profit table
  (`useMatchedTrades.js`), deployed live to `ppoflipperopus.web.app`.

## Live config values changed by hand (not in git, won't come back on their own)

These were edited directly in the RuneLite profile properties file this session and are **not**
reset by anything in git — restore by hand if the old behavior is wanted back.

| Setting | Before session | End of session |
|---|---|---|
| `minSellProfitMarginPercent` | 15.0 | 0.0 |
| `sellMarginDecayStepPercent` | (didn't exist) | 1.0 |
| `sellMarginDecayFloorPercent` | (didn't exist) | 10.0 |
| `fillStallTimeoutSeconds` | (didn't exist) | 45 |
| `minOrderValueGp` | 10000 (invisible in panel) | 10000 (now visible) |

## Bringing any of this back later

Every commit hash above is real and cherry-pickable individually onto `ppo-flipper-star-reverted`
(or wherever the branch goes next) without pulling in the rest of the session:

```
git cherry-pick 1d77166        # just the deadlock fix (recommend starting here if picking one thing)
git cherry-pick fe7b05e 6dd6e74  # just the dashboard features
git cherry-pick 17e772a        # just the NMZ repair feature
git diff f0a8c77..18c6f2e      # see the whole session's diff again
git show <hash>                # see any single commit's exact change
```

`ppo-flipper-star` (all 15 commits, unmodified) and `ppo-flipper-star-reverted` (at `f0a8c77`) both
exist on `origin` — nothing here required a force-push or history rewrite.
