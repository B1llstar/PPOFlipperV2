"""
Turns the raw computed exit features/labels in data/processed/exit_features.parquet
into an actual training-ready dataset: liquidity filtering, outlier clipping, and a
time-based train/validation split. Mirrors prepare_training_data.py's design
exactly (see that module's docstring for the full memory-design rationale) with
two differences: the label horizon is 24h (matching build_exit_labels.py's
HORIZON_BLOCKS) instead of 4h, and the feature set includes the two
position-aware features (unrealized_pnl_pct, holding_duration_hours) alongside
the same 13 rolling features the buy-side model uses.

The train/validation split is keyed on decision_timestamp (T), not
purchase_timestamp (P) - a row's label looks forward up to 24h from T, so T is
what must not straddle the split gap; P is just where the simulated position
started and carries no forward-looking information of its own.

Usage:
    python prepare_exit_training_data.py
    python prepare_exit_training_data.py --min-price 10 --min-qty 5 --min-volume-1h 5000 --clip-percentile 0.995
"""

import argparse
import pathlib

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq
from tqdm import tqdm

FEATURES_PATH = pathlib.Path(__file__).parent.parent / "processed" / "exit_features.parquet"
OUTPUT_DIR = pathlib.Path(__file__).parent.parent / "processed"

# Matches build_exit_labels.py's HORIZON_BLOCKS (24h) - the split gap must be at
# least this wide so no training row's label window (which looks forward from its
# decision_timestamp) overlaps into the validation period.
LABEL_HORIZON_SECONDS = 24 * 60 * 60

FEATURE_COLUMNS = [
    "spread_pct",
    "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
    "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
    "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
    "unrealized_pnl_pct", "holding_duration_hours",
]
LABEL_COLUMN = "label_hold_return_pct"
ID_COLUMNS = ["item_id", "purchase_timestamp", "decision_timestamp"]
ALL_COLUMNS = ID_COLUMNS + ["current_price", "label_achievable_qty"] + FEATURE_COLUMNS + [LABEL_COLUMN]

BATCH_SIZE = 50_000


