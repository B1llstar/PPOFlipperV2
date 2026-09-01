# PPOFlipperStar — Proposal

A self-contained Microbot plugin, sitting alongside `ge-star-v2` and `flipper-star`
under `plugins/`, that owns every mechanical aspect of GE flipping (buy, sell,
collect, inventory, bank, gold, portfolio) *and* is driven by a PPO reinforcement-
learning policy trained in Python and consulted every decision tick over Firestore
(§3.6, §4) — not a local server, since one inference process is meant to eventually
serve multiple RuneLite client instances across different machines, not just the
one it happens to share a computer with. No dependency on `ge-star-v2` or
`flipper-star` — this is a new plugin built from scratch, using the same underlying
Microbot primitives those two already use, but with its own execution/state code
and a genuinely different brain (PPO, not gradient-boosted trees).

Package: `net.runelite.client.plugins.microbot.ppoflipperstar`
Gradle module: `plugins/ppo-flipper-star`

---

## 0. What already exists vs. what's new

The repo has a prior project (`ge-star-v2` + `flipper-star`, and a `data/` pipeline
labeled `ppoflipperopus`) that solved the *same mechanical problem* with a
*different brain*: `flipper-star` scores candidates with two LightGBM models
(`margin_model.txt`, `exit_model.txt`) trained on hand-built features, and queues
orders into `ge-star-v2` via reflection across the plugin classloader boundary
(`GeStarBridge.java`, `plugins/flipper-star/.../GeStarBridge.java:21`) because
RuneLite gives every sideloaded jar its own `ClassLoader`.

**Reused, not rebuilt:**
- `data/raw/5m/*.parquet` — 6 months of 5-minute OSRS Wiki price/volume history,
  per item. This is real, valuable, and slow to reacquire. It becomes the backing
  data for the RL training environment (§3).
- `data/pipeline/build_features.py`'s feature definitions (spread, volatility,
  volume, momentum at 1h/6h/24h windows) — reused as a *starting point* for the
  PPO observation space, not as inputs to a supervised label.
- The general Microbot execution primitives (`Rs2GrandExchange`, `Rs2Bank`,
  `Rs2Inventory`) and plugin conventions (`@PluginDescriptor`, config groups,
  panel/overlay wiring, gradle module shape) — these are just how any Microbot
  plugin is built, not specific to the old project.

**Rebuilt from scratch, deliberately:**
- The decision-making model itself: PPO (actor-critic, on-policy RL) replacing
  LightGBM regression. Different problem framing entirely — this model learns a
  *policy* (buy/sell/hold given state) end-to-end from reward, not "predict this
  scalar, then apply a hand-written threshold."
- All execution/state code: order queue, portfolio/cost-basis ledger, buy-limit
  ledger, guardrails, script state machine. Same *mechanics* the old `ge-star-v2`
  had to solve (GE only exposes 8 offer slots, buy limits are a rolling 4h window,
  a partial fill needs correct cost-basis math), same Microbot APIs to solve them
  with, but implemented directly inside PPOFlipperStar so it has zero runtime
  dependency on any other plugin being installed or running.

**Addendum (added after milestone 1+2 shipped): Firestore persistence.**
Unlike the original "no Firestore" stance above, PPOFlipperStar *does* now use
Firestore — but as a durable cross-session/cross-account store the plugin owns
itself, not the old web-UI-sync design. It reuses the existing `ppoflipperopus`
Firebase project and service-account credential (still gitignored, never
committed) purely for infrastructure convenience; it does not read or write
`ge-star-v2`'s `orders`/`buyLimits` collections, and shares nothing but the
project itself. See §4 for the schema and §3.6 (revised) for how this same
mechanism doubles as the model↔plugin communication channel.

---

