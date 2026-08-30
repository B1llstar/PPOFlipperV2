# Handoff: GE Flipper exit (sell-timing) model

**Written:** 2026-08-30, end of a long session on branch `ge-star` (not yet
merged to `main`). This file is for whichever Claude session picks up next
— read this before touching anything below. Delete this file once the
sell-side model is built and this context is no longer needed, or once
its content has been absorbed into the relevant README files.

## What the user actually wants

An **advanced, adaptive algorithm** that decides both when to buy AND when
to sell, for real profit — not a fixed take-profit/stop-loss rule. The
user explicitly rejected the simple rule-based sell trigger (target margin
+ stop-loss) in favor of a real model-driven exit decision, and rejected
reusing the existing buy-side model as a rough sell signal too, once it
was explained that a real hold/sell decision needs a **HOLD** state
distinct from SELL, plus position-aware inputs (unrealized P&L, holding
duration) the current model was never given. The user's own insight
mid-conversation: *"won't we need something that can hold?"* — correct,
and that's the crux of why this needs a dedicated model, not a repurposed
one.

## Full context: what exists today

This is one big system, built incrementally over one long session, all on
branch `ge-star`:

### 1. GE Star V2 (`plugins/ge-star-v2/`) — the execution engine

A RuneLite/Microbot plugin that executes a queue of buy/sell orders on the
Grand Exchange. Runs its own state machine (`GeStarV2Script`), has a
sidebar panel (`GeStarV2Panel`) for manual order entry, guardrails
(`GeStarGuardrails`), and portfolio/cost-basis tracking
(`portfolio/GeStarPortfolio.java`, `portfolio/CostBasisEntry.java`).

Key classes:
- `GeStarOrderQueue` — the shared order queue. Has a
  `addOrder(GrandExchangeAction, String, int, int) -> long` and
  `getOrderStatusName(long) -> String` method **specifically designed for
  cross-plugin reflective access** (see "Critical architecture fact"
  below) — primitives-only signatures on purpose.
- `GeStarOrder` — one order (BUY/SELL, item name, quantity, price,
  mutable status QUEUED/SUBMITTED/DONE/SKIPPED/FAILED, fill quantity).
- `GeStarPortfolio` — tracks live holdings (bank + inventory, read fresh
  every call) and a **persisted cost-basis ledger** (weighted-average cost
  per item, realized P&L), updated from every completed GE fill. This is
  the data source any exit model will need for "what did I pay, how much
  do I hold."
- `GeStarV2Script` — the actual execution state machine:
  `GOING_TO_GE -> SUBMITTING_ORDERS -> MONITORING_OFFERS -> DONE`, plus
  `PREPARING_FUNDS_OR_ITEMS` for bank withdrawals.
- Also has a Firestore-backed web sync (`GeStarFirestoreSync`,
  `GeStarFirestoreClient`, `GoogleServiceAccountAuth`) so a companion
  Firebase web UI (`firebase/`, project `ppoflipperopus`) can also submit
  orders into the same queue. Not relevant to the exit-model work but
  exists and works.

**Currently GE Star V2 only executes what's queued — it has no opinion on
strategy.** FlipperStar (below) is the "brain."

### 2. Data pipeline (`data/`) — training data + the buy-side model

Python, its own venv (`data/venv/`, needs Python 3.13 via
`/opt/homebrew/bin/python3.13` — the system python3 is 3.9, too old for
current pandas/pyarrow).

