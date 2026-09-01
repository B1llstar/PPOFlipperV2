"""
PPO inference worker - the Python side of PPOFlipperStar's model<->plugin transport
(PROPOSAL.md section 3.6/4, milestone 4). Loads a trained checkpoint once at
startup, then listens on Firestore for `decision/request` documents written by
the Java plugin and answers them with `decision/response` documents.

Transport, and why this differs from the Java plugin's approach
------------------------------------------------------------------
PROPOSAL.md 3.6 specifies Firestore (not local HTTP) as the transport because a
single inference server is meant to eventually serve many RuneLite client
instances across different machines. The Java plugin's Firestore client
(PPOFlipperStarFirestoreClient.java) deliberately uses plain java.net.http REST
calls against Firestore's REST API, hand-building the wire-format JSON itself,
specifically to avoid pulling the (heavy, gRPC-based) official Firestore Admin
SDK into a sideloaded RuneLite plugin jar - every extra dependency there adds to
a classloader/shading footprint that matters a lot more for a jar that gets
loaded into someone else's client process than it does for a standalone
Python service.

None of that applies here. This is a plain long-running Python process with its
own venv/dependency set (see data/requirements.txt), already depending on
`google-cloud-firestore` elsewhere in this repo (data/pipeline/upload_tradable_items.py,
data/service/main.py both already import `from google.cloud import firestore`).
Firestore's REST API has no true server-push mechanism over plain HTTP - only
the gRPC-based Admin/client SDK exposes a real `on_snapshot` listener API. Using
that SDK here (not a REST reimplementation, not polling) is the correct choice
for this specific process: it gets a genuine push-based listener (per
PROPOSAL.md 3.6's "a persistent Firestore snapshot listener on the Python side...
no polling needed"), it costs nothing extra to depend on since the repo already
depends on it, and there is no classloader-weight concern for a standalone
Python process the way there is for a sideloaded Java plugin jar.

Flow per decision tick (see PROPOSAL.md 3.6 for the full schema)
------------------------------------------------------------------
1. The Java plugin writes accounts/{accountHash}/decision/request - one
   document, overwritten every tick - with a `tickId` and an `items` array of
   per-watchlisted-item state vectors.
2. This worker's on_snapshot listener fires, builds an observation vector per
   item (matching env.py's exact per-item observation construction - see
   `_build_observation_row` below), runs the loaded policy
   (`model.predict(obs, deterministic=True)`) once per item, and writes
   accounts/{accountHash}/decision/response with one action per item, echoing
   the request's tickId.
3. The plugin either polls or listens on decision/response and ignores any
   response whose tickId doesn't match the most recent request it sent.

Account discovery
------------------
By default (no --account-hash given) this worker auto-discovers accounts to
watch by periodically scanning accounts/{accountHash}/presence/heartbeat
(a doc PPOFlipperStarFirestoreSync refreshes every ~60s while the plugin
runs - see that class's javadoc). This means no account hash ever needs to
be found or passed in manually: enable the plugin, log in, and within one
scan interval this worker starts watching that account. An account whose
heartbeat goes stale (no refresh within STALE_THRESHOLD_SECONDS - the
plugin closed, crashed, or was disabled) has its InferenceWorker stopped on
the next scan. --account-hash is still available to force-watch one
specific account without waiting on discovery (e.g. for testing against a
hash you already know), skipping the scan entirely.

Usage
------
    python inference_worker.py                        # auto-discover accounts via presence
    python inference_worker.py --account-hash 123456789   # watch exactly one account, no discovery
    python inference_worker.py --checkpoint ../models/ppo/checkpoints/ppo_200000.pth
"""

from __future__ import annotations

import argparse
import json
import logging
import pathlib
import signal
import sys
import threading
import time
from dataclasses import dataclass

import gymnasium as gym
import numpy as np
import torch
from google.cloud import firestore
from google.cloud.firestore_v1.watch import DocumentChange  # noqa: F401  (type reference only, not used directly)
from gymnasium import spaces
from stable_baselines3 import PPO

