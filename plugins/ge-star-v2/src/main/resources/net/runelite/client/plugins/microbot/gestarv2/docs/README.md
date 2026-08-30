# GE Star V2

Grand Exchange trading plugin: buys and sells items from simple
`itemName,quantity,price` order lists, with spend/price guardrails and
real-time fill auto-detection. Built as a standalone state machine using
Microbot Hub's `Rs2GrandExchange` utility, following the same shape as
[Microbot-Hub's `geflipper`](../../../../../../../../../../vendor/microbot-hub/src/main/java/net/runelite/client/plugins/microbot/geflipper)
(kept in `vendor/microbot-hub/` for reference only — see root README for why
that repo is never a build dependency).

## Controlling it

Enabling the plugin adds a **GE Star V2** icon to the client sidebar (reuses
the same icon as the built-in Grand Exchange plugin) with a small panel:

- **Execute** — starts the order-processing script (equivalent to what used
  to run automatically on plugin startup).
- **Stop** — shuts the script down without disabling the plugin, so you can
  edit the order lists in the config and hit Execute again without a full
  plugin restart.
- A live status block (state, pending orders, active offers, GP spent this
  session) refreshes once a second from the running script.

Enabling/disabling the plugin itself only adds/removes the panel and
overlay — it does not start or stop the script; that's what the panel
buttons are for.

## What it does

1. Walks to the Grand Exchange and opens it.
2. Reads the **Buy orders** and **Sell orders** config boxes, one order per
   line: `itemName,quantity,price`.
3. Before submitting each order, runs it through `GeStarGuardrails`:
   - session GP spend cap (buys only)
   - max quantity per single order
   - max % deviation from the live GE guide price (`Rs2GrandExchange.getPrice`)
   - max concurrent offer slots used
4. Submits via `Rs2GrandExchange.buyItem(name, qty, price)` /
   `sellItem(name, qty, price)`.
5. Withdraws coins (for buys) or the sale item (for sells) from the bank if
   the inventory doesn't already have enough, when "Withdraw from bank if
   needed" is enabled.
6. Monitors active offers every tick via `Rs2GrandExchange.getOfferDetails`,
   `getItemsBoughtFromOffer` / `getItemsSoldFromOffer`, and the offer's
   `GrandExchangeOfferState` to detect completed/cancelled fills — this is
   the same live state the client's `GrandExchangeOfferChanged` event fires
   on, which the plugin also subscribes to directly for real-time logging.
   Completed offers are auto-collected (to bank or inventory, per config).
7. Optionally stops itself once every order is filled and collected.

## Files

- `GeStarV2Plugin.java` — `@PluginDescriptor`, wires config/overlay/script,
  adds the sidebar panel/nav button, subscribes to `GrandExchangeOfferChanged`
  for real-time fill detection. Exposes `execute()`/`stop()` for the panel.
- `GeStarV2Panel.java` — the sidebar `JPanel`: Execute/Stop buttons plus a
  live status readout, polling the script once a second.
- `GeStarV2Script.java` — the state machine:
  `GOING_TO_GE -> SUBMITTING_ORDERS -> MONITORING_OFFERS -> DONE`, with a
  `PREPARING_FUNDS_OR_ITEMS` side-state for bank withdrawals.
- `GeStarV2Config.java` — order lists + guardrail + behavior settings.
- `GeStarGuardrails.java` — the safety checks, isolated from click/widget
  logic so the rules are easy to read and adjust.
- `GeStarOrder.java` — parses one `name,quantity,price` config line.
- `GeStarV2Overlay.java` — in-game overlay showing state, pending orders,
  active offers, GP spent this session (same data as the sidebar panel).

## Guardrails (all configurable, `0` = disabled unless noted)

| Setting | Default | Effect |
|---|---|---|
| Max GP to spend (session) | 0 (off) | Hard cap on total coins spent on buys |
| Max quantity per item | 0 (off) | Rejects any single order above this qty |
| Max price deviation from guide price | 25% | Rejects orders priced too far from the wiki guide price |
| Max concurrent offers | 4 | Caps how many of the 8 GE slots are used at once |
| Stop plugin on guardrail breach | off | If on, any rejected order stops the whole plugin instead of just being skipped |

A rejected order is logged and skipped (or stops the plugin, if configured)
— it never reaches `Rs2GrandExchange.buyItem`/`sellItem`.

## Setup

1. Start at or near the Grand Exchange.
2. Have coins (for buys) or the sell items (for sells) in your inventory or
   bank.
3. Fill in the order list config boxes, e.g.:
   ```
   Nature rune,1000,180
   Yew logs,500,300
   ```
4. Adjust guardrails to taste, especially the GP spend cap.
5. Enable the plugin, click the GE Star V2 sidebar icon, then click
   **Execute**.

## Building

```bash
./gradlew :plugins:ge-star-v2:build
```

## Known limitations / next steps

- Order lines are plain text (`name,quantity,price`), not a native repeating
  config group — Microbot's config UI has no built-in list widget, so this
  follows the same convention used elsewhere in the Hub (e.g. loot lists).
- No retry/reprice logic if an offer sits unfilled — it just waits. A future
  version could re-adjust price after a timeout using
  `Rs2GrandExchange.abortOffer` + resubmission.
- Item name resolution for the guide-price guardrail uses
  `Rs2ItemManager.getItemId(String)`; if a name doesn't resolve, that
  specific guardrail is skipped rather than blocking the order (the GE's own
  search is the real source of truth for whether a name is valid).
