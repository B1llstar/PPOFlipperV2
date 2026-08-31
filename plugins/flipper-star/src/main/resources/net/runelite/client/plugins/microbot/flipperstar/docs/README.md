# FlipperStar

Grand Exchange flip *decision-maker*: scans the currently-liquid item universe
via a local scoring service (a trained margin-prediction model, see `data/`
at the repo root), filters candidates against your risk settings, and queues
buy orders into [GE Star V2](../../../gestarv2/docs/README.md)'s order
queue. It also scans every currently-held position through a dedicated exit
model and queues sell orders for anything the model calls SELL - "hold" has
no explicit action, a position it doesn't flag just isn't touched that scan.
FlipperStar never executes anything in-game itself - GE Star V2 owns that
(clicking, guardrails, fill detection); FlipperStar only decides what goes
into the queue.

## Setup

1. **Train the model and run the scoring service** (one-time, then keep the
   service running alongside the client):
   ```bash
   cd data
   source venv/bin/activate   # see data/README.md for full setup
   cd service
   uvicorn main:app --host 127.0.0.1 --port 8420
   ```
2. Enable **GE Star V2** (FlipperStar queues into it).
3. Enable **FlipperStar**, open its sidebar icon.
4. For fully unattended operation, turn on **Automate** in config (see
   "Automate" below) - one toggle instead of clicking Scan/Execute yourself.
   Otherwise, click **Scan now** to run one pass manually.

## How it works