# Import directly from env.py/features.py rather than reimplementing the
# observation math - per the task's explicit instruction to reuse these, not
# duplicate them. This only works when inference_worker.py is run from within
# data/ppo/ (or that directory is otherwise on sys.path), same constraint
# train.py/market_data.py already have (they do `from env import GEMarketEnv`,
# `from features import ...` as bare same-package imports, not package-relative
# ones) - matched here for consistency rather than introducing a different
# import style just for this file.
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from env import (  # noqa: E402
    ACTION_NAMES,
    BUY_LARGE,
    BUY_MEDIUM,
    BUY_PRICE_OFFSET_FRAC,
    BUY_SIZE_FRACTIONS,
    BUY_SMALL,
    HOLD,
    NUM_ACTIONS,
    SELL_100,
    SELL_25,
    SELL_50,
    SELL_PRICE_OFFSET_FRAC,
    SELL_SIZE_FRACTIONS,
    _normalize_market_features,
)
from features import MARKET_FEATURE_COLUMNS  # noqa: E402

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("ppo.inference_worker")

MODELS_DIR = pathlib.Path(__file__).parent.parent / "models" / "ppo"
DEFAULT_CHECKPOINT_PATH = MODELS_DIR / "best.pth"
DEFAULT_SIDECAR_PATH = MODELS_DIR / "best.json"
DEFAULT_SERVICE_ACCOUNT_PATH = pathlib.Path(__file__).parent.parent.parent / "ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json"

# Same net_arch as train.py's PPO(...) construction - must match exactly for
# load_state_dict to succeed (a state_dict is just a dict of tensors keyed by
# module path; a mismatched net_arch produces different module shapes/names
# and load_state_dict raises immediately, so this is a hard requirement, not a
# tuning knob for this worker).
POLICY_KWARGS = dict(net_arch=dict(pi=[256, 128, 128], vf=[256, 128, 128]))

# How many per-item state fields env.py's observation appends after the market
# features, and how many global scalars after that - mirrors
# GEMarketEnv._n_item_state /._n_global_state exactly (env.py doesn't export
# these as module-level constants, so they're re-declared here; if env.py's
# observation shape ever changes, this worker's parsing must change with it -
# flagged here as the one real duplication point between this file and env.py).
N_ITEM_STATE_FIELDS = 4
N_GLOBAL_STATE_FIELDS = 2
OBS_DIM = len(MARKET_FEATURE_COLUMNS) + N_ITEM_STATE_FIELDS + N_GLOBAL_STATE_FIELDS

# Action tiers requiring a computed quantity/price (mirrors env.py's action
# constants) - HOLD needs neither.
_BUY_ACTIONS = (BUY_SMALL, BUY_MEDIUM, BUY_LARGE)
_SELL_ACTIONS = (SELL_25, SELL_50, SELL_100)


def get_git_commit() -> str:
    """Best-effort read of the running worker's own commit, for logging only
    (checkpointVersion in the response doc comes from the checkpoint's own
    sidecar JSON, not this)."""
    try:
        import subprocess
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=pathlib.Path(__file__).parent, text=True
        ).strip()
    except Exception as e:
        return f"unknown ({e})"


@dataclass
class CheckpointVersion:
    step: int
    git_commit: str

    @classmethod
    def load(cls, checkpoint_path: pathlib.Path) -> "CheckpointVersion":
        """Reads the sidecar JSON next to a checkpoint (same convention
        train.py's CheckpointAndEvalCallback writes: <name>.json alongside
        <name>.pth, or best.json alongside best.pth). Falls back to
        step=-1/git_commit=unknown if no sidecar is found or it's malformed -
        never blocks startup on missing metadata, since the checkpoint itself
        is still perfectly loadable without it."""
        sidecar_path = checkpoint_path.with_suffix(".json")
        if not sidecar_path.exists():
            log.warning("No sidecar JSON found at %s - checkpointVersion will be reported as unknown.", sidecar_path)
            return cls(step=-1, git_commit="unknown")
        try:
            data = json.loads(sidecar_path.read_text())
            return cls(step=int(data.get("step", -1)), git_commit=str(data.get("git_commit", "unknown")))
        except Exception as e:
            log.warning("Failed to parse sidecar JSON at %s (%s) - checkpointVersion will be reported as unknown.", sidecar_path, e)
            return cls(step=-1, git_commit="unknown")

    def as_string(self) -> str:
        return f"step={self.step};commit={self.git_commit}"


