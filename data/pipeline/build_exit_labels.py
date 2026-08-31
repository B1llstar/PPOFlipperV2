"""
Builds the exit-model training dataset: for simulated purchase points in each
item's historical timeseries, computes position-aware features (unrealized P&L,
holding duration) plus a forward-looking "value of continuing to hold" label, from
the raw 5-minute candles in data/raw/5m/.

Why this needs simulated purchase points: there's no historical record of what
anyone actually bought and when (GE Star V2's cost-basis ledger only exists going
forward from when it was built). So for each item, several plausible purchase
timestamps are sampled across its history, and for each one, several later
"decision points" are evaluated - "if I bought here and it's now T later, what
does holding further from here look like?"

Label definition: label_hold_return_pct at decision point T is the best achievable
round-trip margin over the next 24 hours if selling at T's price and buying back
in at the best point in the next 288 five-minute blocks - i.e. exactly
build_features.py's compute_forward_label, just called with a 24h horizon and
re-anchored to T's own price rather than treating T as a label subject in the buy
sense. This is deliberately reused, not reimplemented (see the "why reuse, not
recompute per pair" note in compute_exit_rows below) - it's the same
fill-feasibility-aware, non-Python-looped computation that was manually verified
against a brute-force scan on the buy side, called once per item at a longer
horizon rather than once per (purchase, decision) pair.

Row-count design (see data/README.md for the full writeup): naively crossing every
sampled purchase point against every one of the 288 possible decision points per
item would blow this dataset up past what this machine's memory can handle (~300M+
rows, vs. today's proven-working 52M for the buy side). Instead each purchase
point is only evaluated at a small fixed set of decision offsets
(HOLD_CHECK_OFFSETS_BLOCKS - 1h/4h/8h/12h/18h/24h after the simulated purchase),
which gives the model varied coverage of "how does the decision look at different
points in a position's life" without the full cross-product. Purchase points
themselves are sampled every PURCHASE_STRIDE_BLOCKS (2h) rather than every block,
for the same reason.

Usage:
    python build_exit_labels.py
    python build_exit_labels.py --min-rows 500 --purchase-stride-blocks 12
"""

import argparse
import pathlib

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
from tqdm import tqdm

from build_features import (
    RAW_DIR,
    ROLLING_WINDOWS,
    compute_forward_label,
    compute_rolling_features,
    fill_time_gaps,
    load_all_months,
)

OUTPUT_PATH = pathlib.Path(__file__).parent.parent / "processed" / "exit_features.parquet"

FIVE_MINUTES = 300
HORIZON_BLOCKS = 24 * 60 // 5  # 24 hours = 288 blocks - longer than the buy side's
                                 # 4h, since a real position may reasonably be held
                                 # longer while waiting for a good exit.

# Decision points to evaluate after each simulated purchase, in 5-minute blocks:
# 1h, 4h, 8h, 12h, 18h, 24h. A fixed small set rather than every block in range -
# adjacent decision points 5 minutes apart carry almost no new information for a
# tree model, so this trades finer-grained T coverage for a tractable row count.
HOLD_CHECK_OFFSETS_BLOCKS = [12, 48, 96, 144, 216, 288]

# How often to sample a simulated purchase point, in 5-minute blocks (2 hours).
PURCHASE_STRIDE_BLOCKS = 24

ROLLING_FEATURE_COLUMNS = [
    "spread_pct",
    "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
    "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
    "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
]