def compute_clip_thresholds(min_price: float, min_qty: float, min_volume_1h: float, percentile: float) -> tuple[float, float, int, int]:
    """Pass 1: streams the file collecting only label_hold_return_pct for rows
    that pass the liquidity filter, to compute clip thresholds without holding any
    other column in memory. Returns (floor, cap, kept_rows, total_rows)."""
    pf = pq.ParquetFile(FEATURES_PATH)
    total_rows = pf.metadata.num_rows

    labels = []
    kept_rows = 0
    for batch in tqdm(
        pf.iter_batches(batch_size=BATCH_SIZE, columns=["current_price", "label_achievable_qty", "volume_1h", LABEL_COLUMN]),
        desc="pass 1: scanning for clip thresholds",
        total=(total_rows // BATCH_SIZE) + 1,
    ):
        df = batch.to_pandas()
        mask = (
            (df["current_price"] >= min_price)
            & (df["label_achievable_qty"] >= min_qty)
            & (df["volume_1h"] >= min_volume_1h)
        )
        kept_rows += int(mask.sum())
        labels.append(df.loc[mask, LABEL_COLUMN].to_numpy())

    all_labels = np.concatenate(labels)
    del labels
    cap = float(np.quantile(all_labels, percentile))
    floor = float(np.quantile(all_labels, 1 - percentile))
    del all_labels

    print(f"Liquidity filter (price >= {min_price}, achievable qty >= {min_qty}, "
          f"volume_1h >= {min_volume_1h}): "
          f"{kept_rows:,} / {total_rows:,} rows pass ({100 * kept_rows / total_rows:.1f}%)")
    print(f"Clip thresholds at percentile {percentile}: [{floor:.4f}, {cap:.4f}]")

    return floor, cap, kept_rows, total_rows


def find_split_boundary(validation_days: int) -> tuple[int, int]:
    """Reads just the decision_timestamp column to find the dataset's max
    timestamp and compute the train/validation boundary, without touching any
    other column."""
    pf = pq.ParquetFile(FEATURES_PATH)
    max_ts = 0
    for batch in pf.iter_batches(batch_size=BATCH_SIZE, columns=["decision_timestamp"]):
        batch_max = pc.max(batch.column("decision_timestamp")).as_py()
        max_ts = max(max_ts, batch_max)

    validation_start = max_ts - validation_days * 24 * 60 * 60
    train_end = validation_start - LABEL_HORIZON_SECONDS

    gap_hours = (validation_start - train_end) / 3600
    print(f"Time-based split: train up to {pd.to_datetime(train_end, unit='s')}, "
          f"validation from {pd.to_datetime(validation_start, unit='s')} "
          f"({gap_hours:.1f}h gap between them)")

    return train_end, validation_start


def stream_filter_clip_split(
    min_price: float, min_qty: float, min_volume_1h: float, floor: float, cap: float,
    train_end: int, validation_start: int,
) -> None:
    """Pass 2: streams the file again, applying the liquidity filter + label clip
    + NaN-feature drop to each batch, and writing straight to exit_train.parquet
    or exit_validation.parquet, split by decision_timestamp."""
    pf = pq.ParquetFile(FEATURES_PATH)
    total_rows = pf.metadata.num_rows

    train_writer: pq.ParquetWriter | None = None
    validation_writer: pq.ParquetWriter | None = None
    train_rows = 0
    validation_rows = 0
    output_columns = ID_COLUMNS + FEATURE_COLUMNS + [LABEL_COLUMN]

    try:
        for batch in tqdm(
            pf.iter_batches(batch_size=BATCH_SIZE, columns=ALL_COLUMNS),
            desc="pass 2: filtering, clipping, splitting",
            total=(total_rows // BATCH_SIZE) + 1,
        ):
            df = batch.to_pandas()

            mask = (
                (df["current_price"] >= min_price)
                & (df["label_achievable_qty"] >= min_qty)
                & (df["volume_1h"] >= min_volume_1h)
            )
            df = df.loc[mask]
            if df.empty:
                continue

            df = df.dropna(subset=FEATURE_COLUMNS)
            if df.empty:
                continue

            df[LABEL_COLUMN] = df[LABEL_COLUMN].clip(lower=floor, upper=cap)
            df = df[output_columns]

            train_part = df[df["decision_timestamp"] <= train_end]
            validation_part = df[df["decision_timestamp"] >= validation_start]

            if not train_part.empty:
                table = pa.Table.from_pandas(train_part, preserve_index=False)
                if train_writer is None:
                    train_writer = pq.ParquetWriter(OUTPUT_DIR / "exit_train.parquet", table.schema)
                train_writer.write_table(table)
                train_rows += len(train_part)

            if not validation_part.empty:
                table = pa.Table.from_pandas(validation_part, preserve_index=False)
                if validation_writer is None:
                    validation_writer = pq.ParquetWriter(OUTPUT_DIR / "exit_validation.parquet", table.schema)
                validation_writer.write_table(table)
                validation_rows += len(validation_part)
    finally:
        if train_writer is not None:
            train_writer.close()
        if validation_writer is not None:
            validation_writer.close()

    print(f"Wrote {train_rows:,} rows to {OUTPUT_DIR / 'exit_train.parquet'}")
    print(f"Wrote {validation_rows:,} rows to {OUTPUT_DIR / 'exit_validation.parquet'}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-price", type=float, default=10.0,
                         help="Minimum current_price to keep a row (default: 10gp)")
    parser.add_argument("--min-qty", type=float, default=5.0,
                         help="Minimum label_achievable_qty to keep a row (default: 5)")
    parser.add_argument("--min-volume-1h", type=float, default=5000.0,
                         help="Minimum trailing 1h total trade volume to keep a row (default: 5000) - same liquidity guardrail the buy-side dataset needed (see prepare_training_data.py)")
    parser.add_argument("--clip-percentile", type=float, default=0.995,
                         help="Clip label_hold_return_pct to this percentile and its mirror on the low end (default: 0.995)")
    parser.add_argument("--validation-days", type=int, default=21,
                         help="How many trailing days to hold out as validation (default: 21)")
    args = parser.parse_args()

    if not FEATURES_PATH.exists():
        raise FileNotFoundError(f"{FEATURES_PATH} not found - run build_exit_labels.py first")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    floor, cap, _, _ = compute_clip_thresholds(args.min_price, args.min_qty, args.min_volume_1h, args.clip_percentile)
    train_end, validation_start = find_split_boundary(args.validation_days)
    stream_filter_clip_split(args.min_price, args.min_qty, args.min_volume_1h, floor, cap, train_end, validation_start)


if __name__ == "__main__":
    main()