- `data/pipeline/wiki_client.py` — shared HTTP client for the OSRS Wiki
  real-time prices API (`prices.runescape.wiki`), with a proper User-Agent
  and retry/backoff. **Read this before writing any new fetch code** — the
  wiki API explicitly discourages per-item-looping; always use the bulk
  endpoints (`/5m`, `/1h`, `/6h`, `/24h`, `/latest` each return every
  item's data in one call).
- `data/pipeline/fetch_mapping.py` / `fetch_5m_history.py` — pulled 6
  months (March–August) of 5-minute price/volume candles, ~79M rows,
  resumable (monthly Parquet partitions). Already run; data lives in
  `data/raw/` (gitignored, not committed — regenerate via these scripts if
  needed, takes about 30-45 min end to end with retries).
- `data/pipeline/build_features.py` — computes rolling
  spread/volatility/volume/momentum features (1h/6h/24h windows) **and a
  forward-looking label**: `label_margin_pct` = the realistic achievable
  round-trip margin over the **next 4 hours** if you bought at that
  block's low price and sold at the best point in the next 48 five-minute
  blocks, sized by realistic fill volume (not just "best price seen" —
  actually fill-feasibility-aware). This is the **buy-side entry label**.
  Manually verified correct against a brute-force scan before trusting the
  vectorized version. Output: `data/processed/features.parquet` (52M rows,
  gitignored).
- `data/pipeline/prepare_training_data.py` — liquidity filter (min price,
  min achievable qty, **min trailing 1h volume** — this last one mattered
  a lot, see "Bugs found" below), outlier clipping, and a **time-based**
  train/validation split (never random — would leak future info) with a
  gap matching the 4h label horizon. Streams through the data in small
  batches; **this machine runs under genuine sustained memory pressure**
  (confirmed via `sysctl vm.swapusage` showing real swap usage even at
  idle) — a naive full-file load already crashed once this session. Any
  new data-processing script needs the same streaming discipline.
- `data/pipeline/train_model.py` — trains a LightGBM regressor on 13
  features to predict `label_margin_pct`. Reports both RMSE/MAE and a
  ranking-quality metric (true mean margin of the model's top-K picks vs.
  the true best-possible top-K) — the ranking metric matters more than raw
  RMSE for how this is actually used. Current trained model:
  `data/models/margin_model.txt` (LightGBM native format, ~2.5MB,
  committed to git — small enough to version, unlike the datasets).
  Metrics: `data/models/margin_model_metrics.json`.
- `data/service/` — a FastAPI scoring service (`main.py`,
  `live_features.py`). `GET /candidates?limit=N` scans the currently
  liquid item universe via 4 bulk wiki API calls (`/latest`, `/1h`, `/6h`,
  `/24h` — no per-item looping), computes a **live approximation** of the
  training features (documented in `live_features.py`'s docstring exactly
  what's approximated and why), scores every candidate through the model,
  returns the top-N ranked by predicted margin. Runs on `127.0.0.1:8420`.

### 3. FlipperStar (`plugins/flipper-star/`) — the decision-maker plugin

A **separate** RuneLite plugin (own Gradle module, own sideloaded jar) that
calls the scoring service and queues buy orders into GE Star V2's queue.
Config-driven (min margin threshold, GP budget per flip, max open flips,
optional auto-scan interval). Has its own panel with a manual "Scan now"
button plus an auto-scan toggle (off by default).

**Currently BUY-only.** `FlipperStarEngine.scanAndQueue()` never emits a
SELL order. This is the gap the user wants filled — and wants filled
properly (model-driven), not with a quick rule.

## Critical architecture fact: cross-plugin communication is reflection-only

**Read this before writing any code that touches both GE Star V2 and
FlipperStar.** RuneLite gives every sideloaded plugin jar its own
`ClassLoader` (verified directly against `PluginClassLoader`'s bytecode
this session — each sideloaded jar gets a fresh `PluginClassLoader`
instance). This means FlipperStar **cannot** compile against or directly
reference `GeStarOrder`, `GeStarOrderQueue`, or `GeStarPortfolio` as Java
types — even though the source is sitting right there in the same repo, at
runtime they are two separately-sideloaded jars and Guice's singleton
scoping does not span them.

The existing solution (`plugins/flipper-star/.../GeStarBridge.java`):
finds the live `GeStarV2Plugin` instance via
`Microbot.getPluginManager().getPlugins()`, then calls methods on it
**reflectively**, restricted to method signatures using only types that
come from the shared Microbot client jar or `java.lang`/`java.util`
(`String`, `int`, `long`, `GrandExchangeAction` — all loaded once by the
classloader every plugin's own loader delegates to as parent, so identical
across plugins). This is the same pattern the Microbot-Hub's own
`geflipper` plugin uses to reach the third-party Flipping Copilot plugin.

**If the exit model needs GE Star V2 to expose something new** (e.g. "give
me every currently-held position with its cost basis and holding
duration"), the pattern is: add a primitives-only method to
`GeStarPortfolio` or `GeStarOrderQueue` (see `getOrderStatusName(long)` for
the exact style — small, deliberate, documented as part of the
cross-plugin contract), then add a matching reflective call to
`GeStarBridge`. Do not try to pass a `GeStarOrder` or any other
plugin-defined class across the boundary — it will compile fine and fail
at runtime with a confusing `ClassCastException` (this exact failure mode
already happened once this session for an unrelated reason — a
`ClassCastException: String cannot be cast to Map` from a completely
different bug, but it's the same category of "looks fine at compile time,
breaks at runtime across a classloader boundary" trap).

## Full list of live bugs found and fixed this session (all in `plugins/ge-star-v2/`)

Every one of these was caught by testing live in-game, not by code
review — a reminder that this whole system needs real testing, not just
compiling, before trusting new changes. In commit order on `ge-star`:

1. **`Rs2Inventory.count("Coins")` used a string name instead of item ID**
   — fixed to `ItemID.COINS` (995), matching how `geflipper` does it.
2. **`Rs2Inventory.count(int)` doesn't sum quantity — it's
   `Stream.count()`, counting matching inventory *slots/stacks*, not
   summed quantity.** For a single coin stack this always returns `1`
   regardless of the actual gp amount. The real fix was
   `Rs2Inventory.itemQuantity(int)`, which does the actual
   `mapToInt(Rs2ItemModel::getQuantity).sum()`. This was the *real* cause
   of a "insufficient funds" bug that the `ItemID.COINS` fix alone did not
   resolve — always verify a fix against the actual rebuilt jar's
   bytecode when a "fix" doesn't resolve a live-reported bug, don't just
   assume the first plausible cause was the real one.
3. **`Rs2GrandExchange.buyItem(String, int, int)` and
   `sellItem(String, int, int)` have the SAME signature shape but a
   DIFFERENT parameter order from each other**:
   `buyItem(name, price, quantity)` but `sellItem(name, quantity, price)`.
   Verified by decompiling both methods' bytecode (each builds a
   `GrandExchangeRequest` via its builder — you can see which builder
   method each `int` argument feeds into). The `buyItem` call site had
   price and quantity swapped. **Do not "clean this up" to look symmetric
   without re-verifying against the jar — it really is asymmetric.**
4. **`GeStarPortfolio`'s cost-basis ledger was never actually being saved
   as JSON.** `ConfigManager.setConfiguration(group, key, Object)` only
   has clean JSON serialization for a few special-cased types (Color,
   Enum, Set, a couple others — verified against
   `ConfigManager.objectToString`'s bytecode); for a plain `Map` it
   silently falls back to `Object.toString()`, producing Java's debug
   format (`{995=CostBasisEntry@...}`), not valid JSON. This crashed the
   entire plugin on startup (`ClassCastException: String cannot be cast to
   Map`) the moment it tried to load that "ledger" back. Fixed by
   hand-rolling `Gson.toJson`/`fromJson` and storing/loading through
   `ConfigManager`'s plain-`String` overloads instead of the generic
   `Type`-based ones. **If you ever persist a `Map` or other non-trivial
   object through `ConfigManager` again, serialize it to a JSON string
   yourself first — do not trust the generic `setConfiguration(group,
   key, Object)` overload to do it for you.**
5. **Stopping the script and hitting Execute again would re-buy orders
   that were already live on the real Grand Exchange.** `Stop` only halts
   the script's tick loop — it was never wired to cancel the actual
   in-game GE offer. But `run()` on every Execute blindly reset every
   order still marked `SUBMITTED` back to `QUEUED`, assuming it must be
   orphaned. Fixed with `reconcileSubmittedOrders()`: on resume, before
   resubmitting anything, it scans `Rs2GrandExchange.getActiveOfferSlots()`
   and matches live offers back to `SUBMITTED` orders by
   action+item+quantity+price; only genuinely orphaned orders (no matching
   live offer) get reset to `QUEUED`.
6. **The scoring service had buy/sell prices swapped.** The wiki
   `/latest` endpoint's `high` field is the most recent *insta-buy* trade
   (what buyers are currently paying) and `low` is the most recent
   *insta-sell* trade (what sellers are currently getting) — but
   `live_features.py` set `current_buy_price = avg_low_price` and
   `current_sell_price = avg_high_price`, backwards. A buy order competes
   with other buyers and should price near the high, not the low. This
   directly caused a live-reported bug: a buy order priced ~40gp under
   what buyers were actually paying for a ~140gp item. Fixed and verified
   live against the real wiki API (buy price is now consistently higher
   than sell price for every candidate, as it should be).
7. **No hard ceiling on how much a buy order could actually pay.** The
   price-deviation guardrail only *rejects* an order too far from guide
   price (default 25% tolerance) — generous enough that a legitimately-
   priced-when-queued order could still submit well above the live price
   if the market moved before it was processed. Added
   `clampToLivePrice()`, called right before every submission: a BUY
   order's actual submitted price is capped down to the live insta-buy
   price if the queued price is higher; a SELL order's price is floored up
   to the live insta-sell price if lower. This is a **hard, un-configurable
   guarantee**, separate from the softer guardrail.

## What's next: the exit (hold/sell) model

This is the actual next task. Scope, as discussed with the user:

### Why the existing buy-side model can't just be reused for selling

The trained model (`data/models/margin_model.txt`) predicts: *"given this
item's current market state, what's the best achievable round-trip
buy-then-sell margin over the next 4 hours?"* It has no concept of:
- What you actually paid (cost basis)
- How long you've already held the position
- Unrealized P&L at the current price

A real exit decision is a **HOLD vs. SELL** classification (or a "value of
holding vs. value of selling now" regression), not a repurposed entry
signal. Re-scoring held items with the buy-side model and selling when the
predicted margin looks bad would systematically sell too eagerly on normal
short-term dips, because "is this still a good NEW entry" and "should I
exit my EXISTING position" are different questions with different correct
answers even at the same market price.

### Concrete plan (comparable scope to the buy-side model build)

**1. New label in the data pipeline** (new file, e.g.
`data/pipeline/build_exit_labels.py`, or extend `build_features.py`):

For each point in the historical 5-minute candle data, simulate: *"if I
had bought at this price at this time, and I'm now at some later point T+k
still holding, what is the optimal decision — hold or sell?"* This needs:
- A defined **holding horizon** to simulate over (e.g. up to 24h — probably
  longer than the 4h buy-side horizon, since a real position might be held
  longer while waiting for a good exit).
- The label at each (purchase_time, purchase_price, current_time) triple:
  something like "does holding further from here still improve expected
  outcome vs. selling now at the current insta-sell price" — this could be
  framed as a regression (expected forward return from holding vs. selling
  now) or a classification (hold=1/sell=0 based on whether current price is
  within some tolerance of the eventual realized maximum before it decays).
  **This label design is the hardest and most important part — get this
  wrong and the model will look fine in validation but make bad decisions
  live, exactly like the buy-side label needed care (see
  `build_features.py`'s `compute_forward_label` for how much rigor went
  into making that label realistic/fill-feasibility-aware, not just "best
  price in the window").**
- Watch for the same kind of data-quality trap as before: illiquid/
  low-volume items produced absurd label values on the buy side (fixed
  with `--min-volume-1h`) — the exit label will need the same liquidity
  guardrails, probably reusing the exact same filter.

**2. New features**: everything the buy-side model already has (spread,
volatility, volume, momentum at 1h/6h/24h), **plus position-aware
features**:
- Unrealized P&L % (current live price vs. cost basis)
- Holding duration (how long since simulated purchase)
- Possibly: price trajectory since purchase (has it been rising or
  falling since you bought, not just recently)

**3. New model + training run**: same LightGBM approach, same
memory-conscious streaming design (`prepare_training_data.py`'s two-pass
streaming pattern, `train_model.py`'s bounded `max_bin`/`num_threads`).
Verify with the same rigor: manually spot-check the label against a
brute-force calculation before trusting the vectorized version (this
caught nothing wrong on the buy side, but the check itself is what
justified trusting it — do the same here). Watch the ranking-quality
metric, not just RMSE.

**4. New serving endpoint** on the scoring service: something like
`POST /should-sell` or `GET /positions/{item_id}/decision`, taking (at
minimum) item_id, cost basis, holding duration as input from the caller
(GE Star V2 already tracks this in `GeStarPortfolio` — FlipperStar would
need to pull it via `GeStarBridge` and pass it to the scoring service).
Returns hold/sell (and maybe a confidence/urgency score).

**5. Plugin-side wiring**: `FlipperStarEngine` gains a sell-side scan pass
— for each currently-held position (via `GeStarBridge.getHeldQuantity`/
a new reflective call to get full position details from
`GeStarPortfolio.getOpenPositions()` — that method already exists,
returns `List<CostBasisEntry>`, just needs a primitives-only bridge method
added, following the `getOrderStatusName` pattern), calls the new
sell-decision endpoint, and if it says sell, queues a SELL order via
`GeStarBridge.addOrder(GrandExchangeAction.SELL, ...)` (already supports
SELL, just never called with it yet) priced via the same
`clampToLivePrice`-equivalent logic GE Star V2 already has on the buy
side (the sell side of `clampToLivePrice` already exists and works —
floors the price up to live insta-sell rate).

### Also worth doing alongside this (smaller, related)

- `GeStarPortfolio.getOpenPositions()` exists but has no cross-plugin
  bridge method yet in `GeStarOrderQueue`/`GeStarBridge` — needed for step
  5 above. Follow the exact pattern of `getOrderStatusName(long)`: add a
  method to `GeStarPortfolio` (or a new small primitives-only DTO-shaped
  return, since `CostBasisEntry` itself is a plugin-defined type and can't
  cross the classloader boundary directly — probably needs a method like
  `String getOpenPositionsJson()` returning pre-serialized data, or several
  parallel primitive-returning methods) and a matching `GeStarBridge`
  method.
- The `data/README.md` and `plugins/flipper-star/.../docs/README.md`
  files are kept up to date with real, verified information throughout
  this session — read them, they have more detail than this handoff on
  the *current* (buy-only) system. Update them once the exit model ships.

## Testing discipline established this session (keep following this)

- **Verify against the actual jar's bytecode**, not just source-level
  review, when a fix might not have actually taken effect the way it
  looks like it should — `javap -c -p` on the relevant `.class` file after
  building, confirm the expected method calls are actually there. This
  caught real problems twice this session (bug #2 and #3 above).
- **Verify labels/computations against a brute-force manual check** before
  trusting a vectorized/optimized version, on real data, not synthetic
  data.
- **This machine has genuinely tight, sustained memory pressure**
  (`sysctl vm.swapusage` shows real swap usage even at idle) — any new
  data-processing script needs to stream, not load-everything-at-once.
  Test any new heavy computation's memory footprint before running it at
  full scale, and prefer small batches over large ones.
- **Test live in-game** before considering something fixed — multiple
  "fixes" this session looked correct in source review and were not; the
  user catching live behavior and reporting it precisely (exact log lines,
  exact screenshots) is what actually found the real bugs.
