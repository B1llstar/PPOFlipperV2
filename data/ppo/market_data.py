"""
Loads data/raw/5m/*.parquet + data/raw/item_mapping.parquet once, computes rolling
features per item (via features.py, adapted from build_features.py), and holds
everything as plain numpy arrays in RAM keyed by item_id - so GEMarketEnv.step()
never touches pandas/parquet at simulation time (per PROPOSAL.md 3.5's "pre-loaded-
into-RAM historical arrays, not re-reading parquet per step").

Split convention: mirrors data/pipeline/prepare_training_data.py's time-based
train/validation split (a trailing window held out as validation, with a gap so
no label/feature window leaks across the boundary) adapted to this repo's actual
6 monthly files (2026-03 .. 2026-08) rather than a fixed day count - PROPOSAL.md
3.4 says "train on months 1-5, validate on month 6", so the split boundary here is
the last calendar month present in data/raw/5m/, with the same kind of gap
prepare_training_data.py uses (bounded by the longest rolling window, 24h) so a
training-episode's window doesn't reach across the boundary into validation data.
"""

from __future__ import annotations

import glob
import pathlib
from dataclasses import dataclass

import numpy as np
import pandas as pd

from features import MARKET_FEATURE_COLUMNS, clean_market_features, compute_rolling_features, fill_time_gaps

RAW_DIR = pathlib.Path(__file__).parent.parent / "raw"

FIVE_MINUTES = 300
# Longest rolling window (24h = 288 blocks) - the gap enforced between train and
# validation splits, same reasoning as prepare_training_data.py's LABEL_HORIZON_SECONDS
# gap (except here it's feature lookback, not a forward label).
FEATURE_LOOKBACK_BLOCKS = 288

# Liquidity bar for which items are even eligible to be sampled into an episode -
# mirrors data/service/main.py's MIN_VOLUME_1H convention (same liquidity bar the
# old pipeline used for its watchlist/candidate filtering), applied here as "median
# volume_1h over the item's history" rather than per-row, since eligibility is
# decided once at load time, not per block.
#
# Lowered from 1000.0 to 250.0 deliberately: at 1000/hr, roughly 85% of all
# tradeable items (measured against the actual raw dataset - ~3,800 of ~4,500)
# never became eligible for training at all, no matter how large --max-items was
# set, which directly excluded genuinely rare/low-liquidity items from ever being
# proposed for a BUY - the model can't learn to "snipe" an item it never once saw
# during training. 250/hr roughly triples the eligible pool (~674 -> ~1,000 items)
# while stopping short of thresholds low enough that the env's fill simulation
# (filled_qty capped by that block's actual traded volume) would mostly produce
# empty fills for the newly-included items - that risks teaching "this item never
# fills, don't bother" rather than real trading skill, so this wasn't dropped all
# the way to zero. A genuinely lower floor is a plausible future experiment, but
# should be evaluated on its own after this change's effect is understood, not
# combined with it blind.
MIN_MEDIAN_VOLUME_1H = 250.0
MIN_MEDIAN_PRICE = 1.0


@dataclass
class ItemSeries:
    item_id: int
    name: str
    buy_limit: int
    timestamps: np.ndarray        # int64, seconds, shape (T,)
    avg_high_price: np.ndarray    # float64, shape (T,)
    avg_low_price: np.ndarray     # float64, shape (T,)
    high_price_volume: np.ndarray  # int64, shape (T,)
    low_price_volume: np.ndarray   # int64, shape (T,)
    features: np.ndarray          # float32, shape (T, len(MARKET_FEATURE_COLUMNS))


@dataclass
class MarketDataset:
    items: dict[int, ItemSeries]
    all_timestamps: np.ndarray  # sorted, shared time grid across every item
    train_end_idx: int          # exclusive upper bound (into all_timestamps) for training episodes
    val_start_idx: int          # inclusive lower bound for validation episodes

    def item_ids(self) -> list[int]:
        return list(self.items.keys())


def _load_raw() -> tuple[pd.DataFrame, pd.DataFrame]:
    files = sorted(glob.glob(str(RAW_DIR / "5m" / "*.parquet")))
    if not files:
        raise FileNotFoundError(f"No monthly parquet files found in {RAW_DIR / '5m'}")
    frames = [pd.read_parquet(f) for f in files]
    raw = pd.concat(frames, ignore_index=True)
    raw.sort_values(["item_id", "timestamp"], inplace=True)
    raw.reset_index(drop=True, inplace=True)

    mapping = pd.read_parquet(RAW_DIR / "item_mapping.parquet")
    return raw, mapping


