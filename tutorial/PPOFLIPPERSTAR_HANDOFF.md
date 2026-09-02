# PPOFlipperStar — Handoff / Tutorial for Future Claude Sessions

This document exists so a future session with zero memory of this work can
pick up where it left off without re-deriving everything from scratch, and
without re-introducing bugs that were already found and fixed the hard way.
It covers: what this project is, how the pieces fit together, where to find
things in the code, how to actually run/test it, and a candid account of
every real incident hit during development — because the *why* behind a fix
is what keeps it from being silently reverted later.

If you're a Claude session picking this up: **read this whole document
before touching code.** Then read `plugins/ppo-flipper-star/PROPOSAL.md`,
which is the original design doc and has been kept up to date as the
authoritative architecture reference (this document is the *narrative*/
*operational* companion to it, not a replacement).

---

## 1. What this project actually is

**PPOFlipperStar** is a self-contained RuneLite/Microbot plugin that
autonomously (or manually, or in a supervised "shadow mode") buys and sells
items on the Old School RuneScape Grand Exchange, using a PPO (Proximal
Policy Optimization) reinforcement-learning policy trained in Python on 6
months of real historical OSRS Wiki price data.

It lives in the `BotStar` repo (`/Users/b1llstar/Documents/GitHub/BotStar`),
on branch **`ppo-flipper-star`** (based off `ge-star`, which is itself off
`main`). It is genuinely independent of the repo's other plugins
(`ge-star-v2`, `flipper-star`) — no shared code, no runtime dependency, no
reflection bridge. That was a deliberate, explicit decision (see PROPOSAL.md
§0): the prior project solved a similar problem with a LightGBM
(gradient-boosted-tree) model; this one is a genuinely different approach
(PPO reinforcement learning), built from scratch.

### The three big pieces

1. **The Java plugin** (`plugins/ppo-flipper-star/`) — runs inside RuneLite,
   owns all game interaction (buying, selling, collecting, banking,
   inventory), tracks portfolio/gold/buy-limits locally and in Firestore,
   enforces hard safety guardrails, and either lets a human manually place
   orders or (if `autonomousModeEnabled` is on) lets a trained model place
   them automatically.
2. **The Python training pipeline** (`data/ppo/`) — trains the PPO policy
   offline against a simulated market built from historical price data, and
   produces checkpoints (`.pth` files).
3. **The Python inference worker** (`data/ppo/inference_worker.py`) — a
   separate long-running process that loads a trained checkpoint and answers
   the plugin's "what should I do with this state?" questions over
   Firestore, in near-real-time.

These three talk to each other **entirely through Firestore**, not direct
network calls between the plugin and the worker — see §4 for why.

---

## 2. Where things live — a map

