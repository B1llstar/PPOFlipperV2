"""
GEMarketEnv - a Gymnasium-style market environment that replays real historical
5-minute OSRS Grand Exchange price/volume data (data/raw/5m/) as a simulator for
training a PPO flipping policy. See PROPOSAL.md section 3.2 for the full design
this implements.

Single-agent action space, multi-item per episode
---------------------------------------------------
Stable-Baselines3's PPO (and Gymnasium in general) expects one discrete action
per env.step() call, not a vector of per-item actions. PROPOSAL.md 3.3 describes
"the same network applied per-item... the per-tick decision loop iterating
watchlisted items and calling the network once per item per tick (batched...not
literally one-at-a-time)". To get that shape correct while staying inside a
standard Gymnasium single-agent Env (so SB3's stock MlpPolicy works with zero
custom policy code, per the task's "verify the simplest correct approach"
instruction), this env cycles through its episode's watchlist one item at a time:
each env.step() call is "decide the action for item i at the current market
tick"; after every item in the watchlist has been decided once, the market clock
advances one 5-minute block and the cycle repeats from item 0. This preserves
per-tick, per-item decisions and shared parameters (SB3 trains one policy across
every (item, tick) sample) while keeping the action space Discrete(7) throughout.

Guardrails modeled (mirrors Guardrails.java)
---------------------------------------------
- sell quantity must not exceed what's currently held for that item (position
  tracked per item, per episode - the env has no cross-episode portfolio).
- a buy must not push the rolling-4h buy-limit-window usage over the item's GE
  limit (from item_mapping.parquet's `limit` column).
- at most 8 concurrent open GE offer slots (buy or sell) - a BUY/SELL action
  that would require a 9th open slot is rejected.
- (not modeled: duplicate-buy-in-queue and price-deviation-from-guide-price
  checks - those are queue-management/order-staleness concerns specific to the
  live plugin's async order lifecycle, not applicable to this env's instantaneous
  per-tick fill-or-reject model.)

Fill simulation
-----------------
A submitted buy/sell does not fill instantly at the requested price for the full
requested quantity. Following the same "achievable margin sized by the smaller
of the two volumes" principle build_features.py's compute_forward_label uses
(see that module's docstring), a BUY fills against the current block's
low_price_volume (the volume actually transacted at avg_low_price that block)
and a SELL against high_price_volume, both at that block's realized average
price - not the requested price - and capped at the requested quantity. Any
unfilled remainder is simply not filled (no partial-fill carry-over across
blocks in this first version - see "Known simplifications" below).

Reward
--------
- Realized P&L, net of GE tax, credited exactly once per SELL fill.
- A small time-decay penalty per tick while holding an open position (bag-
  holding disincentive).
- A fixed penalty when an action is rejected by a guardrail (buy-limit exceeded,
  sell more than held, no free GE slots) - teaches the policy to internalize
  guardrails rather than relying on the real Guardrails.check() to bail it out.

Known simplifications (documented, not hidden)
-------------------------------------------------
- No partial-fill carryover: a BUY/SELL either fills (up to the available
  volume) in the tick it's submitted, or the unfilled remainder is dropped -
  the real GE would leave a partially-filled offer open across ticks. Modeling
  a full multi-tick offer lifecycle is a reasonable v2 improvement; this keeps
  the environment simple enough to validate quickly per the task's "validate on
  a short run first" instruction.
- Price offered per action tier is a fixed pct offset from the current spread
  (documented inline below), not learned - matches PROPOSAL.md 3.2's explicit
  "not separately learned at first" choice.
- GE tax: 2% on the sale total, capped at 5,000,000gp per sale. This value was
  NOT found anywhere in the old LightGBM pipeline (build_features.py and
  prepare_training_data.py compute margin with no tax adjustment at all - the
  old supervised label is pre-tax) - the 2%/5,000,000gp-cap figures are the
  real-world OSRS GE tax rule as of when this was written, applied here as a
  fresh assumption, not something carried forward from validated repo code.
"""

from __future__ import annotations

from dataclasses import dataclass

import gymnasium as gym
import numpy as np
from gymnasium import spaces

from features import MARKET_FEATURE_COLUMNS
from market_data import MarketDataset