class DummySingleStepEnv(gym.Env):
    """A tiny stand-in Gymnasium env, just enough for PPO('MlpPolicy', env, ...)'s
    constructor to introspect observation_space/action_space - the real GEMarketEnv
    depends on a loaded MarketDataset (parquet files), which this inference-only
    process has no need to load at all (it only ever calls model.predict on
    observations built from Firestore request documents, never env.step). Kept
    local to this file rather than importing GEMarketEnv specifically so this
    worker never has a hard dependency on data/raw/*.parquet being present on
    the inference machine. Must actually subclass gym.Env (not just duck-type
    its interface) - SB3's _patch_env does an isinstance(env, gym.Env) check and
    raises if that fails, confirmed empirically while building this worker.
    """

    metadata = {"render_modes": []}

    def __init__(self):
        super().__init__()
        self.observation_space = spaces.Box(low=-np.inf, high=np.inf, shape=(OBS_DIM,), dtype=np.float32)
        self.action_space = spaces.Discrete(NUM_ACTIONS)

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        return np.zeros(OBS_DIM, dtype=np.float32), {}

    def step(self, action):
        return np.zeros(OBS_DIM, dtype=np.float32), 0.0, False, True, {}


def load_policy(checkpoint_path: pathlib.Path) -> PPO:
    """Loads a trained PPO policy from a raw `policy.state_dict()` .pth file (NOT
    an SB3 .zip) - the exact pattern verified working for this project: construct
    a fresh PPO with the same net_arch used at training time, then load the
    state dict onto its .policy. Raises if the file is missing or the state dict
    doesn't match the constructed policy's shape (a real error - it means this
    worker's POLICY_KWARGS have drifted from whatever trained the checkpoint)."""
    if not checkpoint_path.exists():
        raise FileNotFoundError(f"Checkpoint not found: {checkpoint_path}")

    env = DummySingleStepEnv()
    model = PPO("MlpPolicy", env, policy_kwargs=POLICY_KWARGS, verbose=0)
    state_dict = torch.load(str(checkpoint_path), map_location="cpu")
    model.policy.load_state_dict(state_dict)
    model.policy.eval()
    return model


# ---------------------------------------------------------------------------
# Observation construction from a decision/request "items" array
# ---------------------------------------------------------------------------
#
# Each element of `items` (see PROPOSAL.md section 4's schema) is expected to
# carry the same feature set env.py's per-item observation packs into one row:
#   - the 13 MARKET_FEATURE_COLUMNS (raw, pre-normalization - normalization
#     happens here, matching env.py's _build_observation, not on the Java side)
#   - positionSizeNorm, unrealizedPct, holdingDuration, limitHeadroomUsed
#     (env.py's 4 item-state fields, already normalized the same way env.py
#     computes them - see PPOFlipperStarScript's DECIDE-phase comments for how
#     the Java side approximates these from its own managers)
#   - availableGpNorm, freeSlotsNorm (env.py's 2 global-state fields, one pair
#     shared across every item in the request, same as env.py appending the
#     same two global scalars onto every per-item observation row)
#   - midPrice (needed here, not part of env.py's observation itself, purely so
#     this worker can reproduce _normalize_market_features's mid-price-relative
#     rescaling of mean_price_*/volatility_* exactly as env.py does it)
#   - itemId (for echoing back in the response, not part of the observation)


class MalformedItemError(ValueError):
    """Raised for one item's request payload that can't be turned into a valid
    observation - callers catch this per-item so one bad item doesn't take down
    the whole tick's response."""


def _build_observation_row(item: dict) -> np.ndarray:
    """Reconstructs exactly the observation vector GEMarketEnv._build_observation
    would have produced for this item at this tick, from the plain-JSON fields
    the plugin sends. Raises MalformedItemError (never lets a KeyError/TypeError
    propagate raw) if a required field is missing or of the wrong shape."""
    try:
        raw_market = np.array(
            [float(item["marketFeatures"][col]) for col in MARKET_FEATURE_COLUMNS],
            dtype=np.float32,
        )
        mid_price = float(item["midPrice"])
        item_state = np.array(
            [
                float(item["positionSizeNorm"]),
                float(item["unrealizedPct"]),
                float(item["holdingDuration"]),
                float(item["limitHeadroomUsed"]),
            ],
            dtype=np.float32,
        )
        global_state = np.array(
            [float(item["availableGpNorm"]), float(item["freeSlotsNorm"])],
            dtype=np.float32,
        )
    except (KeyError, TypeError, ValueError) as e:
        raise MalformedItemError(f"missing/invalid field: {e}") from e

    market_feats = _normalize_market_features(raw_market, mid_price)
    obs = np.concatenate([market_feats.astype(np.float32), item_state, global_state])
    return np.nan_to_num(obs, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)