```
BotStar/
├── plugins/ppo-flipper-star/
│   ├── PROPOSAL.md                 ← the authoritative design doc, kept current
│   ├── build.gradle
│   └── src/main/java/net/runelite/client/plugins/microbot/ppoflipperstar/
│       ├── PPOFlipperStarPlugin.java      ← @PluginDescriptor, lifecycle, right-click menu
│       ├── PPOFlipperStarConfig.java      ← every config option lives here
│       ├── PPOFlipperStarScript.java      ← THE state machine - execution loop + DECIDE phase
│       ├── PPOFlipperStarPanel.java       ← the sidebar UI (Swing)
│       ├── PPOFlipperStarOverlay.java     ← in-game HUD overlay
│       ├── DecisionEngine.java            ← builds the state vector sent to the model
│       ├── DecisionSuggestions.java       ← shared holder for pending model suggestions
│       ├── PPOFlipperDecision.java        ← one model suggestion (item/action/qty/price/confidence)
│       ├── PPOFlipperOrder.java           ← one order (queued/submitted/done/skipped/failed)
│       ├── OrderQueue.java                ← the shared order list (panel + script both use it)
│       ├── Guardrails.java                ← hard safety checks, applied to EVERY order regardless of origin
│       ├── WatchlistManager.java          ← which items the model is allowed to act on
│       ├── WikiPriceClient.java           ← live insta-buy/insta-sell price (single snapshot)
│       ├── WikiHistoryBuffer.java         ← rolling 5-min price/volume history (for real features)
│       ├── InventoryManager.java, BankManager.java, GoldManager.java
│       ├── portfolio/PortfolioManager.java, BuyLimitLedger.java, CostBasisEntry.java
│       └── sync/                          ← Firestore plumbing
│           ├── AccountIdentity.java              ← resolves Client.getAccountHash()
│           ├── PPOFlipperStarGoogleAuth.java      ← JWT-bearer OAuth2, no Google SDK dependency
│           ├── PPOFlipperStarFirestoreClient.java ← hand-built REST calls to Firestore
│           └── PPOFlipperStarFirestoreSync.java   ← lifecycle/async-push wrapper around the client
├── data/ppo/
│   ├── env.py              ← GEMarketEnv - the Gymnasium training environment
│   ├── features.py         ← rolling-window feature computation (spread/volatility/momentum/volume)
│   ├── market_data.py      ← loads the 6 months of historical parquet data into RAM
│   ├── train.py            ← the actual PPO training loop (Stable-Baselines3)
│   └── inference_worker.py ← the live Firestore-listening inference process
├── data/raw/5m/*.parquet   ← 6 months of real 5-minute OSRS Wiki price/volume history
├── data/models/ppo/
│   ├── best.pth, best.json ← the currently-deployed checkpoint (see §6 for what's in it now)
│   └── checkpoints/        ← periodic checkpoints from training runs (pruned - see PROPOSAL.md)
├── firebase/
│   ├── firestore.rules     ← security rules (mostly admin-only; the web dashboard's read allowlist)
│   └── web/                ← the Vue dashboard source (builds to firebase/public/)
├── scripts/
│   ├── launch.sh               ← plain client launch (all plugins, no PPO worker)
│   ├── launch-with-ppo.sh      ← starts inference_worker.py + launch.sh together
│   ├── launch-with-flipper.sh  ← the OLD project's launcher (LightGBM scoring service) - unrelated
│   └── kill-ppo.sh             ← force-kill the whole PPOFlipperStar process stack (see §8)
└── tutorial/
    └── PPOFLIPPERSTAR_HANDOFF.md   ← this file
```

**Local Launchpad icons** (macOS, live outside the repo at
`~/Applications/`, not tracked in git):
- **BotStar Launcher** — plain client, no PPO.
- **BotStar + FlipperStar Launcher** — the OLD project's launcher.
- **BotStar + PPO Launcher** — starts the inference worker + client together.
  This is the one you want for anything PPOFlipperStar-related.
- **BotStar Kill Switch** — force-kills everything (see §8).

---

## 3. The Java plugin's execution model