## 1. High-level architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  RuneLite client (Java) — plugins/ppo-flipper-star/                  │
│                                                                       │
│  PPOFlipperStarPlugin                                                │
│   ├─ InventoryContextMenuManager   (right-click Buy More/Sell/Watch)│
│   ├─ PPOFlipperStarPanel           (sidebar UI, manual controls)     │
│   ├─ PPOFlipperStarOverlay         (in-game HUD: gold, positions)    │
│   ├─ PPOFlipperStarConfig          (@ConfigGroup — all settings)     │
│   │                                                                   │
│   ├─ PortfolioManager   ── holdings, cost basis, realized P&L        │
│   ├─ GoldManager        ── coins in inv+bank, session net worth      │
│   ├─ InventoryManager   ── Rs2Inventory wrapper, slot/state cache    │
│   ├─ BankManager        ── Rs2Bank wrapper, sync inv<->bank          │
│   ├─ BuyLimitLedger     ── rolling 4h GE-limit tracking (persisted)  │
│   ├─ OrderQueue         ── pending/submitted/done orders             │
│   ├─ Guardrails         ── hard safety caps (independent of the RL)  │
│   ├─ MarketStateProvider ── live prices, spreads, volumes (Wiki API) │
│   │                                                                   │
│   └─ PPOFlipperStarScript (extends Microbot Script)                  │
│        state machine: IDLE → GOING_TO_GE → OBSERVE → DECIDE →        │
│        SUBMIT → MONITOR → COLLECT → (loop)                           │
│        "DECIDE" = builds a state vector from all Managers above,     │
│        writes it to Firestore, gets back one action per tracked      │
│        item, executes via the other Managers (see §3.6)              │
│                                                                       │
└───────────────────────────┬───────────────────────────────────────────┘
                             │ Firestore: accounts/{accountHash}/decision/
                             │ request (state) ↔ response (actions) - §3.6