def _action_to_order(action: int, item: dict) -> dict:
    """Turns a discrete action id into the {action, quantity, price} triple the
    response schema wants, mirroring env.py's own sizing/pricing tiers
    (BUY_SIZE_FRACTIONS/SELL_SIZE_FRACTIONS/xxx_PRICE_OFFSET_FRAC) exactly so a
    given action id means the same real-world order shape here as it did during
    training/backtesting. Quantity/price are computed from fields the plugin
    already includes in the request (avgLowPrice/avgHighPrice/buyLimit/
    limitHeadroomQty/heldQuantity) - see PPOFlipperStarScript's request-building
    code for where these come from on the Java side.
    """
    action_name = ACTION_NAMES[action]
    if action == HOLD:
        return {"action": action_name, "quantity": 0, "price": 0}

    avg_low = float(item.get("avgLowPrice", 0.0))
    avg_high = float(item.get("avgHighPrice", 0.0))
    spread = max(avg_high - avg_low, 0.0)

    if action in _BUY_ACTIONS:
        headroom = max(int(item.get("limitHeadroomQty", 0)), 0)
        buy_limit = max(int(item.get("buyLimit", 0)), 0)
        desired_qty = max(1, int(round(buy_limit * BUY_SIZE_FRACTIONS[action]))) if buy_limit > 0 else 0
        quantity = min(desired_qty, headroom)
        price = avg_low + BUY_PRICE_OFFSET_FRAC[action] * spread
        return {"action": action_name, "quantity": max(quantity, 0), "price": int(round(price))}

    # SELL tier
    held = max(int(item.get("heldQuantity", 0)), 0)
    quantity = max(1, int(round(held * SELL_SIZE_FRACTIONS[action]))) if held > 0 else 0
    quantity = min(quantity, held)
    price = avg_high - SELL_PRICE_OFFSET_FRAC[action] * spread
    return {"action": action_name, "quantity": max(quantity, 0), "price": int(round(price))}


def run_inference(model: PPO, items: list) -> list[dict]:
    """Runs the policy once per item (per-item forward pass - see PROPOSAL.md
    3.3's "batched... not literally one-at-a-time" note; a straightforward
    per-item loop is used here rather than a hand-rolled batch predict since a
    watchlist-sized batch (single digits to low tens of items) is not a
    bottleneck worth the extra complexity of reaching into SB3's policy
    internals for a manual batched forward pass). Skips (logs, does not raise)
    any item whose payload is malformed rather than failing the whole tick's
    response - matches the task's "malformed/incomplete request: log and skip,
    never crash the listener" requirement.
    """
    actions = []
    for item in items:
        item_id = item.get("itemId")
        try:
            obs = _build_observation_row(item)
        except MalformedItemError as e:
            log.warning("Skipping malformed item %s in decision/request: %s", item_id, e)
            continue

        try:
            action, _ = model.predict(obs, deterministic=True)
            action = int(action)
            # Confidence: the actor head's own probability mass on the chosen
            # action, from a single extra forward pass through the policy's
            # distribution - not something SB3's predict() surfaces directly.
            confidence = _action_confidence(model, obs, action)
        except Exception as e:
            log.warning("Inference failed for item %s, skipping - %s", item_id, e)
            continue

        order = _action_to_order(action, item)
        actions.append({
            "itemId": item_id,
            "action": order["action"],
            "quantity": order["quantity"],
            "price": order["price"],
            "confidence": confidence,
        })
    return actions


def _action_confidence(model: PPO, obs: np.ndarray, action: int) -> float:
    """Categorical action-distribution probability the policy assigned to the
    action actually chosen - a natural, cheap confidence figure for the panel
    to display (see PROPOSAL.md 2.5's "model confidence threshold" config item).
    Best-effort: falls back to 1.0 (i.e. "no useful confidence signal") if SB3's
    internal distribution API is ever unavailable rather than failing the whole
    prediction."""
    try:
        with torch.no_grad():
            obs_tensor = torch.as_tensor(obs, dtype=torch.float32, device=model.policy.device).unsqueeze(0)
            distribution = model.policy.get_distribution(obs_tensor)
            probs = distribution.distribution.probs.squeeze(0)
            return float(probs[action].item())
    except Exception:
        return 1.0


