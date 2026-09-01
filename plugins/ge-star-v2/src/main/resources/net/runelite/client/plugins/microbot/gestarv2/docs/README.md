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
- **Cancel all offers** — a panic button, separate from Execute/Stop: walks
  to/opens the GE if needed, aborts every currently active offer in all 8
  slots via `Rs2GrandExchange.abortAllOffers()`, and collects whatever comes
  back (unfilled items/GP, and anything already finished) to inventory or
  bank per **Collect completed offers to bank**. Also clears every still-
  `QUEUED` order from the queue immediately (no GE interaction needed for
  those - they were never submitted). A full stop, not a pause: nothing
  cancelled here gets resubmitted afterward. Works even if Execute was never
  clicked - it starts the script's own loop if it isn't already running, and
  once done leaves it idling rather than fully stopping, so it still notices
  and processes any order added after the fact.
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
   - max % deviation from the live guide price (`GeStarWikiPriceClient`, see
     "Hard price clamp" below - the guardrail and the clamp share the same
     trusted price source)
4. Submits via `Rs2GrandExchange.buyItem(name, price, qty)` /
   `sellItem(name, qty, price)`, marking the order `SUBMITTED`. **These two
   methods take price and quantity in a different order from each other**
   (verified against the client jar's bytecode after a live bug where they
   were swapped) - see the comment at the call site in
   `GeStarV2Script.java` before touching this.
5. Withdraws coins (for buys) or the sale item (for sells) from the bank if
   the inventory doesn't already have enough, **only if** "Withdraw from
   bank if needed" is turned on (off by default - see "Portfolio & cost
   basis" below for why inventory-only is the default). With it off, an
   order that can't be covered by inventory alone is marked `SKIPPED`
   rather than triggering a bank trip.
