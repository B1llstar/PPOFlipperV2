"""
Builds the training dataset: per-item, per-5-minute-block features (spread,
volatility, volume, momentum) and a realistic forward-looking label (achievable
round-trip margin over the next 4 hours), from the raw 5-minute candles in
data/raw/5m/.

Processes one item's full history at a time (median ~1800 rows/month, worst case
tens of thousands across 6 months) rather than holding the whole cross-item dataset
in memory - the raw data is ~4GB across 6 months uncompressed, more than this
machine's 8.6GB RAM can comfortably hold at once alongside pandas' working memory
for rolling-window computation. Output is written incrementally, one item's rows
appended per iteration.

Label definition (see data/README.md for the full rationale): buy at this block's
avg_low_price sized by its low_price_volume, look forward up to 4 hours for the
block that maximizes (avg_high_price sized by its high_price_volume) net profit,
using the smaller of the two volumes as the achievable trade size. This is a
concrete, fill-feasibility-aware label - not just "best price seen in the future
window," which would credit margins that were never actually tradeable at size.

Usage:
    python build_features.py                    # all months in data/raw/5m/
    python build_features.py --min-rows 500      # skip items with too little history
"""

import argparse
import glob
import pathlib

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
from tqdm import tqdm

RAW_DIR = pathlib.Path(__file__).parent.parent / "raw"
OUTPUT_PATH = pathlib.Path(__file__).parent.parent / "processed" / "features.parquet"

FIVE_MINUTES = 300
HORIZON_BLOCKS = 4 * 60 // 5  # 4 hours = 48 blocks of 5 minutes

# Rolling windows for spread/volatility/volume features, in 5-minute blocks.
ROLLING_WINDOWS = {
    "1h": 12,
    "6h": 72,
    "24h": 288,
}


def load_all_months() -> pd.DataFrame:
    """Loads and concatenates every monthly Parquet file, sorted by (item_id, timestamp)
    so each item's rows are contiguous - required for the per-item grouped processing
    below to see a clean, ordered timeseries per item."""
    files = sorted(glob.glob(str(RAW_DIR / "5m" / "*.parquet")))
    if not files:
        raise FileNotFoundError(f"No monthly Parquet files found in {RAW_DIR / '5m'}")

    frames = [pd.read_parquet(f) for f in tqdm(files, desc="loading months")]
    df = pd.concat(frames, ignore_index=True)
    del frames
    df.sort_values(["item_id", "timestamp"], inplace=True)
    df.reset_index(drop=True, inplace=True)
    return df


def compute_item_features(item_df: pd.DataFrame) -> pd.DataFrame:
    """Computes features + the buy-side label for one item's full timeseries.
    item_df must already be sorted by timestamp and reindexed to a complete,
    gap-filled 5-minute grid (see fill_time_gaps) so rolling windows and the
    forward-looking label both operate over uniform time steps.

    Thin wrapper kept for backward compatibility: compute_rolling_features and
    compute_forward_label are the actual building blocks, split apart so
    build_exit_labels.py can reuse the rolling features with a different label
    horizon (24h instead of this module's 4h) without recomputing or duplicating
    the rolling-window logic."""
    df = compute_rolling_features(item_df)
    df["label_margin_pct"], df["label_achievable_qty"] = compute_forward_label(df)
    return df