# Indices of MARKET_FEATURE_COLUMNS that need RL-specific rescaling before being
# fed to the policy network - build_features.py's raw feature definitions are
# reused as-is for consistency with data/processed/features.parquet (see
# features.py's docstring), but mean_price_*/volume_* are raw-magnitude columns
# (gp price levels up to ~10^5-10^6 for expensive items, cumulative volumes up to
# ~10^5-10^6) sitting alongside features already scaled to roughly [-1, 1]
# (spread_pct, momentum_*, volatility_* as a fraction of price). Feeding that
# straight into a freshly-initialized MLP badly destabilizes PPO in practice -
# confirmed empirically during this task's short validation run, where the
# unnormalized observation caused the policy to collapse to a single constant
# action within 50k steps (see the env module's "Known simplifications" note).
# mean_price_* is rescaled to a ratio against the current mid-price (scale-free,
# centered near 1.0); volume_* is log1p-transformed (volumes span orders of
# magnitude across items, log-compresses that range without losing relative
# signal). volatility_*/momentum_*/spread_pct are already unit-free ratios from
# build_features.py and are left untouched.
_MEAN_PRICE_INDICES = [MARKET_FEATURE_COLUMNS.index(f"mean_price_{w}") for w in ("1h", "6h", "24h")]
_VOLATILITY_INDICES = [MARKET_FEATURE_COLUMNS.index(f"volatility_{w}") for w in ("1h", "6h", "24h")]
_VOLUME_INDICES = [MARKET_FEATURE_COLUMNS.index(f"volume_{w}") for w in ("1h", "6h", "24h")]


def _normalize_market_features(raw_feats: np.ndarray, mid_price: float) -> np.ndarray:
    feats = raw_feats.copy()
    safe_mid = max(mid_price, 1e-6)
    for idx in _MEAN_PRICE_INDICES:
        feats[idx] = feats[idx] / safe_mid - 1.0
    for idx in _VOLATILITY_INDICES:
        # volatility_* is a raw gp standard deviation (build_features.py computes
        # it on mid_price directly, not as a pct) - same raw-magnitude problem as
        # mean_price_*, rescaled the same way: as a fraction of current price.
        feats[idx] = feats[idx] / safe_mid
    for idx in _VOLUME_INDICES:
        feats[idx] = np.log1p(max(feats[idx], 0.0))
    return feats

# ---------------------------------------------------------------------------
# Action space
# ---------------------------------------------------------------------------
HOLD, BUY_SMALL, BUY_MEDIUM, BUY_LARGE, SELL_25, SELL_50, SELL_100 = range(7)
ACTION_NAMES = ["HOLD", "BUY_SMALL", "BUY_MEDIUM", "BUY_LARGE", "SELL_25%", "SELL_50%", "SELL_100%"]
NUM_ACTIONS = 7

# Buy sizing tiers, as a fraction of CURRENT AVAILABLE GP (self._gp at the time of the
# action) - deliberately NOT a fraction of the item's buy limit, which was the
# original design and is now known to be a real bias, not a neutral choice.
#
# Why this changed: sizing off buy limit means desired_qty for a cheap, high-limit
# staple (e.g. Flax, limit 13,000) is enormous in absolute terms next to a low-limit
# expensive item (e.g. a rare herb or high-value equipment, limit in the hundreds) at
# the identical tier - and since reward is raw realized P&L, a policy trained this way
# earns more apparent reward per action from high-limit cheap items purely through
# volume, regardless of whether that trade was actually the better one. Confirmed live
# after deployment: the trained policy repeatedly proposed the same handful of cheap,
# high-limit staples and effectively never proposed low-limit, higher-value items,
# even when a live GP-price/margin signal would have favored them - a real reward-
# signal distortion, not a deliberate preference, and not fixable from the live
# plugin side (a client-side cooldown on repeat suggestions only dampens the
# symptom, it can't teach the model to value a trade it was never rewarded for).
#
# Sizing off available GP instead makes "small/medium/large" mean "spend a
# small/medium/large fraction of current capital" - price-agnostic and buy-limit-
# agnostic, so a low-limit expensive item gets a properly-sized (correspondingly
# small unit-count, large gp-value) BUY_SMALL exactly like a cheap item would, instead
# of being structurally sized down to irrelevance by the old formula. Still capped by
# headroom (the real GE buy limit) exactly as before - this only changes the natural
# *target* size before that cap (and the existing cost_if_full-vs-gp affordability
# cap) apply.
BUY_SIZE_FRACTIONS = {BUY_SMALL: 0.02, BUY_MEDIUM: 0.05, BUY_LARGE: 0.10}
SELL_SIZE_FRACTIONS = {SELL_25: 0.25, SELL_50: 0.50, SELL_100: 1.00}