6. Monitors active offers every tick via `Rs2GrandExchange.getOfferDetails`,
   `getItemsBoughtFromOffer` / `getItemsSoldFromOffer`, and the offer's
   `GrandExchangeOfferState` to detect completed/cancelled fills — this is
   the same live state the client's `GrandExchangeOfferChanged` event fires
   on, which the plugin also subscribes to directly for real-time logging.
   Completed offers are auto-collected (to bank or inventory, per config)
   and marked `DONE` **only if the collect actually succeeds**
   (`Rs2GrandExchange.collectOffer`'s return value is checked) - if it
   fails (e.g. the GE offer widget wasn't interactable that tick), the
   order stays `SUBMITTED` and tracked so the next tick retries, instead
   of the order being marked done (and cost-basis recorded) while the
   GP/items are still actually sitting uncollected in the GE slot.
7. With **"Stop script when queue is empty"** off, the script stays running
   once the queue drains instead of shutting down - it idles on the same
   tick loop and re-checks the queue every tick, so an order queued later
   (e.g. by [FlipperStar](../../../../../../../../../../plugins/flipper-star)'s
   autonomous scan, long after this script last had anything to do) gets
   picked up and submitted without Execute needing to be clicked again. This
   is what makes unattended, continuously-running buy/sell automation
   possible - with the default (on), the script fully stops once its queue
   empties and needs a manual Execute to notice anything new.
8. **Detects and adopts GE offers it didn't queue itself.** Every time the
   script (re)opens the GE - right after Execute, and again whenever it
   resumes from idle to submit a newly-queued order - it scans all 8 GE
   slots (`Rs2GrandExchange.getActiveOfferSlots`) and, for any live offer
   that doesn't match a known order, builds a synthetic `GeStarOrder` from
   the offer's own details (item, quantity, price, buy/sell) and adds it to
   the queue as `SUBMITTED`. That offer - whether placed manually, by
   another tool, or left over from a previous session - is then monitored,
   collected, and cost-based exactly like any order this script submitted
   itself. Without this, an untracked offer sitting in a slot would be
   invisible to `maxActiveOffers`' slot counting and would never get
   collected or recorded into the portfolio by this script.

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

When web sync is running, every detected BUY fill is also mirrored
(fire-and-forget, best-effort) to Firestore's `buyLimits/{agentId}/events`
collection, alongside the local `BuyLimitLedger` write that actually
enforces the buy-limit guardrail (see "Guardrails" above) - this is purely
for cross-machine visibility/audit of an agent's rolling-window buy
history; enforcement itself never depends on Firestore being reachable
or web sync being enabled at all.

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
  `GeStarOrder`'s status/fill in place as it progresses. A `CANCELLING_ALL`
  state pre-empts all of the above the tick after `requestCancelAll()` is
  called (the panel's **Cancel all offers** button) - aborts every active
  offer and collects everything back, marking any order that was
  `SUBMITTED` as `FAILED` rather than leaving it looking still-live.
- `GeStarWikiPriceClient.java` — direct OSRS Wiki `/latest` client used only
  by the hard price clamp (see "Hard price clamp" below for why this exists
  instead of `Rs2GrandExchange.getRealTimePrices()`).
- `GeStarV2Config.java` — guardrail + behavior settings (no order data).
- `GeStarGuardrails.java` — the safety checks, isolated from click/widget
  logic so the rules are easy to read and adjust.
- `GeStarOrder.java` — one order: action/item/quantity/price plus a mutable
  `Status` (`QUEUED`/`SUBMITTED`/`DONE`/`SKIPPED`/`FAILED`) and fill count.
- `GeStarV2Overlay.java` — in-game overlay showing state, queued-order
  count, active offers, GP spent this session.
- `portfolio/GeStarPortfolio.java` — tracks live holdings (inventory-only,
  read fresh on every call - see "Portfolio & cost basis" below for why
  not bank + inventory) and a persisted weighted-average cost-basis
  ledger, updated from every completed fill in `GeStarV2Script`. Shared
  across GE Star V2's guardrails and FlipperStar - see
  "Portfolio & cost basis" below.
- `portfolio/CostBasisEntry.java` — one item's running average cost,
  quantity held, and realized profit/loss.
- `portfolio/BuyLimitLedger.java` — per-item, timestamped log of buy fills,
  persisted the same way as the cost-basis ledger, used to enforce the GE's
  rolling 4h buy-limit window across sessions (see "Guardrails" below).
  Also owns this installation's locally-generated agent id.

## Portfolio & cost basis

`GeStarPortfolio` (injected as a Guice singleton, so it's the same instance
everywhere) answers two different kinds of question:

- **Holdings** (`getHeldQuantity`, `getAllHoldings`) — read live from
  `Rs2Inventory.all()` on every call, **deliberately inventory-only, not
  bank + inventory**. `Rs2Bank.bankItems()` (tried initially) reads a
  client-side cache that's only populated reactively when the bank is
  actually open, not a true live read - if the bank hasn't been opened
  this session, or was opened once and its contents changed since, that
  cache silently under/over-reports what's actually held. Inventory has
  no such gap. Kept simple on purpose: `withdrawFromBank` (Orders section,
  off by default) matches this - with it off, the script never tops up
  from the bank either, so what it can see and what it can act on stay
  the same thing. Turn `withdrawFromBank` on if you want it to reach into
  the bank when inventory alone falls short of a queued order; holdings
  reporting stays inventory-only either way.
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
| Max price deviation from live price | 25% | Rejects orders priced too far from the OSRS Wiki's current live insta-buy/insta-sell price |
| Max deviation from base item value | 0 (off) | BUY-only. Would reject paying more than this % above the item's static base value (`ItemComposition.getPrice()` - Jagex's internal alch/store-price number, not shown anywhere in the normal GE UI and not the same as the live insta-buy/insta-sell price). Off by default: added after a live report that turned out to be a false alarm (onion seed bought at 10gp looked wrong next to its 3gp base value, but had genuine 647-unit hourly trading volume at that price - a normal, liquid, correctly-priced flip, not a bug). Checked against real candidates afterward: no threshold on base-value deviation separates real problems from normal market behavior for cheap items - common herbs/ammo/seeds routinely trade 10-100x+ their base value with nothing actually wrong. Left available, not recommended as a general guardrail |
| Stop script on guardrail breach | off | If on, any rejected order stops the script instead of just being skipped |

Sell orders are also checked against `GeStarPortfolio.getHeldQuantity()`
(inventory only, see "Portfolio & cost basis" above) - a sell order for
more than you actually hold is rejected up front, rather than sitting
forever failing (with `withdrawFromBank` off, its default) or attempting
a bank withdrawal (with it on). This check always runs regardless of the
guardrails master switch, since it's catching an order that can never
succeed rather than a risk/safety tradeoff.

Buy orders are checked the same unconditional way against
`BuyLimitLedger` - a per-item, timestamped log of actual buy fills,
persisted via `ConfigManager` (same pattern as the cost-basis ledger
above) so it survives restarts and correctly enforces the GE's real
rolling 4-hour buy-limit window across sessions, not just within a
single order or a single script run. The item's limit itself comes from
`Rs2GrandExchange.getItemMappingData()` (the OSRS Wiki's item-mapping
data, cached client-side after first lookup) - if the item isn't
resolvable there, nothing is enforced. A BUY that would push this
window's total past the limit is rejected before submission, the same
way an over-sized SELL is. FlipperStar's own sizing
(`FlipperStarEngine.sizeOrder`) also subtracts what's already been
bought this window before queuing, so it doesn't even queue a doomed
order in the first place - this guardrail is the backstop that always
applies regardless of caller.

Each installation gets a stable, locally-generated agent id
(`BuyLimitLedger.getAgentId()`, a UUID persisted the same way) used only
to key its buy history if mirrored to Firestore's `buyLimits` collection
when web sync is enabled (see "Web sync" below) - purely for
cross-machine visibility/audit, never required for the guardrail itself,
which only ever reads the local ledger.

A BUY is also rejected if it isn't the *earliest* still-active
(QUEUED/SUBMITTED) BUY for that item name already in the queue - i.e. a
duplicate riding along behind one that hasn't resolved yet. This exists
because `getHeldQuantity` and `BuyLimitLedger` both only learn about a
purchase once it *fills* - neither one sees a still-unfilled order, so
without this check the same top-ranked candidate could get queued again
every scan cycle (live-reported as 3 simultaneous flax orders). The
primary fix for this lives in FlipperStar itself
(`pendingBuyOrderIdsByItemId`, checked before queuing at all - see
[FlipperStar's docs](../../../flipper-star/docs/README.md)); this
guardrail is the second line of defense that catches a duplicate from
any other source (manual panel, web UI), since it's checked here
regardless of where the order came from.

"Max concurrent offers" (default 8, how many of the 8 GE slots to use at
once) lives in the **Behavior** section instead — it's a throttle, not a
safety check, and stays in effect even with guardrails disabled. Lower it
if you'd rather the script keep some slots free (e.g. for placing manual
offers alongside it).

The price-deviation guardrail compares against `GeStarWikiPriceClient` (a
direct call to the OSRS Wiki's real-time price API - see "Hard price
clamp" below), matched to the correct side of the book — a buy order is
checked against the recent insta-buy price, a sell order against the
recent insta-sell price. It deliberately avoids
`Rs2GrandExchange.getRealTimePrices()`/`.getPrice()`, which hit
ge-tracker.com's derived "overall" price first and can drift badly from
the real market on low-volume items (observed: it reported 103gp for an
item that was actually trading around 27gp).

A rejected order is marked `SKIPPED` with the reason shown in its row (or
stops the script, if configured) — it never reaches
`Rs2GrandExchange.buyItem`/`sellItem`.

## Hard price clamp (not a guardrail — always on)

Separate from the price-deviation guardrail above (which *rejects* an
order that's too far from guide price, and can be loosened or disabled),
`GeStarV2Script.clampToLivePrice()` runs on every single order right
before submission and *adjusts* the price actually offered: a BUY order is
never offered above the live insta-buy price, and a SELL order is never
offered below the live insta-sell price. This exists because the price an
order was queued at can go stale by the time it's actually submitted (queue
backlog, script paused and resumed later, market movement) — added after a
live report of an order being submitted noticeably above the item's
in-game guide price. This clamp cannot be turned off from config; if you
genuinely want to pay above the live price for faster fill, set the
order's price directly on the resulting narrower range (the clamp only
ever moves price toward the live rate, never further away from it in the
caller's favor).

**Price source: `GeStarWikiPriceClient`, a direct call to the OSRS Wiki's
`/latest` API — deliberately not `Rs2GrandExchange.getRealTimePrices()`.**
That Microbot Hub utility was the original source here, but tracing it
against its own bytecode showed it calls `getPrice`/`getSellPrice` first,
which hit `ge-tracker.com`'s API (a third-party price aggregator) - not
the wiki - and only fall back to the wiki if that call fails outright. A
live-reported bug (an order clamped down to ~10gp for an item genuinely
worth ~40gp) traced directly to ge-tracker's price for that item being
stale/wrong, with no sanity check catching it. `GeStarWikiPriceClient`
bypasses that path entirely, hitting the wiki directly - the same source
the buy-side model, scoring service, and data pipeline already use and
trust - with a 30s per-item cache (this clamp runs on every submission
tick, no reason to refetch more often than the wiki's own data changes).

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

**For unattended operation driven by [FlipperStar](../../../../../../../../../../plugins/flipper-star)**
(auto-scanning and queuing buys/sells on its own, with no manual Scan/Execute
after the first click): turn off **"Stop script when queue is empty"** above,
click **Execute** once, and leave it running - see item 7 in "What it does"
above for why this keeps the script alive to notice orders FlipperStar
queues later, and FlipperStar's own docs for its auto-scan/exit-scan config.

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