def load_market_dataset(
    max_items: int | None = None,
    min_rows: int = 500,
    validation_days: int = 30,
) -> MarketDataset:
    """Loads and preprocesses the full 6-month dataset into RAM.

    max_items: cap the number of eligible items loaded (useful for fast iteration/
    the short validation run this task requires - loading all ~4000 items and
    computing rolling features for each takes several minutes).
    min_rows: skip items with too little history to compute meaningful rolling
    features (same convention as build_features.py's --min-rows).
    validation_days: trailing days held out as validation - default 30 approximates
    "the last of the 6 months" per PROPOSAL.md 3.4, using a day count (like
    prepare_training_data.py) rather than a hardcoded calendar month so it stays
    correct if more/less than exactly 6 months of data is ever present.
    """
    raw, mapping = _load_raw()

    all_timestamps = np.sort(raw["timestamp"].unique())
    print(f"{len(all_timestamps):,} unique timestamps spanning the full dataset")

    max_ts = int(all_timestamps.max())
    validation_start_ts = max_ts - validation_days * 24 * 60 * 60
    train_end_ts = validation_start_ts - FEATURE_LOOKBACK_BLOCKS * FIVE_MINUTES

    trade_counts = raw.groupby("item_id").size()
    eligible_ids = trade_counts[trade_counts >= min_rows].index.to_numpy()

    limit_by_id = mapping.set_index("item_id")["limit"].to_dict()
    name_by_id = mapping.set_index("item_id")["name"].to_dict()

    grouped = {item_id: g for item_id, g in raw.groupby("item_id") if item_id in set(eligible_ids)}
    del raw

    items: dict[int, ItemSeries] = {}
    for item_id in eligible_ids:
        item_df = grouped.pop(item_id, None)
        if item_df is None:
            continue

        item_df = fill_time_gaps(item_df, all_timestamps)
        item_df = compute_rolling_features(item_df)
        item_df = clean_market_features(item_df)

        med_price = float(np.nanmedian(item_df["avg_low_price"]))
        med_vol_1h = float(item_df["volume_1h"].median())
        if not np.isfinite(med_price) or med_price < MIN_MEDIAN_PRICE:
            continue
        if med_vol_1h < MIN_MEDIAN_VOLUME_1H:
            continue

        # Defensive final check: fill_time_gaps' ffill+bfill should leave no NaN
        # prices, but an item with literally zero real trades in this dataset
        # (all-NaN before any fill) would still slip through - skip it rather
        # than load a series whose price arrays can produce NaN cost-basis math
        # mid-episode.
        if not np.isfinite(item_df["avg_low_price"]).all() or not np.isfinite(item_df["avg_high_price"]).all():
            continue

        buy_limit = limit_by_id.get(item_id, np.nan)
        if not np.isfinite(buy_limit) or buy_limit <= 0:
            # No usable GE limit data for this item - skip it rather than
            # silently allowing unlimited buys the real GE would reject.
            continue

        items[int(item_id)] = ItemSeries(
            item_id=int(item_id),
            name=str(name_by_id.get(item_id, f"item_{item_id}")),
            buy_limit=int(buy_limit),
            timestamps=item_df["timestamp"].to_numpy(dtype=np.int64),
            avg_high_price=item_df["avg_high_price"].to_numpy(dtype=np.float64),
            avg_low_price=item_df["avg_low_price"].to_numpy(dtype=np.float64),
            high_price_volume=item_df["high_price_volume"].to_numpy(dtype=np.int64),
            low_price_volume=item_df["low_price_volume"].to_numpy(dtype=np.int64),
            features=item_df[MARKET_FEATURE_COLUMNS].to_numpy(dtype=np.float32),
        )

        if max_items is not None and len(items) >= max_items:
            break

    if not items:
        raise RuntimeError("No items passed liquidity/limit filtering - check min_rows/liquidity thresholds")

    train_end_idx = int(np.searchsorted(all_timestamps, train_end_ts, side="right"))
    val_start_idx = int(np.searchsorted(all_timestamps, validation_start_ts, side="left"))
    train_end_idx = max(train_end_idx, FEATURE_LOOKBACK_BLOCKS + 1)
    val_start_idx = min(max(val_start_idx, train_end_idx), len(all_timestamps) - 1)

    print(f"Loaded {len(items):,} eligible items "
          f"(train blocks: 0..{train_end_idx:,}, validation blocks: {val_start_idx:,}..{len(all_timestamps):,})")

    return MarketDataset(
        items=items,
        all_timestamps=all_timestamps,
        train_end_idx=train_end_idx,
        val_start_idx=val_start_idx,
    )