# ---------------------------------------------------------------------------
# Firestore plumbing
# ---------------------------------------------------------------------------

REQUEST_DOC_FIELDS = ("tickId", "items", "writtenAt")


def _validate_request_doc(data: dict) -> tuple[int, list] | None:
    """Basic shape check on a decision/request document snapshot's data before
    doing anything with it. Returns (tickId, items) or None (logged, not
    raised) if the document is missing required top-level fields or `items`
    isn't a list - satisfies the "malformed/incomplete request: log and skip,
    never crash the listener" requirement at the document level (per-item
    validation happens separately in _build_observation_row)."""
    if data is None:
        return None
    tick_id = data.get("tickId")
    items = data.get("items")
    if tick_id is None:
        log.warning("decision/request document missing tickId, skipping this tick.")
        return None
    if not isinstance(items, list):
        log.warning("decision/request document's items field is missing/not a list (tickId=%s), skipping this tick.", tick_id)
        return None
    if not items:
        log.info("decision/request tickId=%s has an empty items array (nothing watchlisted?), writing an empty response.", tick_id)
    return int(tick_id), items


class InferenceWorker:
    """Owns one Firestore snapshot listener on a single account's
    decision/request document, and writes back decision/response. One worker
    per account, per PROPOSAL.md 3.6's "one worker per account, to avoid a
    race" - this class does not attempt to shard/watch multiple accounts
    itself; running more than one account is future work (a launcher process
    spawning one of these per shard), out of scope for this milestone per the
    task instructions."""

    def __init__(self, db: firestore.Client, account_hash: int, model: PPO, checkpoint_version: CheckpointVersion):
        self.db = db
        self.account_hash = account_hash
        self.model = model
        self.checkpoint_version = checkpoint_version
        self.request_ref = (
            db.collection("accounts").document(str(account_hash))
            .collection("decision").document("request")
        )
        self.response_ref = (
            db.collection("accounts").document(str(account_hash))
            .collection("decision").document("response")
        )
        self._last_handled_tick_id: int | None = None
        self._watch = None
        self._stop_event = threading.Event()

    def start(self) -> None:
        log.info(
            "Watching accounts/%s/decision/request (checkpoint %s)",
            self.account_hash, self.checkpoint_version.as_string(),
        )
        self._watch = self.request_ref.on_snapshot(self._on_snapshot)

    def stop(self) -> None:
        if self._watch is not None:
            try:
                self._watch.unsubscribe()
            except Exception as e:
                log.debug("Error unsubscribing Firestore watch (ignored): %s", e)
        self._stop_event.set()

    def wait_forever(self) -> None:
        # on_snapshot runs its callback on a background gRPC thread; this just
        # blocks the main thread until told to stop (Ctrl+C -> SIGINT handler
        # calls stop()), matching the "no request document yet: idle wait"
        # requirement without busy-polling anything.
        while not self._stop_event.is_set():
            time.sleep(0.5)

    def _on_snapshot(self, doc_snapshots, changes, read_time) -> None:
        # on_snapshot delivers doc_snapshots as a list even for a single
        # document watch - always length 0 (doc doesn't exist yet/was
        # deleted) or 1 for a document().on_snapshot() watch.
        try:
            if not doc_snapshots:
                log.debug("No decision/request document yet for account %s (idle wait).", self.account_hash)
                return
            snapshot = doc_snapshots[0]
            if not snapshot.exists:
                log.debug("decision/request document does not exist yet for account %s (idle wait).", self.account_hash)
                return

            data = snapshot.to_dict()
            validated = _validate_request_doc(data)
            if validated is None:
                return
            tick_id, items = validated

            if tick_id == self._last_handled_tick_id:
                # Firestore's watch can redeliver the same snapshot (e.g. on
                # reconnect) - avoid redundant inference/writes for a tick
                # already answered.
                return

            self._handle_tick(tick_id, items)
            self._last_handled_tick_id = tick_id
        except Exception as e:
            # Never let an exception escape the snapshot callback - per the
            # task's "graceful... never crash the listener" requirement, an
            # unexpected error on one tick should not tear down the whole
            # watch (an uncaught exception here would otherwise propagate into
            # the Firestore watch's background thread and silently kill it).
            log.error("Unhandled error processing decision/request snapshot: %s", e, exc_info=True)

    def _handle_tick(self, tick_id: int, items: list) -> None:
        start = time.monotonic()
        actions = run_inference(self.model, items)
        elapsed_ms = (time.monotonic() - start) * 1000.0

        self.response_ref.set({
            "tickId": tick_id,
            "actions": actions,
            "checkpointVersion": self.checkpoint_version.as_string(),
            "answeredAt": firestore.SERVER_TIMESTAMP,
        })
        log.info(
            "Answered tickId=%s for account %s: %d/%d item(s) scored in %.1fms",
            tick_id, self.account_hash, len(actions), len(items), elapsed_ms,
        )