# Price offset per action tier, as a fraction of the current spread, applied
# from the "aggressive" side of the book so larger/urgent orders are more
# likely to actually fill against the historical volume at that tick:
#   BUY offers at avg_low_price + offset * spread   (bidding a bit above the
#     floor to fill faster; larger buys bid more aggressively)
#   SELL offers at avg_high_price - offset * spread  (asking a bit below the
#     ceiling; larger sells concede more to fill faster)
# This is a reasonable, documented choice (not specified exactly by the
# proposal) - small=conservative/cheap price improvement, large=aggressive.
BUY_PRICE_OFFSET_FRAC = {BUY_SMALL: 0.05, BUY_MEDIUM: 0.15, BUY_LARGE: 0.30}
SELL_PRICE_OFFSET_FRAC = {SELL_25: 0.05, SELL_50: 0.15, SELL_100: 0.30}

# ---------------------------------------------------------------------------
# GE mechanics constants
# ---------------------------------------------------------------------------
MAX_GE_SLOTS = 8
# How many 5-minute blocks a filled offer continues to occupy a GE slot for,
# after the tick it was submitted/filled on - since this env's fill model
# resolves a fill's quantity/price instantly within the tick it's submitted
# (see "Known simplifications" above), the slot itself still needs to be held
# for some duration for the 8-slot cap to bind at all; 1 block is a deliberately
# conservative minimum (an offer is never free the instant it fills) documented
# here as a modeling choice, not a measured real-world duration.
OFFER_SLOT_HOLD_BLOCKS = 1
BUY_LIMIT_WINDOW_BLOCKS = 48  # 4 hours / 5 minutes - matches BuyLimitLedger.WINDOW_MILLIS
GE_TAX_RATE = 0.02
GE_TAX_CAP = 5_000_000
GE_TAX_MIN_PRICE = 50  # items priced below this are exempt from tax (real-world GE rule)

# Reward shaping constants (documented, tunable).
#
# GUARDRAIL_VIOLATION_PENALTY was originally set to -1.0, matching the same
# order of magnitude as a typical realized-P&L reward (realized_pnl/1000, often
# in the 0.1-3.0 range for a modest few-hundred-gp flip). Empirically, during
# this task's short validation runs, that made the guardrail penalty a *larger
# and far more consistent* (zero-variance, always exactly -1.0) signal than the
# sparse, noisy trade-P&L reward - PPO's policy collapsed to a single constant
# illegal action within 20-50k steps every time (see the validation section in
# this task's report for the measured before/after: mean validation episode
# reward moved from -4000 to -198 purely from shrinking this constant to -0.05,
# with everything else held fixed). Lowered by 20x so the guardrail penalty
# still teaches the policy to avoid illegal actions without dominating the
# reward landscape and creating this degenerate local optimum.
HOLD_DECAY_PENALTY = -0.0005   # per tick, per unit of GP currently tied up (scaled below)
GUARDRAIL_VIOLATION_PENALTY = -0.05
STARTING_GP = 10_000_000
EPISODE_LENGTH_BLOCKS = 2016   # one simulated week of 5-minute blocks (7*24*12)


@dataclass
class _Position:
    quantity: int = 0
    total_cost: float = 0.0  # cost basis of currently held quantity (gp, pre-tax)
    open_since_block: int = -1

    @property
    def avg_cost(self) -> float:
        return self.total_cost / self.quantity if self.quantity > 0 else 0.0


@dataclass
class _OpenOffer:
    is_buy: bool
    item_id: int
    expires_at_block: int  # the offer occupies a GE slot through this block, inclusive


@dataclass
class _EpisodeStats:
    realized_pnl: float = 0.0
    closed_trades: int = 0
    winning_trades: int = 0
    guardrail_violations: int = 0
    total_reward: float = 0.0