`PPOFlipperStarScript` (extends Microbot's `Script` base class) runs two
independent loops on different cadences, both started by clicking **Execute**
in the panel:

1. **The mechanical execution loop** (`tick()`, every `SCHEDULE_INTERVAL_MS`
   = 600ms): a state machine —
   `IDLE → GOING_TO_GE → SUBMITTING_ORDERS → MONITORING_OFFERS → COLLECTING → DONE`
   (plus `CANCELLING_ALL` for the panic button). This pulls the next
   `QUEUED` order from `OrderQueue`, runs it through `Guardrails.check()`,
   and if it passes, submits it via `Rs2GrandExchange.buyItem`/`sellItem`.
   This part is **manual-order-execution only in origin** — it doesn't care
   whether an order came from a human clicking Confirm or from the model
   autonomously; both go through the exact same path.

2. **The DECIDE loop** (`runDecideTick()`, on its own cadence —
   `decisionTickIntervalSeconds`, default 1 second, on a separate executor
   thread so a slow Firestore round-trip never blocks order execution): for
   every watchlisted item, builds a state vector (`DecisionEngine`), writes
   it to Firestore as `decision/request`, waits (bounded by
   `decisionResponseTimeoutSeconds`) for the model's answer in
   `decision/response`, and turns actionable suggestions (confidence ≥
   `modelConfidenceThreshold`) into `DecisionSuggestions` for the panel.
   **If and only if `autonomousModeEnabled` is true**, those suggestions are
   also automatically pushed into `OrderQueue` (see §5 for the safety story
   here).

### The manager classes

Each does one job, all singletons via Guice `@Inject`:

- `InventoryManager` / `BankManager` — thin wrappers over Microbot's
  `Rs2Inventory`/`Rs2Bank`.
- `PortfolioManager` — weighted-average cost basis and realized/unrealized
  P&L, tracked across **inventory + bank** (a deliberate change from the old
  `ge-star-v2` plugin, which was inventory-only for staleness-safety reasons
  — see that class's javadoc for the full story). Persisted locally
  (`ConfigManager`) and to Firestore.
- `GoldManager` — total coins (inventory + bank), session net-worth delta.
- `BuyLimitLedger` — rolling 4-hour GE buy-limit tracking per item,
  persisted, so the plugin never tries to buy more of an item than the game
  will actually allow in a 4h window.
- `WatchlistManager` — the set of item ids the model is allowed to
  autonomously act on. **This is the main safety boundary between "what
  exists in the game" and "what the model can touch."**
- `Guardrails` — hard checks applied to *every* order before submission,
  regardless of where it came from: sell-more-than-held, exceed-GE-buy-limit,
  duplicate-buy, price-deviation-from-live-market, max-GP-per-session,
  max-quantity-per-item. See §5 — this is the thing that makes autonomous
  mode not insane.

---

## 4. Why Firestore, not a local HTTP server, for model↔plugin communication

This was a real design decision, not an accident (see PROPOSAL.md §3.6).
The original plan was a local HTTP server (plugin calls `127.0.0.1:PORT`,
gets an answer back). That was changed because **the actual long-term goal
is a central inference server managing multiple RuneLite client instances
across different machines** — there's no shared localhost between machines,
so Firestore (which both sides already need for persistence anyway) does
double duty as the transport.

**Schema** (all under `accounts/{accountHash}/`, where `accountHash` is
`Client.getAccountHash()` — a real, stable, Jagex-issued identifier, NOT a
display name or a locally-generated UUID, chosen specifically so history
follows the actual RuneScape account across reinstalls/machines):

| Collection | Written by | Purpose |
|---|---|---|
| `portfolio/{itemId}` | Plugin | Cost-basis ledger |
| `buyLimitLedger/{itemId}` | Plugin | Rolling 4h buy-limit events |
| `watchlist/{itemId}` | Plugin | Which items the model can act on |
| `tradeHistory/{autoId}` | Plugin | Append-only log of every completed fill |
| `decision/request` | Plugin | This tick's state vector (ONE doc, overwritten every tick) |
| `decision/response` | Python worker | The model's answer (ONE doc, overwritten every tick) |
| `presence/heartbeat` | Plugin | "I'm alive," refreshed every ~60s, so the worker can auto-discover accounts |

Plus one **project-wide, non-account-scoped** collection:
`modelTrainedItems/{gitCommit}` — the list of items a given trained
checkpoint actually saw during training (see §6).

All of this reuses the existing `ppoflipperopus` Firebase project and its
service-account key (`ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json`
at the repo root — **gitignored, never committed, never print/log its
contents**). This is infrastructure reuse only — PPOFlipperStar shares
nothing else with the old `ge-star-v2`/`flipper-star` project; it doesn't
read or write their `orders`/`buyLimits` collections.

**Two correctness lessons baked in from day one** (see
`Guardrails.java`/`WikiPriceClient.java` javadocs):
1. Never source live prices from `Rs2GrandExchange.getRealTimePrices` — it
   hits a third-party aggregator (ge-tracker.com) first, which caused a real
   bad price clamp in the sibling `ge-star-v2` project. Always call the OSRS
   Wiki's real-time API directly.
2. Never persist a `Map`/custom object through `ConfigManager`'s generic
   `setConfiguration(group, key, Object)` overload — it silently produces
   invalid JSON for anything but a few special-cased types. Hand-serialize
   with Gson to a JSON string and persist through the plain `String`
   overloads instead.

---

## 5. The autonomous-mode safety model — read this before touching it

`autonomousModeEnabled` (default **`false`**, in `PPOFlipperStarConfig`) is
the only thing that lets a model suggestion execute without a human clicking
Confirm. This is a genuinely separate flag from `shadowMode` — `shadowMode`
is currently **inert**, kept in the config purely as documentation of the
staged-rollout design (PROPOSAL.md §3.7); `PPOFlipperStarScript.runDecideTick()`
never reads it. This was deliberate: there is no dormant "if shadow mode is
off, submit directly" branch anywhere, so there's no way to reach autonomous
execution by flipping the wrong toggle.

**The actual safety guarantee, verified by direct code tracing (not just
assumed) during development:** every order, whether from a human's Confirm
click or from `PPOFlipperStarScript.autonomouslySubmit()`, is constructed
identically (`new PPOFlipperOrder(...)`) and goes through the exact same
`OrderQueue.add()` → `OrderQueue.nextQueued()` → `Guardrails.check()` →
`Rs2GrandExchange.buyItem/sellItem` path. `PPOFlipperOrder` has no "origin"
field — there is structurally no way for an autonomous order to skip
`Guardrails.check()`. If you ever change this, that invariant is the one
thing to re-verify by reading the code yourself, not by trusting a summary.

**Confidence gate**: `modelConfidenceThreshold` (default **0.5**) is applied
exactly once, before a suggestion is shown in the panel OR auto-submitted —
one code path for both, so they can't drift apart.

**Guardrail defaults** were deliberately raised from "no cap" to real
numbers once autonomous mode existed: `maxGpToSpend` = 5,000,000 (session
cap, resets each Execute — NOT a lifetime cap), `maxQuantityPerItem` =
50,000.

---

## 6. The trained model — what it actually is right now

As of this document, `data/models/ppo/best.pth` is a checkpoint from a
**real, full training run on a rented RunPod RTX 4090 GPU**:
- 10,000,000 training steps, ~1.84 hours actual training time, $1.89 total
  cost (across a first pod that turned out to have a broken GPU passthrough
  — terminated immediately — and the working second one).
- Trained on **300 liquid items** (up from 60 in earlier local validation
  runs), 6 months of real historical 5-minute price data (March–August
  2026), held out the trailing 30 days as validation.
- The deployed checkpoint is step 9,500,000 (chosen by best validation
  reward, not just "last") — **85.3% validation win rate, +1,428,202gp mean
  realized P&L per validation episode.**
- Training was genuinely noisy step-to-step (not monotonically improving —
  see `data/models/ppo/training_log.csv` for the full history), which is
  normal PPO behavior, not a red flag.
- Its git commit tag is `698392b0ed9101d471a8d7b426fcc57a8a315437` — this is
  also the key for its `modelTrainedItems/{gitCommit}` Firestore document
  (the list of ~300 items it actually trained on).

**"Seed watchlist from trained items"** (a button in the panel) pulls that
list and adds every item to your watchlist that isn't already there — this
is how you widen the model's action universe to match what it actually has
real experience with, rather than watchlisting something it's never seen
(which would make its suggestions for that item pure extrapolation).

If you train a new checkpoint, remember to:
1. Update `data/models/ppo/best.pth`/`best.json`.
2. If training happened on a remote box with no `.git` directory (see below), pass `--git-commit <hash>` to `train.py` so checkpoints/Firestore don't get tagged `"unknown"`.
3. Restart `inference_worker.py` — it loads the checkpoint once at startup and does **not** hot-reload.
4. Update the hardcoded `DEPLOYED_CHECKPOINT_GIT_COMMIT` constant in `PPOFlipperStarPanel.java` (the seed-watchlist button reads this) — it's a constant for now, not read from `best.json` at runtime, because there was no clean way for the sideloaded Java plugin to read a file under `data/models/ppo/` and this wasn't worth over-engineering yet.

### Training on a rented GPU (RunPod)

If you need to do this again: RunPod has a real REST API
(`https://rest.runpod.io/v1/`) that can provision a pod, no browser
automation needed. Key lessons from doing this once:
- **Community Cloud pods can have broken GPU passthrough** — the first pod
  we rented showed `nvidia-smi` seeing the GPU fine but PyTorch's
  `torch.cuda.is_available()` returning `False` (a `/dev/nvidia7` device node
  mismatch, no `NVIDIA_VISIBLE_DEVICES` env var set). **Secure Cloud** pods
  worked correctly. Always verify `torch.cuda.is_available()` returns `True`
  before doing anything else — if not, terminate and get a fresh pod rather
  than debugging a broken host assignment.
- **`pip install stable-baselines3` can silently upgrade torch** to a
  version whose CUDA build is newer than the pod's driver supports (we hit
  `torch 2.13.0+cu130` on a driver that only supported CUDA 12.8). Fix:
  `pip install --no-deps torch==2.4.1` (or whatever the pod's pre-installed
  compatible version was) after installing the other packages.
- **Run training unbuffered** (`python3 -u train.py ...`) and detached
  (`nohup ... &`, then `disown`) — otherwise stdout buffering means you see
  zero output for a long time even though it's working, which looks
  identical to a hang.
- Code deployed via `scp`/tarball (not a git clone) has no `.git` directory
  — `train.py --git-commit <hash>` exists specifically for this (see §6.1
  above and the `4e0c323` commit).

---

## 7. Real incidents hit during development — read these before assuming something is safe

These are documented here, not just in commit messages, because they're
exactly the kind of thing a future session might unknowingly re-introduce
while "improving" something.

### 7.1 — `ItemMappingData.hasTradeLimit()`/`getEffectiveTradeLimit()` are a naming trap

Verified via bytecode decompilation: `hasTradeLimit()` is actually
`tradeLimitPer4Hours > 0 && tradeLimitPer4Hours < 1000` (a bounded range
check, not a "do we have real data" check), and `getEffectiveTradeLimit()`
clamps anything ≥ 1000 down to a flat 500. This silently broke two things at
once for any item with a real buy limit ≥ 1000 (the overwhelming majority of
common flip items — Fishing bait at 8000, Flax at 13000, etc.):
- Every model suggestion came back with `quantity=0` regardless of
  confidence (the Python sizing formula divides by `buyLimit`, which was
  silently zero).
- **The buy-limit guardrail was silently not enforcing at all** for those
  items — a real safety gap, not just a suggestion-quality bug.

**Fix**: read `mapping.tradeLimitPer4Hours` (the raw field, `-1` is the real
"no data" sentinel) directly, never those two helper methods. See
`Guardrails.checkBuyLimit()` and `DecisionEngine`'s buy-limit computation —
both have long javadoc comments explaining this exact trap. **If you ever
see either of those method names anywhere in this codebase again, that's a
bug being reintroduced.**

### 7.2 — Guice-singleton state resets on every plugin disable/re-enable

`WikiHistoryBuffer` and `AccountIdentity` are `@Singleton` classes, but that
scoping is per-plugin-injector, and RuneLite creates a **fresh injector every
time a plugin is disabled and re-enabled** (not just on a real client
restart). This bit twice:

- `WikiHistoryBuffer`'s in-memory rolling price history was wiped on every
  re-enable. Since real volatility/momentum signal needs real elapsed
  wall-clock *hours* to build, a buffer that never survives more than a few
  minutes at a time (from someone toggling the plugin while testing) never
  accumulates real signal at all — the model was fed all-zero
  volatility/momentum, which directly caused a badly-mispriced SELL
  suggestion that a guardrail correctly caught (the guardrail did its job;
  the underlying data was the actual problem). **Fixed** by persisting the
  buffer to local `ConfigManager` (survives a same-machine restart) and
  seeding from a new shared, non-account-scoped Firestore collection
  `marketHistory/{itemId}` (survives even a fresh install, and sets up
  genuine cross-machine sharing for the future multi-account goal).

- The presence heartbeat (`PPOFlipperStarFirestoreSync.pushPresenceHeartbeat`)
  used the non-blocking `AccountIdentity.getAccountHash()`, which only ever
  has a value once a `GameStateChanged → LOGGED_IN` event fires — but that
  event only fires on an *actual login transition*, never on plugin
  re-registration while already logged in. A disable/re-enable cycle with no
  intervening logout left the heartbeat silently, permanently dead (no error,
  just nothing happening) until a real logout/login. **Fixed** by switching
  to `AccountIdentity.resolveBlocking()`, which checks the client's actual
  current login state directly rather than waiting for a future event.

**Lesson for future work**: any `@Singleton` class in this plugin that holds
meaningful in-memory state needs to ask "what happens if this gets recreated
while the user is already logged in / already has real state elsewhere?"
This is not a hypothetical — it happened twice in one project.

### 7.3 — `Rs2GrandExchange.getActiveOfferSlots()` called per-item caused a real client freeze

Verified via bytecode decompilation: this method does a genuine **blocking
round-trip onto the RuneLite client thread**, up to 8 times per call
(`ClientThread.runOnClientThreadOptional` → blocks via
`FutureTask.get(10000, MILLISECONDS)`), with no internal caching.
`DecisionEngine.buildRequestItem()` was calling it **once per watchlisted
item**, every ~1-second decision tick. At 303 watchlisted items, that's
potentially thousands of sequential blocking client-thread hops per second —
enough to visibly freeze the client's own rendering through pure queue
contention. This was reported live ("the plugin slows the game down to a
halt until the window freezes").

**Fix**: the value being computed (how many GE slots are active) is a global
fact, identical for every item in the same tick — moved the single call out
of the per-item loop into `decide()`, computed once per tick, passed down as
a parameter. Cut the call rate from ~300×/tick to 1×/tick.

**Lesson**: any Microbot API call inside a per-item loop over a large
watchlist deserves scrutiny — ask "does this value actually vary per item,
or is it the same every time and just misplaced?" Also: if something in this
codebase ever needs to call a Microbot API from a background thread, check
whether that API self-wraps in `Microbot.getClientThread().invoke()` — if it
does, and you call it in a loop, you've built a freeze generator.

### 7.4 — Autonomous mode had no cap relative to the GE's real 8-slot capacity

The DECIDE phase re-evaluates the *entire* watchlist every tick with **no
memory of what it already proposed**. With `autonomousModeEnabled` on and a
300+ item watchlist, this queued over **1,500 orders in a few minutes** —
but the GE only has 8 real slots and can only actually submit roughly one
order every 12-20 seconds. Only ~39 orders ever reached a real GE
submission, and only 11 ever filled. Visible symptoms reported live: (a)
only a handful of early-watchlisted items ever actually traded, and (b) the
exact same item/price got "AUTONOMOUS submit"-logged repeatedly before its
predecessor had even resolved (e.g. Maple logs, Iron platebody).

**Fix** (`PPOFlipperStarScript.autonomouslySubmit()`): two pre-filters
before `queue.add()`, neither touching `Guardrails`:
1. **Per-item/action dedup** — skip a suggestion if `OrderQueue` already has
   a `QUEUED`/`SUBMITTED` order for the same item id and same BUY/SELL
   direction.
2. **Queue-depth cap** — stop queuing more suggestions once
   `QUEUED + SUBMITTED` count reaches `maxActiveOffers × 3`.

**Known accepted limitation** (documented in the method's javadoc, not
silently ignored): the watchlist is a `LinkedHashSet` (insertion order), so
once the depth cap is hit mid-tick, earliest-watchlisted items consistently
win remaining backlog headroom. A fair-rotation scheme would fix this but
wasn't judged worth the complexity for what the fix actually needed to solve
(unbounded growth, not perfect fairness).

### 7.5 — No way to clear a runaway queue short of restarting the client

A direct consequence of 7.4: while the bug was live, there was no way to
wipe the 1,500+ order backlog except killing the whole client. Added a
dedicated **"Clear queue"** button in the panel (next to the order-queue
header) that removes every `QUEUED` order in one click (leaves `SUBMITTED`
orders alone — those are real live GE offers, use "Cancel all offers" for
those instead).

---

## 8. Operational playbook

### Starting everything
```bash
./scripts/launch-with-ppo.sh
```
or click **BotStar + PPO Launcher** in Launchpad. Starts
`inference_worker.py` (auto-discovers your account via presence heartbeat,
no account hash needs to be found/passed manually) then the client. Log in
normally; the worker picks up your account within ~30-90 seconds.

### Emergency stop
```bash
./scripts/kill-ppo.sh
```
or click **BotStar Kill Switch** in Launchpad. Force-kills the client, the
inference worker, and the launch wrapper by exact process match (never a
broad `pkill java`). **Does not cancel live GE offers** — those need the
panel's "Cancel all offers" button once you're back in, or manual handling.
This exists specifically for the "client froze" scenario (§7.3) where a
clean shutdown isn't possible.

### If you enable `autonomousModeEnabled`
- Confirm `modelConfidenceThreshold` is where you want it (0.5 is the
  current default — don't assume it's still there without checking, someone
  may have changed it in the panel since).
- Watch the client log (`~/.runelite/logs/client.log`, grep for
  `PPOFlipperStar`) for the first several minutes. `AUTONOMOUS submit` lines
  show what got queued; `submitted BUY/SELL` lines show what actually
  reached the GE; rejections show guardrails working.
- If the client becomes unresponsive, use the Kill Switch, not patience.
- The "Clear queue" button is your friend if the backlog ever looks wrong.

### Restarting after a code change
The plugin jar and the Python worker are **separate processes that do not
hot-reload**. After any Java change: rebuild (`./gradlew build` or just
relaunch, which rebuilds automatically) and restart the client. After any
Python change to `inference_worker.py` or swapping `best.pth`: restart the
worker process (kill it, relaunch).

### Checking live state without opening the client
Everything meaningful is in Firestore. From a Python shell with the venv
active:
```python
from google.cloud import firestore
db = firestore.Client.from_service_account_json(
    'ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json')
hash_id = '<your account hash - a negative number, check accounts/ collection or an "Account ... presence detected" log line>'

# Latest model decision
resp = db.collection('accounts').document(hash_id).collection('decision').document('response').get().to_dict()

# Current holdings
portfolio = list(db.collection('accounts').document(hash_id).collection('portfolio').stream())

# Trade history
trades = list(db.collection('accounts').document(hash_id).collection('tradeHistory')
    .order_by('timestampMillis', direction=firestore.Query.DESCENDING).limit(20).stream())
```
There's also a **web dashboard** at https://ppoflipperopus.web.app (Vue app,
`firebase/web/`) — read-only, Google-sign-in-gated to an email allowlist in
`firestore.rules`, shows live portfolio/history/decision state without
needing Python or the client open. See the root `README.md`'s "PPOFlipperStar
web dashboard" section for deploy instructions and how to add someone to the
allowlist.

---

## 9. Things that are still open / known gaps (as of this document)

- **`OrderQueue` persistence** was added (QUEUED/SUBMITTED orders survive a
  restart), but the fairness issue in §7.4 (early-watchlisted items winning
  the queue-depth race) is not fixed, just documented.
- **`DecisionEngine`'s live feature quality**: `WikiHistoryBuffer` needs real
  wall-clock hours of continuous uptime to produce meaningful
  volatility/momentum signal (matching what the model trained on). A fresh
  install or a just-added watchlist item will have thin/zero signal for a
  while — this is inherent, not a bug, but worth remembering when judging
  early suggestion quality after a restart.
- **No fair-rotation scheme** for autonomous mode's per-tick suggestion
  processing (§7.4's documented limitation).
- **`DEPLOYED_CHECKPOINT_GIT_COMMIT`** in `PPOFlipperStarPanel.java` is a
  hardcoded constant, not read from `best.json` at runtime — fine for now,
  but will go stale if a new checkpoint is deployed without updating it.
- **The full item-mapping bug (§7.1) may have similar cousins** elsewhere in
  the Microbot API surface that haven't been hit yet — if something's
  behavior doesn't match its name, verify against actual bytecode rather
  than trusting the method name, especially for anything touching money or
  safety.
- **No live end-to-end test harness** — everything has been validated
  through real (careful, incremental) live testing against a real account,
  not automated tests. Any future change to `Guardrails`, `OrderQueue`, or
  the autonomous-submit path should be re-verified the same way this
  document describes (direct code tracing of the safety-critical path, not
  just "the build passed").

---

## 10. Quick reference — key commands

```bash
# Build just this plugin
./gradlew :plugins:ppo-flipper-star:build -PmicrobotClientVersion=2.6.21 --offline -q

# Start everything
./scripts/launch-with-ppo.sh

# Emergency stop
./scripts/kill-ppo.sh

# Retrain (locally, small/fast sanity check)
cd data/ppo && ../venv/bin/python train.py --timesteps 50000 --checkpoint-freq 10000 --max-items 60

# Retrain (the real thing - needs a GPU, see §6.1)
cd data/ppo && ../venv/bin/python train.py --timesteps 10000000 --checkpoint-freq 250000 --n-envs 8 --max-items 300

# Check what's currently running
ps aux | grep -E "microbot|inference_worker"
```