0. **Inventory check**: if inventory has no free slots
   (`Rs2Inventory.isFull()`), the buy scan is skipped entirely for that
   cycle - buying is inventory-only now (see
   [GE Star V2's docs](../../../gestarv2/docs/README.md)'s "Portfolio &
   cost basis" section), so a BUY that filled with nowhere for the items
   to land would just fail. The exit scan (step 5) still runs regardless -
   a full inventory is exactly when freeing space via a model-approved
   sell matters most, and this never forces a sell the exit model
   wouldn't otherwise recommend on its own.
1. **Scan**: calls the scoring service's `GET /candidates`, which scans
   currently-liquid items and returns them ranked by predicted round-trip
   margin (see `data/service/main.py`).
2. **Filter**: drops candidates below `Min predicted margin %`, anything
   that would exceed `Max open flips` (FlipperStar-originated orders still
   QUEUED/SUBMITTED in GE Star V2), and items already held from a prior
   flip that hasn't sold yet.
3. **Size**: quantity is capped by both `Max GP per flip` and the item's GE
   buy limit (from the scoring service's response) - never overspends the
   budget, never asks for more than could actually be bought in one 4-hour
   window.
4. **Queue**: adds a BUY order into GE Star V2's queue at the item's
   current buy price. From there, GE Star V2's own guardrails and execution
   take over completely - FlipperStar has no further say in what happens to
   an order once queued.
5. **Exit scan** (if `Scan positions for exit` is enabled): pulls every open
   position from `GeStarPortfolio` (item, quantity held, average cost,
   holding duration) via the bridge, sends them in one batched call to the
   scoring service's `POST /should-sell`, and for anything it returns as
   `SELL`, queues a SELL order into GE Star V2's queue at the live
   insta-sell reference price - sized to the *smaller* of the ledger's
   tracked quantity and what's actually in live inventory right now (see
   "Portfolio & cost basis" in [GE Star V2's docs](../../../gestarv2/docs/README.md)
   for why: the ledger only updates via a tracked GE Star V2 fill, so it
   can't notice an item leaving inventory some other way - manually sold,
   banked, traded). A position with nothing actually held is skipped
   entirely (logged, counted in the scan summary) rather than queuing a
   SELL that GE Star V2's guardrail would just reject every cycle forever.
   Runs as the last step of the same Scan action (manual or auto), not a
   separate button/timer - see `FlipperStarConfig`'s "Exit (sell) scanning"
   section.

The exit decision comes from a dedicated model (`data/models/exit_model.txt`,
trained via `data/pipeline/train_exit_model.py`) - not a repurposed version
of the buy-side margin model. A real hold/sell call needs position-aware
inputs (unrealized P&L, holding duration) and a HOLD state the entry model
was never given. Off by default (`exitScanEnabled`) until you've watched it
alongside manual buy scanning for a while, same caution as auto-scan below.

## Automate

A single config toggle (`automateEnabled`, top of FlipperStar's config) for
fully unattended buy+sell+hold operation, with no manual Scan clicks and no
separate Execute click in GE Star V2's panel. Turning it on:

1. Starts GE Star V2's script if it isn't already running (the reflective
   equivalent of clicking **Execute**), via `GeStarBridge.startScriptIfNotRunning`.
2. Turns off GE Star V2's **"Stop script when queue is empty"** setting
   (`GeStarBridge.disableGeStarStopWhenOrdersComplete`, writing directly into
   `gestarv2`'s config through the shared `ConfigManager` - no reflection
   needed for a config write, unlike calling another plugin's methods), so
   its script keeps running instead of shutting down once its queue drains -
   see [GE Star V2's docs](../../../gestarv2/docs/README.md) for why that
   matters (its `DONE` state re-checks the queue every tick and only
   resumes if the script hasn't stopped itself).
3. Turns on both `autoScanEnabled` and `exitScanEnabled` and starts the
   auto-scan timer.

From there, every auto-scan cycle queues buy candidates and scans open
positions for exit, and GE Star V2 (now never stopping on its own) picks up
and executes everything continuously.

Turning Automate back off only stops FlipperStar's own scanning (no new
orders get queued) - it deliberately does **not** stop GE Star V2's script
or revert its "Stop script when queue is empty" setting, so nothing already
in progress gets interrupted. Stop GE Star V2 manually from its own panel if
you actually want execution to halt.

Automate is equivalent to turning on every setting below by hand - the
individual toggles (`autoScanEnabled`, `exitScanEnabled`) still work on
their own if you want, e.g., buy automation without sell automation.

## Scan modes

- **Manual** (`Scan now` button in the panel) - for a single supervised
  pass, or while you're still trusting the model's picks.
- **Auto-scan** (config, off by default, or via **Automate** above) - scans
  and queues automatically on an interval (`Auto-scan interval (minutes)`).
  This means real GP moves based purely on model output with no human step
  in between - only turn it on once you trust the model's picks from
  watching manual scans for a while.

## Files

- `FlipperStarPlugin.java` - `@PluginDescriptor`, wires config/panel, starts/
  stops auto-scan based on config, and implements **Automate**
  (`applyAutomate()`) - starting GE Star V2's script, disabling its stop-
  when-empty setting, and turning on both scan toggles in one call.
- `FlipperStarEngine.java` - the scan/filter/size/queue logic for both buys
  and exits. Tracks which order ids it originated - `openOrderIds` for buys
  (so `Max open flips` reflects its own exposure, not GE Star V2's whole
  queue, which may also hold manually-added or web-UI-submitted orders) and
  `pendingSellOrderIdsByItemId` for sells (keyed by item id, so a position
  already has a pending SELL isn't re-queued every scan) - both reconciled
  against GE Star V2's real order statuses at the start of every scan.
- `GeStarBridge.java` - reflective bridge to the live GE Star V2 plugin
  instance. See its javadoc for **why reflection is required, not a
  compile-time dependency**: RuneLite gives every sideloaded plugin jar its
  own `ClassLoader`, so FlipperStar and GE Star V2 (two separately-built,
  separately-sideloaded jars) can't share `GeStarOrderQueue`/
  `GeStarPortfolio` Guice singleton instances by compiling against each
  other directly - they'd each load their own incompatible copy of those
  classes. Same pattern `geflipper` (in `vendor/microbot-hub/`) already
  uses to reach the third-party Flipping Copilot plugin.
- `ScoringServiceClient.java` / `Candidate.java` / `CandidatesResponse.java`
  / `SellDecision.java` / `ShouldSellResponse.java` / `OpenPosition.java` -
  OkHttp + Gson client for the scoring service and its DTOs, matching the
  field names/types of `data/service/main.py`'s pydantic models exactly.
- `FlipperStarConfig.java` - the top-level **Automate** toggle, service URL,
  sizing/risk thresholds, auto-scan toggle, exit-scan toggle/endpoint path.
- `FlipperStarPanel.java` - Automate/GE Star V2/auto-scan/open-flips/
  pending-sells/last-exit-scan status, Scan button for manual passes, live
  list of the last scan's buy candidates.

## Cross-plugin contract with GE Star V2

FlipperStar calls these methods on GE Star V2's classes reflectively, all
deliberately primitives/String-only (see `GeStarOrderQueue`'s javadoc for
why) - `GeStarPortfolio.getOpenPositionsJson()` is the one exception, in
that it returns a JSON *array* string rather than a single primitive, since
`CostBasisEntry` itself can't cross the classloader boundary:

- `GeStarOrderQueue.addOrder(GrandExchangeAction, String, int, int) -> long`
- `GeStarOrderQueue.getOrderStatusName(long) -> String`
- `GeStarPortfolio.getHeldQuantity(String) -> int`
- `GeStarPortfolio.getAverageCost(int) -> int`
- `GeStarPortfolio.getOpenPositionsJson() -> String` - JSON array of
  `{itemId, itemName, quantityHeld, averageCost, purchaseTimestampMillis}`,
  parsed on FlipperStar's side into `OpenPosition` (Gson, matching field
  names). `purchaseTimestampMillis` comes from `CostBasisEntry`'s
  quantity-weighted acquisition timestamp (updated in `recordBuy`) - see
  that class's javadoc for how it blends across topped-up buys.
- `Script.isRunning() -> boolean` on GE Star V2's `script` field - the
  shared Microbot base class's method, safe to call reflectively since it's
  declared on a type loaded once by the parent classloader, not a
  GE Star V2-defined one.
- `GeStarV2Plugin.execute()` - starts GE Star V2's script (used by
  **Automate**, see above, to start it without a manual Execute click).

If GE Star V2's internal field names (`queue`, `portfolio`, `script` on
`GeStarV2Plugin`) or these method signatures change, `GeStarBridge` needs
updating to match - there's no compiler to catch this, since the whole
point is that these two plugins don't compile against each other.

Separately, **Automate** also writes directly into GE Star V2's config
(`gestarv2`'s `stopWhenOrdersComplete` key) through the shared
`ConfigManager` - not reflection, since `ConfigManager` is itself a shared
client-jar singleton and setting another plugin's config through it is safe
across the classloader boundary the same way any `ConfigManager` call is.

## Building

```bash
./gradlew :plugins:flipper-star:build
```

## Known limitations / next steps

- No partial exits - a SELL decision sells the full tracked quantity of a
  position, not a partial size.
- `Max open flips` counts orders, not GP exposure - a future version could
  cap by total GP committed instead of/alongside order count.
- The scoring service's live features are themselves an approximation of
  what the model trained on (see `data/service/`'s README section) - the
  predicted margins/hold-return values are a ranking signal, not a
  guarantee.
- The exit model's SELL threshold (`EXIT_SELL_THRESHOLD_PCT` in
  `data/service/main.py`) is server-side only, deliberately not exposed as a
  FlipperStar config item, so the value used in training evaluation and live
  serving can't silently diverge - change it in the service and retrain to
  re-evaluate against the new threshold.
- No visual display of scan/position/decision history yet - status is
  text-only in the panel and service logs.