class WorkerSupervisor:
    """Owns a dynamic set of InferenceWorker instances, one per account whose
    accounts/{accountHash}/presence/heartbeat is fresh - see this module's
    "Account discovery" docstring section for the full picture. Periodically
    (SCAN_INTERVAL_SECONDS) lists every document in the top-level `accounts`
    collection, checks each one's presence/heartbeat subdocument's
    lastSeenMillis against STALE_THRESHOLD_SECONDS, and starts/stops
    InferenceWorkers to match: a newly-fresh account gets a worker started,
    an account whose heartbeat has gone stale (plugin closed/crashed/
    disabled) gets its worker stopped and removed.

    One worker per account is maintained here for the same reason
    InferenceWorker itself only ever watches one account
    (PROPOSAL.md 3.6's "one worker per account, to avoid a race") - this
    class doesn't change that invariant, it just automates deciding which
    accounts need one, and manages potentially many of them in a single
    process (this is also the mechanism a future central multi-account
    server would use, per the proposal's eventual goal - this supervisor
    already works that way today, it just usually finds only one account).
    """

    # Slightly more than 2x the plugin's own ~60s heartbeat refresh interval
    # (see PPOFlipperStarFirestoreSync.PRESENCE_HEARTBEAT_INTERVAL_SECONDS) -
    # tolerates one missed heartbeat (a transient network hiccup) without
    # immediately tearing down a worker for an account that's still actually
    # running.
    STALE_THRESHOLD_SECONDS = 150

    # How often to re-scan accounts/* for presence changes. Matches the
    # plugin's own heartbeat cadence closely enough that a newly-started
    # account is picked up within roughly one heartbeat interval, without
    # scanning so often it meaningfully adds to Firestore read volume (this
    # is a small `list documents` call, not a per-account read, so even a
    # fairly tight interval here is cheap).
    SCAN_INTERVAL_SECONDS = 30

    def __init__(self, db: firestore.Client, model: PPO, checkpoint_version: "CheckpointVersion"):
        self.db = db
        self.model = model
        self.checkpoint_version = checkpoint_version
        self._workers: dict[int, InferenceWorker] = {}
        self._stop_event = threading.Event()

    def _fresh_account_hashes(self) -> set[int]:
        """Every account hash under accounts/* whose presence/heartbeat.lastSeenMillis
        is within STALE_THRESHOLD_SECONDS of now. A missing presence/heartbeat doc (an
        account that has other collections - portfolio, watchlist - but was never
        active while running a build with the presence feature, or simply isn't
        running the plugin right now) is treated as not-fresh, not an error."""
        fresh: set[int] = set()
        now_millis = time.time() * 1000.0
        threshold_millis = self.STALE_THRESHOLD_SECONDS * 1000.0

        try:
            account_docs = list(self.db.collection("accounts").list_documents())
        except Exception as e:
            log.warning("Account-discovery scan failed to list accounts/* - %s", e)
            return fresh

        for account_doc_ref in account_docs:
            try:
                account_hash = int(account_doc_ref.id)
            except ValueError:
                continue

            try:
                heartbeat = account_doc_ref.collection("presence").document("heartbeat").get()
            except Exception as e:
                log.debug("Presence read failed for account %s (treating as not-fresh) - %s", account_hash, e)
                continue

            if not heartbeat.exists:
                continue
            last_seen = heartbeat.to_dict().get("lastSeenMillis")
            if last_seen is None:
                continue
            if now_millis - float(last_seen) <= threshold_millis:
                fresh.add(account_hash)

        return fresh

    def _scan_once(self) -> None:
        fresh = self._fresh_account_hashes()
        current = set(self._workers.keys())

        for account_hash in fresh - current:
            log.info("Account %s presence detected, starting inference worker for it.", account_hash)
            worker = InferenceWorker(self.db, account_hash, self.model, self.checkpoint_version)
            worker.start()
            self._workers[account_hash] = worker

        for account_hash in current - fresh:
            log.info("Account %s presence went stale, stopping its inference worker.", account_hash)
            self._workers.pop(account_hash).stop()

    def run_forever(self) -> None:
        while not self._stop_event.is_set():
            try:
                self._scan_once()
            except Exception as e:
                # Never let a scan failure kill the supervisor loop - a transient
                # Firestore error on one scan shouldn't tear down every already-running
                # worker, it should just try again next interval.
                log.error("Account-discovery scan failed unexpectedly: %s", e, exc_info=True)
            self._stop_event.wait(self.SCAN_INTERVAL_SECONDS)

    def stop(self) -> None:
        self._stop_event.set()
        for worker in self._workers.values():
            worker.stop()
        self._workers.clear()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--account-hash", type=int, default=None,
                         help="Watch exactly one account's accounts/{accountHash}/decision/request, "
                              "skipping auto-discovery entirely. Omit (the default) to auto-discover "
                              "every account with a fresh accounts/{accountHash}/presence/heartbeat "
                              "instead - see this module's docstring 'Account discovery' section.")
    parser.add_argument("--checkpoint", type=pathlib.Path, default=DEFAULT_CHECKPOINT_PATH,
                         help=f"Path to a .pth policy state_dict (default: {DEFAULT_CHECKPOINT_PATH})")
    parser.add_argument("--service-account-path", type=pathlib.Path, default=DEFAULT_SERVICE_ACCOUNT_PATH,
                         help=f"Path to the Firebase Admin SDK service account JSON "
                              f"(default: {DEFAULT_SERVICE_ACCOUNT_PATH}). Never logged/printed.")
    args = parser.parse_args()

    if not args.service_account_path.exists():
        log.error("Service account file not found at %s - pass --service-account-path if it's elsewhere.", args.service_account_path)
        sys.exit(1)

    log.info("Loading checkpoint from %s ...", args.checkpoint)
    model = load_policy(args.checkpoint)
    checkpoint_version = CheckpointVersion.load(args.checkpoint)
    log.info("Checkpoint loaded (%s). Worker git commit: %s", checkpoint_version.as_string(), get_git_commit())

    # Never log/print the service account path's contents - only that a file
    # at this path is being used, per the task's explicit instruction.
    db = firestore.Client.from_service_account_json(str(args.service_account_path))

    if args.account_hash is not None:
        log.info("Watching exactly account %s (auto-discovery skipped, --account-hash given).", args.account_hash)
        worker = InferenceWorker(db, args.account_hash, model, checkpoint_version)

        def _handle_sigint(signum, frame):
            log.info("Shutting down (signal %s)...", signum)
            worker.stop()

        signal.signal(signal.SIGINT, _handle_sigint)
        signal.signal(signal.SIGTERM, _handle_sigint)

        worker.start()
        try:
            worker.wait_forever()
        finally:
            worker.stop()
        log.info("Stopped.")
        return

    log.info(
        "No --account-hash given - auto-discovering accounts via presence heartbeat "
        "(scanning every %ss, treating a heartbeat stale after %ss).",
        WorkerSupervisor.SCAN_INTERVAL_SECONDS, WorkerSupervisor.STALE_THRESHOLD_SECONDS,
    )
    supervisor = WorkerSupervisor(db, model, checkpoint_version)

    def _handle_sigint(signum, frame):
        log.info("Shutting down (signal %s)...", signum)
        supervisor.stop()

    signal.signal(signal.SIGINT, _handle_sigint)
    signal.signal(signal.SIGTERM, _handle_sigint)

    try:
        supervisor.run_forever()
    finally:
        supervisor.stop()
    log.info("Stopped.")


if __name__ == "__main__":
    main()