def compute_exit_rows(
    item_df: pd.DataFrame,
    forward_return_from_here: np.ndarray,
    achievable_qty_from_here: np.ndarray,
    purchase_stride: int,
    offsets: list[int],
) -> pd.DataFrame:
    """For one item's gap-filled, feature-computed timeseries, builds one row per
    surviving (purchase_point, decision_point) pair.

    Why forward_return_from_here is passed in rather than recomputed here: it's
    compute_forward_label's output called once per item at the 24h horizon - i.e.
    "if selling at block T's price, what's the best achievable round-trip margin
    buying back in within the next 24h" - which is exactly label_hold_return_pct at
    T regardless of which purchase point led to T. Recomputing it per (P, T) pair
    would make this O(item_length^2 * horizon) instead of O(item_length * horizon);
    joining it in by index keeps this a cheap vectorized cross-product on top of a
    computation that already happened once.
    """
    n = len(item_df)
    timestamps = item_df["timestamp"].to_numpy()
    price = item_df["avg_low_price"].to_numpy()

    purchase_indices = np.arange(0, n, purchase_stride)
    offsets_arr = np.array(offsets)

    # Cross purchase_indices with offsets to get every (P, T) index pair, as flat
    # arrays - numpy broadcasting, not a Python double loop.
    p_grid, offset_grid = np.meshgrid(purchase_indices, offsets_arr, indexing="ij")
    p_idx = p_grid.ravel()
    t_idx = (p_grid + offset_grid).ravel()

    # Filter out-of-range t_idx before using it to index anything - it can exceed
    # n for purchase points near the end of the series (t_idx = p_idx + offset).
    in_range = t_idx < n
    p_idx = p_idx[in_range]
    t_idx = t_idx[in_range]

    valid = (
        ~np.isnan(price[p_idx])
        & ~np.isnan(price[t_idx])
        & ~np.isnan(forward_return_from_here[t_idx])
    )
    p_idx = p_idx[valid]
    t_idx = t_idx[valid]
    if len(p_idx) == 0:
        return pd.DataFrame()

    entry_price = price[p_idx]
    current_price = price[t_idx]
    holding_duration_hours = (t_idx - p_idx) * FIVE_MINUTES / 3600.0

    rows = {
        "item_id": item_df["item_id"].iloc[0],
        "purchase_timestamp": timestamps[p_idx],
        "decision_timestamp": timestamps[t_idx],
        "holding_duration_hours": holding_duration_hours,
        "entry_price": entry_price,
        "current_price": current_price,
        "unrealized_pnl_pct": (current_price - entry_price) / entry_price,
        "label_achievable_qty": achievable_qty_from_here[t_idx],
        "label_hold_return_pct": forward_return_from_here[t_idx],
    }
    for col in ROLLING_FEATURE_COLUMNS:
        rows[col] = item_df[col].to_numpy()[t_idx]

    return pd.DataFrame(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-rows", type=int, default=200,
                         help="Skip items with fewer than this many traded blocks (default: 200), same meaning as build_features.py")
    parser.add_argument("--purchase-stride-blocks", type=int, default=PURCHASE_STRIDE_BLOCKS,
                         help=f"Sample a simulated purchase point every this many 5-minute blocks (default: {PURCHASE_STRIDE_BLOCKS}, i.e. 2h) - smaller means more rows and finer coverage of purchase timing, at the cost of a larger dataset")
    args = parser.parse_args()

    print("Loading raw data (this holds all months in memory briefly to determine the full timestamp grid)...")
    raw = load_all_months()

    all_timestamps = np.sort(raw["timestamp"].unique())
    print(f"{len(all_timestamps):,} unique timestamps spanning the full dataset")

    trade_counts = raw.groupby("item_id").size()
    eligible_items = trade_counts[trade_counts >= args.min_rows].index.to_numpy()
    print(f"{len(eligible_items):,} of {trade_counts.size:,} items have >= {args.min_rows} traded blocks")

    grouped = {item_id: group for item_id, group in raw.groupby("item_id") if item_id in set(eligible_items)}
    del raw

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    writer: pq.ParquetWriter | None = None
    total_rows = 0

    try:
        for item_id in tqdm(eligible_items, desc="building exit labels"):
            item_df = grouped.pop(item_id)
            item_df = fill_time_gaps(item_df, all_timestamps)
            item_df = compute_rolling_features(item_df)
            forward_return, achievable_qty = compute_forward_label(item_df, horizon_blocks=HORIZON_BLOCKS)

            exit_rows = compute_exit_rows(
                item_df, forward_return, achievable_qty,
                args.purchase_stride_blocks, HOLD_CHECK_OFFSETS_BLOCKS,
            )
            if exit_rows.empty:
                continue

            table = pa.Table.from_pandas(exit_rows, preserve_index=False)
            if writer is None:
                writer = pq.ParquetWriter(OUTPUT_PATH, table.schema)
            writer.write_table(table)
            total_rows += len(exit_rows)
    finally:
        if writer is not None:
            writer.close()

    if writer is None:
        print("No output written - no items had a computable label. Check --min-rows and the input data.")
    else:
        print(f"Wrote {total_rows:,} rows to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
