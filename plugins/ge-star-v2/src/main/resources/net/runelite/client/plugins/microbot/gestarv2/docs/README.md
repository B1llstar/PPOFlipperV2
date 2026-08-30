# GE Star V2

Grand Exchange trading plugin: buys and sells items from an order queue you
build and watch fill live in the sidebar panel, with spend/price guardrails
and real-time fill auto-detection. Built as a standalone state machine using
Microbot Hub's `Rs2GrandExchange` utility, following the same shape as
[Microbot-Hub's `geflipper`](../../../../../../../../../../vendor/microbot-hub/src/main/java/net/runelite/client/plugins/microbot/geflipper)
(kept in `vendor/microbot-hub/` for reference only — see root README for why
that repo is never a build dependency).

## Controlling it

Enabling the plugin adds a **GE Star V2** icon to the client sidebar (reuses
the same icon as the built-in Grand Exchange plugin). Everything lives in
that one panel — there's no separate order-list config screen:

- **Add order form** — item name, quantity, price, and a Buy/Sell dropdown,
  plus an "Add to queue" button.
- **Order queue list** — every order ever added, each row showing its live
  status: `Queued`, `Active - N% filled`, `Done`, or `Skipped`/`Failed` with
  a reason. Rows update immediately as the script submits and fills orders.
  Queued, done, skipped, and failed orders can be removed with the row's `x`
  button; active (submitted) orders can't be removed out from under the
  script.
- **Execute** / **Stop** — start/stop the order-processing script. Stopping
  doesn't clear the queue, so you can pause, add more orders, and resume.
- A live status block (Running/Stopped, state, GP spent this session)
  refreshes once a second.

Enabling/disabling the plugin itself only adds/removes the panel and
overlay — it does not start or stop the script, and does not touch the
queue; that's what the panel buttons are for.

## What it does

1. Walks to the Grand Exchange and opens it.
2. Pulls the next `QUEUED` order from the shared order queue.
3. Before submitting each order, runs it through `GeStarGuardrails` (unless
   guardrails are disabled entirely):
   - session GP spend cap (buys only)
   - max quantity per single order
   - max % deviation from the live guide price (`Rs2GrandExchange.getRealTimePrices`)
4. Submits via `Rs2GrandExchange.buyItem(name, qty, price)` /
   `sellItem(name, qty, price)`, marking the order `SUBMITTED`.
5. Withdraws coins (for buys) or the sale item (for sells) from the bank if
   the inventory doesn't already have enough, when "Withdraw from bank if
   needed" is enabled.
6. Monitors active offers every tick via `Rs2GrandExchange.getOfferDetails`,
   `getItemsBoughtFromOffer` / `getItemsSoldFromOffer`, and the offer's
   `GrandExchangeOfferState` to detect completed/cancelled fills — this is
   the same live state the client's `GrandExchangeOfferChanged` event fires
   on, which the plugin also subscribes to directly for real-time logging.
   Completed offers are auto-collected (to bank or inventory, per config)
   and marked `DONE`.
7. Optionally stops itself once the queue has no `QUEUED` or `SUBMITTED`
   orders left.

## Web sync (PPOFlipperOpus)

Orders can also come from the [PPOFlipperOpus web UI](https://ppoflipperopus.web.app)
(`firebase/` at the repo root — a Cloud Functions + Firestore backend and a
static page) instead of the in-game panel:

1. Web UI calls a `createOrder` Cloud Function, which validates the request
   and writes a `QUEUED` document to the `orders` Firestore collection.
2. `GeStarFirestoreSync` (enabled via the **Web sync** config section) polls
   that collection every 5 seconds on its own thread, pulls new `QUEUED`
   documents into the same `GeStarOrderQueue` the sidebar panel uses, and
   pushes status/fill changes back to Firestore as the script processes
   them.
3. The web UI has a live Firestore listener, so order status updates
   (`QUEUED` → `SUBMITTED` → `DONE`/`SKIPPED`) appear there in real time too.

Orders added locally through the panel and orders pulled from the web share
one queue and go through the same guardrails — the web UI is just another
way to add to the queue, not a separate execution path.

**Auth model:** the plugin authenticates to Firestore using the Firebase
service-account JSON (`ppoflipperopus-firebase-adminsdk-*.json` at the repo
root, gitignored — see `firestoreServiceAccountPath` in config) via plain
HTTPS (`GoogleServiceAccountAuth` signs a JWT and exchanges it for an OAuth
token; `GeStarFirestoreClient` calls the Firestore REST API directly) rather
than the Admin SDK, to avoid pulling gRPC/Guava/Protobuf into a jar that
gets sideloaded into the same JVM as the game client. **Never commit or
share that JSON file** — it grants full admin access to the whole Firebase
project, not just this collection.

## Files

- `GeStarV2Plugin.java` — `@PluginDescriptor`, wires config/overlay/script/
  queue/Firestore sync, adds the sidebar panel/nav button, subscribes to
  `GrandExchangeOfferChanged` for real-time fill detection. Exposes
  `execute()`/`stop()` for the panel.
- `GeStarFirestoreSync.java` — polls Firestore for web-submitted orders and
  mirrors status/fill changes back; runs on its own thread, independent of
  Execute/Stop.
- `GeStarFirestoreClient.java` — Firestore REST API calls (list QUEUED
  orders, patch status) matching the schema in
  `firebase/functions/src/orders.ts`.
- `GoogleServiceAccountAuth.java` — service-account JWT signing and OAuth
  token exchange/caching, no SDK dependency.
- `GeStarV2Panel.java` — the sidebar `JPanel`: add-order form, live order
  queue list, Execute/Stop buttons, status readout.
- `GeStarOrderQueue.java` — the shared, thread-safe order list the panel
  (EDT) and script (its own scheduled-executor thread) both read/write, with
  a change-listener the panel uses to repaint on every status update.
- `GeStarV2Script.java` — the state machine:
  `GOING_TO_GE -> SUBMITTING_ORDERS -> MONITORING_OFFERS -> DONE`, with a
  `PREPARING_FUNDS_OR_ITEMS` side-state for bank withdrawals. Mutates each
  `GeStarOrder`'s status/fill in place as it progresses.
- `GeStarV2Config.java` — guardrail + behavior settings (no order data).
- `GeStarGuardrails.java` — the safety checks, isolated from click/widget
  logic so the rules are easy to read and adjust.
- `GeStarOrder.java` — one order: action/item/quantity/price plus a mutable
  `Status` (`QUEUED`/`SUBMITTED`/`DONE`/`SKIPPED`/`FAILED`) and fill count.
- `GeStarV2Overlay.java` — in-game overlay showing state, queued-order
  count, active offers, GP spent this session.
- `portfolio/GeStarPortfolio.java` — tracks live holdings (bank + inventory,
  read fresh on every call) and a persisted weighted-average cost-basis
  ledger, updated from every completed fill in `GeStarV2Script`. Shared
  across GE Star V2's guardrails and the planned GE Flipper plugin - see
  "Portfolio & cost basis" below.
- `portfolio/CostBasisEntry.java` — one item's running average cost,
  quantity held, and realized profit/loss.

## Portfolio & cost basis

`GeStarPortfolio` (injected as a Guice singleton, so it's the same instance
everywhere) answers two different kinds of question:

- **Holdings** (`getHeldQuantity`, `getAllHoldings`) — read live from
  `Rs2Bank.bankItems()` + `Rs2Inventory.all()` on every call. Always
  current, nothing to keep in sync.
- **Cost basis** (`getAverageCost`, `getRealizedProfit`,
  `getTotalRealizedProfit`, `getOpenPositions`) — the client has no concept
  of "what did I pay for this," so `GeStarV2Script` records every completed
  fill into a weighted-average-cost ledger
  (`recordCostBasis` in `GeStarV2Script`, using
  `GrandExchangeOfferDetails.getSpent()` for the *actual* GP that changed
  hands, not the order's requested price - a partial fill or a different
  clearing price cost-bases at what really happened). Persisted through
  `ConfigManager` under the `gestarv2` group, so it survives plugin/client
  restarts.

The sidebar panel shows all-time realized P&L; the full open-positions
breakdown is available via `getOpenPositions()` for anything that wants to
render more detail (a future flipper panel, most likely).

## Guardrails (all configurable, `0` = disabled unless noted)

| Setting | Default | Effect |
|---|---|---|
| Guardrails enabled | on | Master switch. Off submits every order as-is, skipping every check below entirely |
| Max GP to spend (session) | 0 (off) | Hard cap on total coins spent on buys |
| Max quantity per item | 0 (off) | Rejects any single order above this qty |
| Max price deviation from guide price | 25% | Rejects orders priced too far from the live guide price |
| Stop script on guardrail breach | off | If on, any rejected order stops the script instead of just being skipped |

Sell orders are also checked against `GeStarPortfolio.getHeldQuantity()`
(bank + inventory combined) - a sell order for more than you actually own
anywhere is rejected up front, rather than sitting forever failing the
bank-withdrawal step. This check always runs regardless of the guardrails
master switch, since it's catching an order that can never succeed rather
than a risk/safety tradeoff.

"Max concurrent offers" (default 4, how many of the 8 GE slots to use at
once) lives in the **Behavior** section instead — it's a throttle, not a
safety check, and stays in effect even with guardrails disabled.

The price-deviation guardrail compares against
`Rs2GrandExchange.getRealTimePrices()` (the OSRS Wiki's real-time price
API), matched to the correct side of the book — a buy order is checked
against the recent buy price, a sell order against the recent sell price.
It deliberately avoids `Rs2GrandExchange.getPrice()`, which hits
ge-tracker.com's derived "overall" price and can drift badly from the real
market on low-volume items (observed: it reported 103gp for an item that
was actually trading around 27gp).

A rejected order is marked `SKIPPED` with the reason shown in its row (or
stops the script, if configured) — it never reaches
`Rs2GrandExchange.buyItem`/`sellItem`.

## Setup

1. Start at or near the Grand Exchange.
2. Have coins (for buys) or the sell items (for sells) in your inventory or
   bank.
3. Enable the plugin and open the GE Star V2 sidebar icon.
4. Add orders through the form, e.g. Buy 1000x Nature rune @ 180 gp.
5. Adjust guardrails to taste in the config screen, especially the GP spend
   cap.
6. Click **Execute** and watch the queue fill live.
7. Optionally, enable **Web sync** in config and point it at the service
   account JSON path to also accept orders from the PPOFlipperOpus web UI.

## Building

```bash
./gradlew :plugins:ge-star-v2:build
```

## Known limitations / next steps

- The order queue lives in memory only — it does not persist across a
  plugin restart or client restart. A future version could serialize it
  through `ConfigManager` if that's needed.
- No retry/reprice logic if an offer sits unfilled — it just waits. A future
  version could re-adjust price after a timeout using
  `Rs2GrandExchange.abortOffer` + resubmission.
- Item name resolution for the guide-price guardrail uses
  `Rs2ItemManager.getItemId(String)`; if a name doesn't resolve, that
  specific guardrail is skipped rather than blocking the order (the GE's own
  search is the real source of truth for whether a name is valid).
- The web UI's `cancelOrder` function only supports cancelling `QUEUED`
  orders (no live GE offer exists yet). Cancelling a `SUBMITTED` order from
  the web isn't wired up - it would need `GeStarFirestoreSync` to poll for a
  cancellation flag and call `Rs2GrandExchange.abortOffer` locally.
- `firebase/functions` requires the project to be on Firebase's Blaze
  (pay-as-you-go) plan to deploy - Firestore rules/indexes and Hosting work
  on the free Spark plan, but Cloud Functions do not.