def compute_ge_tax(sale_price_per_unit: float, quantity: int) -> float:
    """OSRS Grand Exchange tax: 2% of the sale total, capped at 5,000,000gp per
    sale, waived entirely for items whose unit price is below 50gp. Not found in
    the old pipeline's label computation (build_features.py/prepare_training_data.py
    compute margin pre-tax) - see this module's docstring for that caveat."""
    if sale_price_per_unit < GE_TAX_MIN_PRICE:
        return 0.0
    gross = sale_price_per_unit * quantity
    tax = np.floor(gross * GE_TAX_RATE)
    return float(min(tax, GE_TAX_CAP))


class GEMarketEnv(gym.Env):
    """Gymnasium environment simulating GE flipping over replayed historical data.

    One env.step() decides the action for exactly one (item, tick) pair; the
    market clock advances once every `len(watchlist)` steps. See module
    docstring for why this shape was chosen over a literal multi-item action
    vector.
    """

    metadata = {"render_modes": []}

    def __init__(
        self,
        dataset: MarketDataset,
        watchlist_size: int = 8,
        episode_length_blocks: int = EPISODE_LENGTH_BLOCKS,
        starting_gp: float = STARTING_GP,
        split: str = "train",
        seed: int | None = None,
    ):
        super().__init__()
        assert split in ("train", "validation")
        self.dataset = dataset
        self.watchlist_size = watchlist_size
        self.episode_length_blocks = episode_length_blocks
        self.starting_gp = starting_gp
        self.split = split

        self._rng = np.random.default_rng(seed)

        n_market_features = len(MARKET_FEATURE_COLUMNS)
        # Per-item observation: market features + agent-specific state (position
        # size, unrealized P&L%, holding duration, buy-limit headroom fraction)
        # + 2 global scalars (available GP, free GE slots) appended once per
        # item-slice so the network sees them at every decision regardless of
        # which item is currently "active".
        self._n_item_state = 4
        self._n_global_state = 2
        obs_dim = n_market_features + self._n_item_state + self._n_global_state
        self.observation_space = spaces.Box(low=-np.inf, high=np.inf, shape=(obs_dim,), dtype=np.float32)
        self.action_space = spaces.Discrete(NUM_ACTIONS)

        # Episode-local state, (re)initialized in reset().
        self._item_ids: list[int] = []
        self._start_idx = 0
        self._cur_block = 0
        self._cursor = 0  # index into self._item_ids, which item is "active" this step
        self._positions: dict[int, _Position] = {}
        self._buy_events: dict[int, list[tuple[int, int]]] = {}  # item_id -> [(block, qty), ...]
        self._open_offers: list[_OpenOffer] = []
        self._gp = starting_gp
        self.stats = _EpisodeStats()

    # ------------------------------------------------------------------
    # Gymnasium API
    # ------------------------------------------------------------------
    def reset(self, *, seed: int | None = None, options: dict | None = None):
        super().reset(seed=seed)
        if seed is not None:
            self._rng = np.random.default_rng(seed)

        ds = self.dataset
        n_items = min(self.watchlist_size, len(ds.items))
        all_ids = ds.item_ids()
        chosen = self._rng.choice(len(all_ids), size=n_items, replace=False)
        self._item_ids = [all_ids[i] for i in chosen]

        if self.split == "train":
            lo, hi = 0, ds.train_end_idx
        else:
            lo, hi = ds.val_start_idx, len(ds.all_timestamps)

        max_start = max(lo, hi - self.episode_length_blocks - 1)
        self._start_idx = int(self._rng.integers(lo, max(lo + 1, max_start + 1)))
        self._cur_block = 0
        self._cursor = 0

        self._positions = {iid: _Position() for iid in self._item_ids}
        self._buy_events = {iid: [] for iid in self._item_ids}
        self._open_offers = []
        self._gp = self.starting_gp
        self.stats = _EpisodeStats()

        obs = self._build_observation(self._item_ids[0])
        return obs, {}

    def step(self, action: int):
        item_id = self._item_ids[self._cursor]
        reward, info = self._apply_action(item_id, int(action))
        self.stats.total_reward += reward

        # Advance cursor; once every item has acted this tick, advance the clock
        # and expire any GE offers whose hold period has elapsed.
        self._cursor += 1
        terminated = False
        truncated = False
        if self._cursor >= len(self._item_ids):
            self._cursor = 0
            self._cur_block += 1
            self._open_offers = [o for o in self._open_offers if o.expires_at_block >= self._cur_block]
            if self._cur_block >= self.episode_length_blocks:
                truncated = True
            elif self._start_idx + self._cur_block >= len(self.dataset.all_timestamps) - 1:
                truncated = True

        next_item = self._item_ids[self._cursor]
        obs = self._build_observation(next_item)
        return obs, reward, terminated, truncated, info

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------
    def _series(self, item_id: int):
        return self.dataset.items[item_id]

    def _t(self) -> int:
        """Absolute index into the shared time grid for the current block."""
        return self._start_idx + self._cur_block

    def _build_observation(self, item_id: int) -> np.ndarray:
        series = self._series(item_id)
        t = min(self._t(), len(series.timestamps) - 1)

        pos = self._positions[item_id]
        mid_price = (series.avg_high_price[t] + series.avg_low_price[t]) / 2.0
        market_feats = _normalize_market_features(series.features[t], mid_price)
        unrealized_pct = 0.0
        if pos.quantity > 0 and pos.avg_cost > 0:
            unrealized_pct = (mid_price - pos.avg_cost) / pos.avg_cost
        # Normalized to [0, 1] against the episode length so this stays scale-free
        # regardless of --episode-length, consistent with the other bounded
        # item-state features (position_size_norm, limit_headroom_used).
        holding_duration = (
            (self._cur_block - pos.open_since_block) / max(self.episode_length_blocks, 1)
            if pos.quantity > 0 else 0.0
        )

        bought_in_window = self._quantity_bought_in_window(item_id)
        limit_headroom_used = bought_in_window / max(series.buy_limit, 1)

        position_size_norm = pos.quantity / max(series.buy_limit, 1)

        item_state = np.array(
            [position_size_norm, unrealized_pct, holding_duration, limit_headroom_used],
            dtype=np.float32,
        )

        free_slots = MAX_GE_SLOTS - len(self._open_offers)
        global_state = np.array(
            [self._gp / self.starting_gp, free_slots / MAX_GE_SLOTS],
            dtype=np.float32,
        )

        obs = np.concatenate([market_feats.astype(np.float32), item_state, global_state])
        return np.nan_to_num(obs, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)

    def _quantity_bought_in_window(self, item_id: int) -> int:
        events = self._buy_events[item_id]
        cutoff = self._cur_block - BUY_LIMIT_WINDOW_BLOCKS
        events[:] = [(b, q) for (b, q) in events if b > cutoff]
        return sum(q for _, q in events)

    def _apply_action(self, item_id: int, action: int) -> tuple[float, dict]:
        series = self._series(item_id)
        t = min(self._t(), len(series.timestamps) - 1)
        pos = self._positions[item_id]
        info: dict = {"action": ACTION_NAMES[action], "item_id": item_id, "guardrail_violation": False}

        if action == HOLD:
            reward = 0.0
            if pos.quantity > 0:
                reward += HOLD_DECAY_PENALTY * (pos.total_cost / 1000.0)
            return reward, info

        if action in BUY_SIZE_FRACTIONS:
            return self._apply_buy(item_id, series, t, pos, action, info)

        return self._apply_sell(item_id, series, t, pos, action, info)

    def _reject(self, info: dict, reason: str) -> tuple[float, dict]:
        info["guardrail_violation"] = True
        info["reject_reason"] = reason
        self.stats.guardrail_violations += 1
        return GUARDRAIL_VIOLATION_PENALTY, info

    def _apply_buy(self, item_id, series, t, pos: _Position, action: int, info: dict) -> tuple[float, dict]:
        if len(self._open_offers) >= MAX_GE_SLOTS:
            return self._reject(info, "no free GE slots")

        limit = series.buy_limit
        already_bought = self._quantity_bought_in_window(item_id)
        headroom = max(limit - already_bought, 0)
        if headroom <= 0:
            return self._reject(info, "buy limit exceeded")

        spread = max(series.avg_high_price[t] - series.avg_low_price[t], 0.0)
        offer_price = series.avg_low_price[t] + BUY_PRICE_OFFSET_FRAC[action] * spread

        # Sized off current available GP, not the item's buy limit - see
        # BUY_SIZE_FRACTIONS' module-level comment for why. Still capped by headroom
        # (the real GE buy limit) exactly as the old formula was.
        gp_budget_for_action = self._gp * BUY_SIZE_FRACTIONS[action]
        desired_qty = max(1, int(gp_budget_for_action // max(offer_price, 1e-6)))
        qty_requested = min(desired_qty, headroom)

        cost_if_full = offer_price * qty_requested
        if cost_if_full > self._gp:
            qty_requested = int(self._gp // max(offer_price, 1e-6))
            if qty_requested <= 0:
                return self._reject(info, "insufficient GP")

        # Fill simulation: capped by this block's realized low_price_volume (the
        # actual traded volume at avg_low_price this tick) - same "smaller of the
        # two volumes" fill-feasibility principle as build_features.py's
        # compute_forward_label, adapted to a single-leg per-step fill.
        available_volume = int(series.low_price_volume[t])
        filled_qty = min(qty_requested, available_volume)
        if filled_qty <= 0:
            # Nothing tradeable at this tick - not a guardrail violation (the
            # action was legal, the market just had no liquidity), a harmless no-op.
            info["filled_qty"] = 0
            return 0.0, info

        fill_price = series.avg_low_price[t]  # filled at the block's realized price, not the offer price
        total_cost = fill_price * filled_qty

        self._gp -= total_cost
        pos.total_cost += total_cost
        if pos.quantity == 0:
            pos.open_since_block = self._cur_block
        pos.quantity += filled_qty

        self._buy_events[item_id].append((self._cur_block, filled_qty))
        self._open_offers.append(_OpenOffer(
            is_buy=True, item_id=item_id,
            expires_at_block=self._cur_block + OFFER_SLOT_HOLD_BLOCKS,
        ))

        info["filled_qty"] = filled_qty
        info["fill_price"] = fill_price
        return 0.0, info

    def _apply_sell(self, item_id, series, t, pos: _Position, action: int, info: dict) -> tuple[float, dict]:
        if pos.quantity <= 0:
            return self._reject(info, "sell with no position held")

        if len(self._open_offers) >= MAX_GE_SLOTS:
            return self._reject(info, "no free GE slots")

        qty_requested = max(1, int(round(pos.quantity * SELL_SIZE_FRACTIONS[action])))
        qty_requested = min(qty_requested, pos.quantity)

        spread = max(series.avg_high_price[t] - series.avg_low_price[t], 0.0)
        offer_price = series.avg_high_price[t] - SELL_PRICE_OFFSET_FRAC[action] * spread

        available_volume = int(series.high_price_volume[t])
        filled_qty = min(qty_requested, available_volume)
        if filled_qty <= 0:
            info["filled_qty"] = 0
            return 0.0, info

        fill_price = series.avg_high_price[t]  # filled at the block's realized price, not the offer price
        gross_proceeds = fill_price * filled_qty
        tax = compute_ge_tax(fill_price, filled_qty)
        net_proceeds = gross_proceeds - tax

        avg_cost_per_unit = pos.avg_cost
        cost_of_sold = avg_cost_per_unit * filled_qty
        realized = net_proceeds - cost_of_sold

        self._gp += net_proceeds
        pos.total_cost -= cost_of_sold
        pos.quantity -= filled_qty
        if pos.quantity <= 0:
            pos.quantity = 0
            pos.total_cost = 0.0
            pos.open_since_block = -1

        self.stats.realized_pnl += realized
        self.stats.closed_trades += 1
        if realized > 0:
            self.stats.winning_trades += 1

        self._open_offers.append(_OpenOffer(
            is_buy=False, item_id=item_id,
            expires_at_block=self._cur_block + OFFER_SLOT_HOLD_BLOCKS,
        ))

        info["filled_qty"] = filled_qty
        info["fill_price"] = fill_price
        info["tax"] = tax
        info["realized_pnl"] = realized

        # Reward is the realized P&L itself (already net of tax), scaled down so
        # it sits in a well-conditioned range for PPO's advantage estimates
        # relative to the other reward terms (hold-decay, guardrail penalty).
        reward = realized / 1000.0
        return reward, info