def compute_rolling_features(item_df: pd.DataFrame) -> pd.DataFrame:
    """Computes the horizon-independent rolling spread/volatility/volume/momentum
    features for one item's full timeseries. item_df must already be sorted by
    timestamp and reindexed to a complete, gap-filled 5-minute grid (see
    fill_time_gaps)."""
    df = item_df.copy()

    # Current spread: the raw margin opportunity at this block, before considering
    # whether it'll still be there once both legs of a real trade fill.
    df["spread_pct"] = (df["avg_high_price"] - df["avg_low_price"]) / df["avg_low_price"]

    mid_price = (df["avg_high_price"] + df["avg_low_price"]) / 2
    for label, window in ROLLING_WINDOWS.items():
        df[f"volatility_{label}"] = mid_price.rolling(window, min_periods=max(2, window // 4)).std()
        df[f"mean_price_{label}"] = mid_price.rolling(window, min_periods=1).mean()
        df[f"volume_{label}"] = (
            df["high_price_volume"].rolling(window, min_periods=1).sum()
            + df["low_price_volume"].rolling(window, min_periods=1).sum()
        )

    # Momentum: how the price has moved over each window, as a fraction - positive
    # means trending up, which matters differently for a buy-low-sell-high flip
    # than a stable or declining item does.
    for label, window in ROLLING_WINDOWS.items():
        df[f"momentum_{label}"] = mid_price.pct_change(periods=window)

    return df


def compute_forward_label(df: pd.DataFrame, horizon_blocks: int = HORIZON_BLOCKS) -> tuple[np.ndarray, np.ndarray]:
    """For each row, buys at that block's avg_low_price (sized by low_price_volume)
    and finds the best net-profit sell within the next horizon_blocks blocks, sized
    by the smaller of the buy/sell volumes at those two points. Returns
    (margin_pct, achievable_qty) arrays aligned to df's index.

    Vectorized via a rolling-max-of-a-derived-series trick rather than a Python
    loop per row: for each possible forward offset k, compute what the net margin
    *would* be if selling k blocks ahead, then take the max across all k in range
    for each starting row. This is O(rows * horizon) but as numpy array ops, not
    Python-level iteration - fast enough for a few thousand rows per item.

    horizon_blocks defaults to this module's 4h buy-side horizon; build_exit_labels.py
    calls this with horizon_blocks=288 (24h) to get each row's "best forward return
    from here," which it then re-anchors to simulated purchase points rather than
    treating this row's own price as the entry price - see that module for why the
    same function serves both purposes.
    """
    n = len(df)
    buy_price = df["avg_low_price"].to_numpy()
    buy_volume = df["low_price_volume"].to_numpy()
    sell_price = df["avg_high_price"].to_numpy()
    sell_volume = df["high_price_volume"].to_numpy()

    best_margin = np.full(n, np.nan)
    best_qty = np.zeros(n)

    for k in range(1, horizon_blocks + 1):
        shifted_sell_price = np.roll(sell_price, -k)
        shifted_sell_volume = np.roll(sell_volume, -k)
        # Rows within k of the end have no valid future block at offset k - roll()
        # wraps around, so explicitly invalidate those instead of using wrapped data.
        valid = np.arange(n) < (n - k)

        with np.errstate(invalid="ignore", divide="ignore"):
            margin = (shifted_sell_price - buy_price) / buy_price
        qty = np.minimum(buy_volume, shifted_sell_volume)

        candidate_margin = np.where(valid & (qty > 0), margin, np.nan)

        better = np.where(
            np.isnan(best_margin),
            True,
            np.nan_to_num(candidate_margin, nan=-np.inf) > np.nan_to_num(best_margin, nan=-np.inf),
        )
        best_margin = np.where(better, candidate_margin, best_margin)
        best_qty = np.where(better & ~np.isnan(candidate_margin), qty, best_qty)

    return best_margin, best_qty


def fill_time_gaps(item_df: pd.DataFrame, all_timestamps: np.ndarray) -> pd.DataFrame:
    """Reindexes one item's rows onto the full set of timestamps seen across the
    whole dataset, so rolling windows and the forward label operate over uniform
    5-minute steps even for items that don't trade every block (median item has
    gaps - see build_features.py's docstring). Missing blocks get NaN prices and
    zero volume, which the label computation already treats as "no valid trade
    here" via the qty > 0 check."""
    df = item_df.set_index("timestamp").reindex(all_timestamps)
    df["item_id"] = item_df["item_id"].iloc[0]
    df["high_price_volume"] = df["high_price_volume"].fillna(0)
    df["low_price_volume"] = df["low_price_volume"].fillna(0)
    df.reset_index(inplace=True)
    df.rename(columns={"index": "timestamp"}, inplace=True)
    return df


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-rows", type=int, default=200,
                         help="Skip items with fewer than this many traded blocks (default: 200) - too little history to compute meaningful rolling features")
    args = parser.parse_args()

    print("Loading raw data (this holds all months in memory briefly to determine the full timestamp grid)...")
    raw = load_all_months()

    all_timestamps = np.sort(raw["timestamp"].unique())
    print(f"{len(all_timestamps):,} unique timestamps spanning the full dataset")

    trade_counts = raw.groupby("item_id").size()
    eligible_items = trade_counts[trade_counts >= args.min_rows].index.to_numpy()
    print(f"{len(eligible_items):,} of {trade_counts.size:,} items have >= {args.min_rows} traded blocks")

    # Group once up front, then release the full concatenated frame - each group's
    # slice is small and independent, so processing can proceed one item at a time
    # without the whole dataset resident in memory for the rest of the run.
    grouped = {item_id: group for item_id, group in raw.groupby("item_id") if item_id in set(eligible_items)}
    del raw

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    writer: pq.ParquetWriter | None = None

    try:
        for item_id in tqdm(eligible_items, desc="building features"):
            item_df = grouped.pop(item_id)
            item_df = fill_time_gaps(item_df, all_timestamps)
            item_df = compute_item_features(item_df)

            # Rows with no computable label (near the end of the dataset, or no
            # valid future trade within the horizon) aren't useful training
            # examples - drop them here rather than carrying NaN labels forward.
            item_df = item_df[item_df["label_margin_pct"].notna()]
            if item_df.empty:
                continue

            table = pa.Table.from_pandas(item_df, preserve_index=False)
            if writer is None:
                writer = pq.ParquetWriter(OUTPUT_PATH, table.schema)
            writer.write_table(table)
    finally:
        if writer is not None:
            writer.close()

    if writer is None:
        print("No output written - no items had a computable label. Check --min-rows and the input data.")
    else:
        print(f"Wrote features to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
