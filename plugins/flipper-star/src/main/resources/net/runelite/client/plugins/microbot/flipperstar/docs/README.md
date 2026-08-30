# FlipperStar

Grand Exchange flip *decision-maker*: scans the currently-liquid item universe
via a local scoring service (a trained margin-prediction model, see `data/`
at the repo root), filters candidates against your risk settings, and queues
buy orders into [GE Star V2](../../../gestarv2/docs/README.md)'s order
queue. FlipperStar never executes anything in-game itself - GE Star V2 owns
that (clicking, guardrails, fill detection); FlipperStar only decides what
goes into the queue.

## Setup

1. **Train the model and run the scoring service** (one-time, then keep the
   service running alongside the client):
   ```bash
   cd data
   source venv/bin/activate   # see data/README.md for full setup
   cd service
   uvicorn main:app --host 127.0.0.1 --port 8420
   ```
2. Enable **GE Star V2** (FlipperStar queues into it - it must be running,
   though its own script doesn't need to be Executing).
3. Enable **FlipperStar**, open its sidebar icon, click **Scan now**.

## How it works

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

Selling isn't automated by FlipperStar (queuing sells for items it doesn't
yet hold makes no sense, and once a buy fills there's no live "is this a
good time to sell" re-scoring loop yet) - use GE Star V2's panel to sell
manually once positions are ready, or watch `GeStarPortfolio`'s realized
P&L in GE Star V2's panel.

## Scan modes

- **Manual** (`Scan now` button in the panel) - the recommended default.
  Nothing happens without you clicking it.
- **Auto-scan** (config, off by default) - scans and queues automatically
  on an interval (`Auto-scan interval (minutes)`). This means real GP moves
  based purely on model output with no human step in between - only turn it
  on once you trust the model's picks from watching manual scans for a
  while.

## Files

- `FlipperStarPlugin.java` - `@PluginDescriptor`, wires config/panel, starts/
  stops auto-scan based on config.
- `FlipperStarEngine.java` - the scan/filter/size/queue logic. Tracks which
  order ids it originated (`openOrderIds`) so `Max open flips` reflects its
  own exposure, not GE Star V2's whole queue (which may also hold manually-
  added or web-UI-submitted orders) - reconciled against GE Star V2's real
  order statuses at the start of every scan.
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
  - OkHttp + Gson client for the scoring service, matching the field names/
  types of `data/service/main.py`'s pydantic response models exactly.
- `FlipperStarConfig.java` - service URL, sizing/risk thresholds, automation
  toggle.
- `FlipperStarPanel.java` - Scan button, GE Star V2/auto-scan/open-flips
  status, live list of the last scan's candidates.

## Cross-plugin contract with GE Star V2

FlipperStar calls exactly three methods on GE Star V2's classes
reflectively, all deliberately primitives-only (see `GeStarOrderQueue`'s
javadoc for why):

- `GeStarOrderQueue.addOrder(GrandExchangeAction, String, int, int) -> long`
- `GeStarOrderQueue.getOrderStatusName(long) -> String`
- `GeStarPortfolio.getHeldQuantity(String) -> int`

If GE Star V2's internal field names (`queue`, `portfolio` on
`GeStarV2Plugin`) or these method signatures change, `GeStarBridge` needs
updating to match - there's no compiler to catch this, since the whole
point is that these two plugins don't compile against each other.

## Building

```bash
./gradlew :plugins:flipper-star:build
```

## Known limitations / next steps

- No sell-side automation yet (see "How it works" above).
- `Max open flips` counts orders, not GP exposure - a future version could
  cap by total GP committed instead of/alongside order count.
- The scoring service's live features are themselves an approximation of
  what the model trained on (see `data/service/`'s README section) - the
  predicted margins are a ranking signal, not a guarantee.