┌───────────────────────────▼───────────────────────────────────────────┐
│  Python inference worker (data/ppo/inference_worker.py)               │
│   - loads a trained checkpoint (.pth) at startup                     │
│   - Firestore snapshot listener per watched account, no polling      │
│   - one process can serve many accounts/machines at once - this is   │
│     why Firestore replaced the original same-machine-only local HTTP │
│     design (see §3.6's superseded-design note)                       │
│   - hot-reloadable: swap checkpoint without restarting listeners     │
└───────────────────────────┬───────────────────────────────────────────┘
                             │ (offline, not at inference time)
┌───────────────────────────▼───────────────────────────────────────────┐
│  Python training pipeline (data/ppo/)                                 │
│   - GEMarketEnv: Gymnasium-style env replaying data/raw/5m/ history   │
│   - PPO agent (actor-critic), Stable-Baselines3 or custom torch loop  │
│   - checkpoints/ppo_flipper_<timestamp>_<step>.pth every N steps      │
│   - train on rented GPU (one run), evaluate on held-out months,       │
│     export best checkpoint to data/models/ppo/current.pth            │
└─────────────────────────────────────────────────────────────────────┘
```

The RuneLite client (with the plugin) and the Python inference worker are
separate processes that need not share a machine — Firestore is the
transport between them (see §3.6), which is what makes a future
one-server-many-clients deployment possible without a redesign. The plugin
never imports PyTorch or does any tensor math — it writes state, waits for
Firestore to hand back an action, executes it. This keeps
the Java side simple and lets the model evolve (architecture changes, retraining)
without ever touching plugin code.

---

## 2. The plugin (Java side) — manual-first, then autonomous

Built in two layers on purpose: **layer 1 makes every action possible manually**
(so it's independently useful and testable with a human in the loop), **layer 2**
wires the same action-primitives up to the PPO server for autonomous operation.
The RL layer never gets a capability the manual layer doesn't already expose —
this is also how you'll validate the model isn't doing anything you can't watch
happen through the manual controls first.

### 2.1 Right-click inventory integration

Uses RuneLite's `MenuEntryAdded` event, the same mechanism `QoLPlugin` in
`vendor/microbot-hub` uses for its inventory context menus
(`vendor/microbot-hub/.../QoLPlugin.java:572`):

```java
@Subscribe
public void onMenuEntryAdded(MenuEntryAdded event) {
    // NOTE: the actual shipped check is getItemId() != -1 plus a matching Rs2Inventory slot
    // lookup, not a WidgetIndices.*.INVENTORY_CONTAINER comparison as originally sketched here -
    // that constant differs per viewport layout (Fixed/Resizable Classic vs. Resizable Modern),
    // so gating on one specific layout's value silently produced no menu entries at all under
    // the others. Found and fixed during live testing - see PPOFlipperStarPlugin.java's
    // onMenuEntryAdded for the real, layout-agnostic implementation.
    if (event.getMenuEntry().getItemId() == -1) return;
    Rs2ItemModel item = Rs2Inventory.getItemInSlot(event.getMenuEntry().getParam0());
    if (item == null) return;

    addMenuEntry(event, "Buy more", item.getName(), e -> panel.openBuyDialog(item));
    addMenuEntry(event, "Sell", item.getName(), e -> panel.openSellDialog(item, portfolioManager.getHeldQuantity(item.getId())));
    addMenuEntry(event, "Watch (let PPO manage)", item.getName(), e -> watchlistManager.add(item.getId()));
}
```

- **Buy more** / **Sell** open a small dialog (quantity + price, price
  pre-filled from `MarketStateProvider`'s live insta-buy/insta-sell) and push
  directly onto `OrderQueue` — same path autonomous mode uses.
- **Watch** adds the item to a user-curated watchlist the PPO agent is allowed
  to act on autonomously; items not on the watchlist are never touched by the
  model, only by explicit manual action. This is the main safety valve — the
  user decides the agent's *universe*, the agent decides *timing/price* within it.
- **Custom item entry**: the sidebar panel also has a plain text/autocomplete
  item search (backed by `Rs2ItemManager`) for adding anything not currently in
  inventory — e.g. queuing a buy for an item you don't hold yet.

### 2.2 Core managers

All singletons via Guice `@Inject`, same DI pattern every plugin in this repo uses.

| Manager | Responsibility | Backing API |
|---|---|---|
| `InventoryManager` | Live inventory snapshot, slot lookups, change events | `Rs2Inventory` |
| `BankManager` | Open/close bank, deposit/withdraw, bank snapshot | `Rs2Bank` |
| `PortfolioManager` | Holdings (inventory **+** bank, unlike the old inventory-only design — see §2.3), weighted cost basis, realized/unrealized P&L per item | Own ledger, persisted via `ConfigManager` (JSON, hand-rolled Gson — see the pitfall the old code hit, avoided the same way: `ConfigManager`'s generic object serialization only special-cases a few types and silently stringifies a `Map` into non-JSON) |
| `GoldManager` | Coins in inventory + bank, session net-worth delta (start-of-session snapshot vs. now, across gold + at-cost-basis holdings value) | `Rs2Inventory`/`Rs2Bank` coin counts |
| `BuyLimitLedger` | Rolling 4h GE buy-limit window per item, persisted across restarts | Own ledger + `Rs2GrandExchange.getItemMappingData` for the limit itself |
| `OrderQueue` | Pending/submitted/done orders, one source of truth the panel and script both read | In-memory + `ConfigManager` persistence for crash recovery |
| `Guardrails` | Hard caps independent of the RL policy: max GP/session, max qty/item, max price deviation from live Wiki price, "never sell more than held", "never exceed buy limit" | Pure logic, same structure as `GeStarGuardrails` — checked on *every* order regardless of whether it came from the model or a human click |
| `WikiPriceClient` + `WikiHistoryBuffer` | Live insta-buy/insta-sell per item (`WikiPriceClient`, not a third-party aggregator — see §2.3) plus a real rolling 24h price/volume history per item (`WikiHistoryBuffer`, polling the wiki's bulk `/5m` endpoint), used to compute genuine volatility/mean-price/volume/momentum features for the DECIDE phase (§3.6) rather than approximating them from a single snapshot. Originally sketched as one `MarketStateProvider` class; built as two once the rolling-history requirement became concrete. | Wiki real-time-prices API (`/latest` and `/5m`) |

### 2.3 Two correctness lessons carried forward from the old code

These aren't reused code, but reused *hard-won knowledge* — worth stating
explicitly so the new implementation doesn't repeat the same bugs:

1. **Price source matters.** `Rs2GrandExchange.getRealTimePrices` (a Microbot Hub
   utility) sources from `ge-tracker.com` first, a third-party aggregator that
   caused a real bad price clamp in production (an item clamped to ~10gp when it
   was genuinely worth ~40gp). PPOFlipperStar's `MarketStateProvider` and any
   price-clamping-before-submit logic must call the OSRS Wiki's real-time API
   directly, never that helper.
2. **`ConfigManager.setConfiguration(group, key, Object)`'s generic overload**
   only cleanly serializes a handful of special-cased types; a plain `Map` or
   custom object silently becomes Java's `Object.toString()` debug format, not
   valid JSON, and blows up on the next load. Persist anything structured
   (portfolio ledger, buy-limit ledger, RL episode logs) by hand-serializing to
   a JSON string with Gson and storing/loading through the plain `String`
   overloads instead.

Also a deliberate *change* from the old design: `PortfolioManager` here tracks
**inventory + bank**, not inventory-only. The old code chose inventory-only
because `Rs2Bank`'s cache is only populated once the bank has actually been
opened this session. PPOFlipperStar's bank manager proactively opens/refreshes
the bank on a slow interval (configurable, off by default to avoid unnecessary
trips) specifically so the "manage bank + inventory as one portfolio, synced at
all times" requirement can be trustworthy — with a config toggle to run
inventory-only if the user prefers the old, more conservative behavior.

### 2.4 The execution state machine (`PPOFlipperStarScript`)

Extends Microbot's `Script` base class, same shape as `GeStarV2Script`:

```
IDLE → GOING_TO_GE → OBSERVE → DECIDE → SUBMITTING → MONITORING → COLLECTING → (back to OBSERVE)
                         ↑                                                          │
                         └──────────────────────────────────────────────────────────┘
```

- **OBSERVE**: builds the state vector (§3.2) from every manager above, for
  every watchlisted item plus currently-held positions.
- **DECIDE**: writes one `decision/request` document per tick (batched — all
  watchlisted items in one request, not one write per item — see §3.6), then
  waits (bounded by a timeout, defaulting to HOLD on every item if it's
  exceeded) for the matching `decision/response`: an action per item —
  `BUY(qty, price) | SELL(qty, price) | HOLD`.
- **SUBMITTING**: each non-HOLD action passes through `Guardrails.check()`
  first (identical enforcement whether the order came from the model or a
  human's right-click) before hitting `Rs2GrandExchange.buyItem`/`sellItem`.
- **MONITORING/COLLECTING**: same fill-detection and cost-basis recording
  approach as `GeStarV2Script` (poll `GrandExchangeOfferDetails`, collect on
  terminal state, record actual spent/received — not requested price — against
  `PortfolioManager`).
- A **panic button** ("Cancel all offers") in the panel, same
  pre-empting-any-state design as the existing one: sets a volatile flag, aborts
  every live GE offer, collects everything back, and freezes the policy loop
  until manually resumed.

### 2.5 Config (`@ConfigGroup("ppoflipperstar")`)

Sections mirroring `GeStarV2Config`'s structure:
- **Orders**: withdraw-from-bank toggle, inventory-only vs inventory+bank mode.
- **Guardrails**: guardrails master switch, max GP/session, max qty/item, max
  price deviation %, stop-on-breach.
- **Behavior**: max concurrent offers, collect-to-bank, decision-tick interval.
- **PPO**: decision-response timeout (how long to wait for Firestore's
  `decision/response` before defaulting to HOLD, see §3.6), watchlist
  management, model confidence threshold below which the plugin forces HOLD
  regardless of the model's suggested action, "shadow mode" (model decides but
  every action requires a manual click to confirm — the recommended way to
  trial a new checkpoint before trusting it unattended).
- **Cloud sync** (already implemented — see the Firestore-persistence
  addendum in §0): `firestoreSyncEnabled`, `firestoreServiceAccountPath`.

---

## 3. The PPO framework (Python side)

### 3.1 Why PPO specifically

PPO (Proximal Policy Optimization) is the standard choice for this shape of
problem: continuous decision-making under uncertainty, a large discrete-ish
action space (which item, buy/sell/hold, how much, at what price), a reward
that's naturally delayed (you don't know if a buy was good until the sell
closes, sometimes hours later), and a need for stable, sample-efficient
on-policy learning without the brittleness of vanilla policy gradients. It's
also the same family of algorithm used for RLHF and most modern actor-critic
control problems — well-understood tooling (Stable-Baselines3) exists so we're
not implementing PPO's clipped-objective math from scratch unless we choose to.

### 3.2 Environment design (`data/ppo/env.py`, Gymnasium-style)

**One environment instance per training episode, replaying real historical
data** from `data/raw/5m/*.parquet` (6 months already collected) — this is what
makes fast training possible: no need to play the actual game to generate
experience, the market simulator replays real OSRS Wiki price/volume history
tick by tick, and the "agent" plays against that recorded book.

- **State (observation space)**, per trackable item, reusing
  `build_features.py`'s feature set as the starting point:
  - `spread_pct` (current insta-buy/insta-sell gap)
  - `volatility_1h/6h/24h`, `mean_price_1h/6h/24h`, `volume_1h/6h/24h`,
    `momentum_1h/6h/24h` (already computed by the existing pipeline)
  - Agent-specific state not in the old feature set (new, since this is a
    policy, not a scoring function): current position size for this item,
    unrealized P&L % if held, holding duration, fraction of GE buy-limit
    already used in the current 4h window, GP currently available, number of
    free GE slots.
- **Action space**: per item, a small discrete set —
  `{HOLD, BUY_SMALL, BUY_MEDIUM, BUY_LARGE, SELL_25%, SELL_50%, SELL_100%}` —
  discretized rather than fully continuous (continuous price/qty is harder to
  train stably and GE mechanics are chunky anyway: 8 slots, integer quantities,
  buy limits). Price offered is derived from the current spread by a fixed
  small offset per action tier, not separately learned at first — this keeps
  the action space small enough to train fast, with room to make price a
  learned continuous output in a v2 if the discretized version underperforms.
- **Reward**: realized P&L on every SELL/collect (actual GP after tax — OSRS
  GE tax is a real, non-negligible cost the old LightGBM label already had to
  account for and this must too), a small time-decay penalty on HOLD while
  holding a position (discourages bag-holding forever), and a penalty for a
  guardrail-rejected action (teaches the policy to respect buy limits/funds
  without needing the guardrail layer to bail it out during training).
- **Episode structure**: a fixed-length window (e.g. one simulated week) sampled
  from a random start point in the 6 months of history, across a random subset
  of items each episode — this is what lets one GPU run generate huge amounts
  of effective experience from a fixed historical dataset (the same weeks get
  replayed with different starting conditions/items many times over).

### 3.3 Network architecture

Small and fast on purpose — this is a tabular-ish state (a few dozen features
per item), not an image or long text sequence, so a large network buys nothing:

- Shared trunk: 2–3 fully-connected layers (e.g. 256 → 128 → 128), ReLU.
- Actor head: outputs a categorical distribution over the discrete action set.
- Critic head: outputs the state-value estimate (standard PPO actor-critic).
- Multi-item handling: the same network is applied per-item (parameters
  shared across items, since "what makes a good flip" generalizes), with the
  per-tick decision loop iterating watchlisted items and calling the network
  once per item per tick (batched on the GPU/CPU as one forward pass across all
  items, not literally one-at-a-time).

### 3.4 Training loop and checkpoints

- Library: Stable-Baselines3's `PPO` implementation over the custom
  `GEMarketEnv` (fastest path to a correct, tested PPO — avoids re-deriving
  the clipped surrogate objective, GAE, and rollout buffer machinery by hand).
- Checkpointing: a `.pth` saved every N training steps (configurable, e.g.
  every 50k steps) to `data/models/ppo/checkpoints/ppo_<step>.pth`, plus a
  running `best.pth` tracked by validation-episode reward on held-out months
  (train on months 1–5, validate/pick-checkpoint on month 6 — the existing
  `train_val` split convention this repo's pipeline already uses).
- Metrics logged per checkpoint: mean episode reward, realized P&L, Sharpe-like
  ratio of returns, number of guardrail violations attempted, win rate
  (fraction of closed positions that were profitable after tax).
- **Agent versioning**: each checkpoint is tagged with the git commit of the
  env/reward code that produced it (reward/env changes make checkpoints from
  different versions non-comparable) — stored as a small sidecar JSON next to
  each `.pth`.

### 3.5 One training run, as fast as possible — sizing

Given the answers: one rented-GPU run, not an ongoing pipeline; 6 months of
5-minute data already collected; inference stays on the Python side.

- **Compute**: a single mid-tier rented GPU (e.g. RTX 4090 or A10, ~$0.30–0.50/hr
  on Vast.ai/RunPod/Lambda) is enough — this network is tiny (hundreds of
  thousands of parameters), the bottleneck is environment stepping
  (numpy/pandas lookups against the historical data), not GPU FLOPs. A100-class
  hardware would be wasted money here.
- **Throughput**: a vectorized environment (Stable-Baselines3's
  `SubprocVecEnv`, e.g. 16–32 parallel env copies) stepping against
  pre-loaded-into-RAM historical arrays (not re-reading parquet per step) should
  sustain on the order of several thousand env steps/second on a rented
  GPU+multicore instance.
- **Estimated wall-clock for one solid run**: on that throughput, 5–10 million
  environment steps (a reasonable budget for a PPO policy on a state space this
  small to converge well past random and stabilize) lands in roughly
  **1.5–4 hours** of actual training time, plus ~15–30 minutes of setup
  (provisioning the instance, transferring the ~5GB of parquet data, installing
  deps) and ~15 minutes of held-out-month evaluation at the end. Budget a
  **half-day end-to-end** for the first full run including the inevitable one
  or two restarts after fixing a reward-shaping or env bug found in the first
  hour — that first-hour sanity check (is reward trending up at all, is the
  agent avoiding obvious guardrail violations) is worth watching live rather
  than walking away for the full run blind.
- This is a rough order-of-magnitude estimate, not a guarantee — actual speed
  depends on how much of the env step can be vectorized versus staying in
  per-item Python loops; the plan is to profile the env after a first short
  (~10 minute) run before committing to the full budget.

### 3.6 Model↔plugin communication: Firestore, not local HTTP (revised)

**Superseded design note:** earlier drafts of this proposal (and the
milestone-order table in §6) described a local HTTP inference server
(`data/service/ppo_server.py`, plugin POSTs state, gets an action back over
`127.0.0.1`) on the reasoning that same-machine IPC should be fast and simple.
That assumption no longer holds: the actual goal is a *central inference
server managing multiple RuneLite client instances across different
machines* — there is no shared localhost between them, so local HTTP doesn't
reach. Firestore (already integrated for portfolio/ledger/watchlist/trade-
history persistence — see §4) is repurposed as the transport instead. Message
volume is trivial at any realistic scale (low hundreds to a couple thousand
writes/hour even at ten simultaneous accounts), so Firestore's usual
read/write-cost concerns don't apply here.

**Schema** (new, under the same `accounts/{accountHash}/...` root as §4's
other collections):
- `accounts/{accountHash}/decision/request` — **one document, overwritten
  every decision tick** (not one-per-tick history — see below for why).
  Written by the plugin. Fields: `tickId` (a monotonically increasing counter
  or timestamp, used to pair this request with its response), `items` (array
  of per-watchlisted-item state vectors — the same features §3.2 defines:
  spread/volatility/volume/momentum at 1h/6h/24h, current position size,
  unrealized P&L%, holding duration, buy-limit headroom, available GP, free
  GE slots), `writtenAt` (timestamp).
- `accounts/{accountHash}/decision/response` — one document, overwritten by
  the Python worker once inference completes. Fields: `tickId` (echoes the
  request it answered — the plugin ignores a response whose `tickId` doesn't
  match what it most recently sent, so a slow/stale answer is never acted
  on), `actions` (array of `{itemId, action, quantity, price, confidence}`),
  `checkpointVersion` (which `.pth`/commit produced this, for the panel to
  display), `answeredAt` (timestamp).

**Why overwritten "latest" docs, not one-doc-per-tick history:** the
request/response pair is a live handshake, not a record meant to be reviewed
later — `tradeHistory` (§4) already serves as the actual durable log of what
happened. A doc-per-tick design would need a separate cleanup/TTL mechanism
for no real benefit at this volume.

**Flow per decision tick** (replaces the old DECIDE-phase description):
1. Plugin's `OBSERVE` phase builds the state vector and writes it to
   `decision/request` with a fresh `tickId`.
2. A persistent Firestore snapshot listener on the Python side (one process
   can watch many accounts' `decision/request` docs at once — no polling
   needed) fires, runs the loaded PPO checkpoint, writes `decision/response`.
3. The plugin either holds its own snapshot listener on `decision/response`
   or short-polls for a `tickId` match, bounded by a timeout (a few seconds).
   If no matching response arrives in time, that tick's decision defaults to
   HOLD for every item and the loop moves on — a slow/unreachable model must
   never block the trading loop.
4. Guardrails (§2.2) still apply to every action Firestore returns, exactly
   as they do to a manual order — the model is never the last line of
   defense against a bad trade.

**One worker per account, to avoid a race:** if a central server eventually
watches many accounts, each account's `decision/request` stream must have
exactly one Python worker answering it — two workers racing to write the
same account's `decision/response` is a correctness bug (whichever writes
last wins, but there's no guarantee it processed the more recent request).
Simplest enforcement: shard accounts across workers by `accountHash`, not by
having every worker listen to every account.

### 3.7 Going from backtest-good to live-safe

A policy that performs well replaying historical data is not automatically
safe to run unattended with real GP. Recommended rollout, gated by the panel's
**shadow mode** (§2.5):

1. Train and pick a checkpoint on held-out-month backtest performance.
2. Run in shadow mode in the actual client: the model proposes actions, they're
   shown in the panel, but nothing executes without a manual click. Confirms
   the state vector the plugin actually builds matches what the model expects
   (units, feature scaling, missing-data handling for illiquid items) and
   surfaces any live-vs-backtest mismatch before any GP is at risk.
2. Small-stakes live run with tight `Guardrails` (low max-GP-per-session, low
   max-qty-per-item) for a day or two, comparing realized results against the
   backtest's predicted distribution.
3. Loosen guardrails gradually as the checkpoint proves out. Guardrails always
   stay on as a hard outer bound — the RL policy is never the last line of
   defense against a bad action, `Guardrails.check()` is.

---

## 4. Firestore persistence and model↔plugin transport

Implemented in milestone 2.5, ahead of the original build order (see §6) —
noted here as its own section since it's referenced from multiple other
places in this document (§0's addendum, §3.6, §6's Decisions).

Project: reuses the existing `ppoflipperopus` Firebase project and its
service-account credential (gitignored, never committed) — infrastructure
reuse only, no data/collection sharing with `ge-star-v2`'s `orders`/
`buyLimits` collections or the shared `tradableItems` reference list.

Every document lives under `accounts/{accountHash}/...`, keyed by
`Client.getAccountHash()` (a stable Jagex-issued account identifier, not a
display name or a locally-generated UUID — chosen specifically so history
follows the actual RuneScape account across reinstalls and, eventually,
across machines). All collections are admin-only in `firestore.rules`
(`allow read, write: if false` — written directly via the service-account
credential, which bypasses rules the same way Cloud Functions do; no client
SDK read/write path exists or is needed).

| Collection | Purpose | Lifecycle |
|---|---|---|
| `accounts/{hash}/portfolio/{itemId}` | Cost-basis ledger: quantity held, average cost, realized P&L, acquisition timestamp | Durable — Firestore is the source of truth, local `ConfigManager` is the offline cache |
| `accounts/{hash}/buyLimitLedger/{itemId}` | Rolling 4h GE buy-limit usage (per-event quantities/timestamps) | Durable, same as portfolio |
| `accounts/{hash}/watchlist/{itemId}` | Which items the autonomous policy may act on (doc existence = membership) | Durable, same as portfolio |
| `accounts/{hash}/tradeHistory/{autoId}` | Immutable log, one doc per completed BUY/SELL fill (action, item, qty, actual submitted/filled price, GP, timestamp) | Append-only, kept forever — this is the actual "browse my history" record |
| `accounts/{hash}/decision/request` | Model↔plugin transport (§3.6): current state vector for every watchlisted item | **Transient** — one doc, overwritten every decision tick |
| `accounts/{hash}/decision/response` | Model↔plugin transport (§3.6): the policy's action per item, tagged with the `tickId` it answered | **Transient** — one doc, overwritten every decision tick |

**Sync model** (portfolio/buyLimitLedger/watchlist): on startup, a best-effort
pull-and-reconcile — a successful Firestore read fully replaces the
corresponding local entry (Firestore wins); a failed/unreachable pull logs a
warning and the plugin proceeds local-only for that session, never blocking
startup. Every local mutation (a recorded buy/sell, a ledger update, a
watchlist add/remove) pushes to Firestore asynchronously on a background
executor — never blocking the script tick or the EDT. A safety guard holds
order submission for a tick while the startup reconcile is still in flight,
so a trade can't race ahead of — and then get silently overwritten by — the
pull's full-replace.

---

## 5. Build system integration

New Gradle module, following `ge-star-v2`'s exact shape:

- `plugins/ppo-flipper-star/build.gradle` — same `shadowJar` config
  (`compileOnly` the Microbot client jar, exclude it from the shaded jar).
- `settings.gradle` — add `include ':plugins:ppo-flipper-star'`.
- `plugins/ppo-flipper-star/src/main/java/net/runelite/client/plugins/microbot/ppoflipperstar/` —
  source root.
- Python side lives under `data/ppo/`: the training environment/loop, plus
  `data/ppo/inference_worker.py` (the Firestore-listener inference process —
  see §3.6; deliberately not placed under `data/service/`, since that
  directory is `flipper-star`'s LightGBM HTTP scoring service and this is a
  different mechanism entirely, not an extension of it).

---

## 6. Build order (suggested milestones)

1. **Scaffold + manual mechanics** ✅ — plugin skeleton, config, panel, all
   Managers, right-click menu, manual buy/sell/collect fully working with a
   human clicking every action. Fully useful on its own, and the thing the RL
   layer will be validated against.
2. **Watchlist + guardrails** ✅ — the safety/scoping layer the autonomous
   mode will run inside.
2.5. **Firestore persistence** ✅ (added ahead of the original order —
   portfolio/buy-limit-ledger/watchlist/trade-history now sync across
   sessions via the account's stable `Client.getAccountHash()`, per the §0
   addendum. This is also the mechanism §3.6 repurposes for model↔plugin
   communication, which is why it landed before milestone 3/4.)
3. **Environment + PPO training loop** ✅ — `GEMarketEnv` built and validated
   with real training runs (50k steps, then 500k steps after the first run's
   policy collapsed to a single always-BUY action — a known early-PPO failure
   mode, confirmed transient by the longer run: by step 400k the policy
   closes real profitable buy→sell cycles, 86% win rate on a validation
   episode). `best.pth` is the current checkpoint.
4. **Inference worker + plugin wiring** ✅ — `data/ppo/inference_worker.py`
   loads `best.pth` and answers `decision/request` documents; the plugin's
   DECIDE phase is wired in, unconditionally shadow-mode (no config path
   lets a suggestion reach `OrderQueue` without a human clicking Confirm).
   Extended further: `WikiHistoryBuffer` now feeds real rolling
   volatility/volume/momentum features (previously flattened
   approximations), and account discovery is automatic (presence-heartbeat
   scan, no account hash needs to be found/passed manually) with a one-click
   launch path (`scripts/launch-with-ppo.sh`).
5. **Full training run** (§3.5) — not started. The current `best.pth` is
   only 500k steps, well short of the 5-10M-step budget §3.5 sizes for real
   convergence; live shadow-mode testing with today's checkpoint is the
   deliberately-chosen next step before spending on this (see the "Live
   testing before the full run" note below), so the whole pipeline is known
   to work before paying for a better model to run through it.
6. **Shadow mode → gated live rollout** (§3.7) — shadow mode (step 2 of
   §3.7) is live-testable today via `scripts/launch-with-ppo.sh`. Steps 3-4
   (small-stakes live run, guardrail loosening) are not started and
   shouldn't be until shadow-mode output has actually been reviewed.

**Known gaps against this document, not yet closed:**
- `OrderQueue` (§2.2's table) is in-memory only — no `ConfigManager`
  persistence exists despite the table saying so. A client crash while
  orders are `QUEUED` (not yet submitted to the GE) loses them; a
  `SUBMITTED` order is recovered via `reconcileSubmittedOrders`' live-offer
  adoption regardless. Worth fixing before relying on unattended operation
  across restarts, not blocking for supervised shadow-mode testing.
- §2.2's `MarketStateProvider` and §2.1's inline code sample (which still
  shows the pre-fix `WidgetIndices.ResizableModernViewport.INVENTORY_CONTAINER`
  check) describe an earlier state of the code, superseded by
  `WikiPriceClient`/`WikiHistoryBuffer` and the layout-agnostic
  `getItemId() != -1` check respectively - naming/doc drift, not a
  functional gap.

---

## Decisions

- **Watchlist seeding**: pre-seeded with a curated high-volume/liquidity item
  list, derived from `data/raw/item_mapping.parquet` cross-referenced against
  the `volume_1h/6h/24h` features already computed in
  `data/processed/features.parquet` — same liquidity bar the old pipeline used
  (`MIN_VOLUME_1H`-style filter) rather than re-deriving a new threshold.
  Manual add/remove via right-click still works on top of this default set.
- **GPU rental provider**: Vast.ai.
- **Checkpoint retention**: pruned. Keep `best.pth` (by held-out-month
  validation reward) plus the last few periodic checkpoints once a run
  finishes; older intermediate checkpoints from the same run are deleted
  rather than kept indefinitely.
- **Model↔plugin transport**: Firestore (§3.6), not local HTTP — driven by an
  explicit future requirement, not a default assumption: a single central
  inference server is meant to eventually manage multiple RuneLite client
  instances across different machines, which local HTTP (same-machine only)
  can't reach.
- **Decision document lifecycle**: transient — `decision/request` and
  `decision/response` are each one document per account, overwritten every
  tick, not a growing per-tick history. `tradeHistory` (§4) remains the
  actual durable record of what happened; the decision docs are just the
  live handshake. No cleanup/TTL job needed as a result.
