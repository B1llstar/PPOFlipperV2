"""
Feature engineering for the PPO market environment - adapted from
data/pipeline/build_features.py's compute_rolling_features (read-only reference,
not imported directly since that module lives in a sibling package with its own
CLI entrypoint and RAW_DIR/OUTPUT_PATH globals we don't want to depend on).

Only the horizon-independent rolling feature logic is reused here (spread_pct,
volatility/mean_price/volume/momentum at 1h/6h/24h). The OLD pipeline's
compute_forward_label (a precomputed supervised label - "best achievable margin
in the next 4h") is deliberately NOT reused: the PPO environment computes reward
from realized P&L on actual simulated trades, step by step, not a precomputed
forward-looking label. See PROPOSAL.md section 3.2.

Kept as a separate module (not inlined in env.py) so both env.py and any offline
data-prep/inspection script can import the same feature computation.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

# Rolling windows in 5-minute blocks - identical to build_features.py's
# ROLLING_WINDOWS, kept in lockstep so feature *definitions* stay consistent
# with data already validated elsewhere in this repo (data/processed/features.parquet).
ROLLING_WINDOWS = {
    "1h": 12,
    "6h": 72,
    "24h": 288,
}

# The market-feature columns produced by compute_rolling_features, in a fixed
# order - this order is also the order they appear in the env's per-item
# observation slice, so changing it is an observation-space-breaking change.
MARKET_FEATURE_COLUMNS = [
    "spread_pct",
    "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
    "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
    "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
]


def compute_rolling_features(item_df: pd.DataFrame) -> pd.DataFrame:
    """Computes the horizon-independent rolling spread/volatility/volume/momentum
    features for one item's full timeseries. item_df must already be sorted by
    timestamp and reindexed to a complete, gap-filled 5-minute grid (see
    fill_time_gaps below) - same contract as build_features.py's function of the
    same name, whose logic this mirrors line-for-line so feature values match
    what's already validated in data/processed/features.parquet.
    """
    df = item_df.copy()

    df["spread_pct"] = (df["avg_high_price"] - df["avg_low_price"]) / df["avg_low_price"]

    mid_price = (df["avg_high_price"] + df["avg_low_price"]) / 2
    for label, window in ROLLING_WINDOWS.items():
        df[f"volatility_{label}"] = mid_price.rolling(window, min_periods=max(2, window // 4)).std()
        df[f"mean_price_{label}"] = mid_price.rolling(window, min_periods=1).mean()
        df[f"volume_{label}"] = (
            df["high_price_volume"].rolling(window, min_periods=1).sum()
            + df["low_price_volume"].rolling(window, min_periods=1).sum()
        )

    for label, window in ROLLING_WINDOWS.items():
        df[f"momentum_{label}"] = mid_price.pct_change(periods=window)

    return df


def fill_time_gaps(item_df: pd.DataFrame, all_timestamps: np.ndarray) -> pd.DataFrame:
    """Reindexes one item's rows onto the full set of timestamps seen across the
    whole dataset, so rolling windows operate over uniform 5-minute steps even for
    items that don't trade every block. Missing blocks get NaN prices (forward-
    filled by the caller for price continuity) and zero volume. Mirrors
    build_features.py's function of the same name."""
    df = item_df.set_index("timestamp").reindex(all_timestamps)
    df["item_id"] = item_df["item_id"].iloc[0]
    df["high_price_volume"] = df["high_price_volume"].fillna(0)
    df["low_price_volume"] = df["low_price_volume"].fillna(0)
    # Forward-fill prices across gaps so a missing block doesn't create a fake
    # price discontinuity for volatility/momentum - volume-based features already
    # correctly reflect "nothing traded here" via the zero-fill above, but price
    # has to come from *somewhere* for mid_price/spread_pct to stay finite.
    # Also back-fill: an item's very first blocks on the shared timestamp grid
    # (before its first ever recorded trade) have nothing earlier to forward-fill
    # from, so ffill() alone leaves those leading rows NaN - back-filling from the
    # item's first real price closes that gap. This was found empirically (see
    # market_data.py's load_market_dataset - several real items, e.g. "Bronze
    # arrowtips", had NaN prices surviving all the way into the loaded
    # ItemSeries before this fix) rather than assumed safe.
    df["avg_high_price"] = df["avg_high_price"].ffill().bfill()
    df["avg_low_price"] = df["avg_low_price"].ffill().bfill()
    df.reset_index(inplace=True)
    df.rename(columns={"index": "timestamp"}, inplace=True)
    return df


def clean_market_features(df: pd.DataFrame) -> pd.DataFrame:
    """Replaces inf/-inf (e.g. momentum divide-by-zero on a long-dead item) with
    NaN, then fills remaining NaNs with 0.0 - a neutral "no signal yet" value for
    early rows before a rolling window has enough history. Required before feeding
    features into the observation space since NaN/inf would poison the policy
    network's forward pass."""
    df = df.copy()
    df[MARKET_FEATURE_COLUMNS] = (
        df[MARKET_FEATURE_COLUMNS]
        .replace([np.inf, -np.inf], np.nan)
        .fillna(0.0)
    )
    return df
