"""
Turns the raw computed features/labels in data/processed/features.parquet into an
actual training-ready dataset: liquidity filtering, outlier clipping, and a
time-based train/validation split.

Why this is a separate step from build_features.py: the thresholds here are the
kind of thing you tune repeatedly while developing a model (try a stricter filter,
see if validation error improves; try a different clip percentile), and
build_features.py takes ~3.5 minutes to re-run. Keeping features.parquet as the
full unfiltered computed truth means iterating on these thresholds costs seconds,
not minutes. See data/README.md's "Building features + labels" section for the
data-quality finding this responds to.

Memory design: features.parquet is ~3.3GB and this machine has 8.6GB RAM total,
often with other things already running - a plain pd.read_parquet() of the whole
file plus pandas' working memory for filtering/clipping got this process killed
for memory pressure once already. Instead this streams through the file's row
groups (build_features.py wrote one per item, so there are ~4000 of them) via
pyarrow's iter_batches, in two passes:
  1. Compute the liquidity-filtered label distribution (just one float column,
     tiny) to get clip thresholds, without holding any row's full feature set.
  2. Stream through again, filtering + clipping each batch and routing rows to
     train/validation by timestamp, writing incrementally via ParquetWriter.
Peak memory is bounded by one batch at a time, not the whole dataset.

Usage:
    python prepare_training_data.py
    python prepare_training_data.py --min-price 10 --min-qty 5 --clip-percentile 0.995
"""

import argparse
import pathlib

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq
from tqdm import tqdm

FEATURES_PATH = pathlib.Path(__file__).parent.parent / "processed" / "features.parquet"
OUTPUT_DIR = pathlib.Path(__file__).parent.parent / "processed"

# Forward label horizon from build_features.py (4 hours) - the train/validation
# split needs a gap at least this wide so no training row's label window overlaps
# into the validation period (which would leak future validation-period prices
# into a training example).
LABEL_HORIZON_SECONDS = 4 * 60 * 60

FEATURE_COLUMNS = [
    "spread_pct",
    "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
    "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
    "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
]
LABEL_COLUMN = "label_margin_pct"
ID_COLUMNS = ["item_id", "timestamp"]
ALL_COLUMNS = ID_COLUMNS + ["avg_low_price", "label_achievable_qty"] + FEATURE_COLUMNS + [LABEL_COLUMN]

# Rows per streamed batch - independent of the file's per-item row groups
# (some items have 50k+ rows), so peak memory per batch stays predictable
# regardless of how any one item's row group happens to be sized. Kept small
# (this machine runs with genuinely tight memory - confirmed via `sysctl
# vm.swapusage` showing sustained swap usage even at idle, not just app noise)
# so no single batch is more than a few tens of MB.
BATCH_SIZE = 50_000


def compute_clip_thresholds(min_price: float, min_qty: float, percentile: float) -> tuple[float, float, int, int]:
    """Pass 1: streams the file collecting only label_margin_pct for rows that
    pass the liquidity filter, to compute clip thresholds without holding any
    other column in memory. Returns (floor, cap, kept_rows, total_rows)."""
    pf = pq.ParquetFile(FEATURES_PATH)
    total_rows = pf.metadata.num_rows

    labels = []
    kept_rows = 0
    for batch in tqdm(
        pf.iter_batches(batch_size=BATCH_SIZE, columns=["avg_low_price", "label_achievable_qty", LABEL_COLUMN]),
        desc="pass 1: scanning for clip thresholds",
        total=(total_rows // BATCH_SIZE) + 1,
    ):
        df = batch.to_pandas()
        mask = (df["avg_low_price"] >= min_price) & (df["label_achievable_qty"] >= min_qty)
        kept_rows += int(mask.sum())
        labels.append(df.loc[mask, LABEL_COLUMN].to_numpy())

    all_labels = np.concatenate(labels)
    del labels
    cap = float(np.quantile(all_labels, percentile))
    floor = float(np.quantile(all_labels, 1 - percentile))
    del all_labels

    print(f"Liquidity filter (price >= {min_price}, achievable qty >= {min_qty}): "
          f"{kept_rows:,} / {total_rows:,} rows pass ({100 * kept_rows / total_rows:.1f}%)")
    print(f"Clip thresholds at percentile {percentile}: [{floor:.4f}, {cap:.4f}]")

    return floor, cap, kept_rows, total_rows


def find_split_boundary(validation_days: int) -> tuple[int, int]:
    """Reads just the timestamp column (a few hundred MB at most) to find the
    dataset's max timestamp and compute the train/validation boundary, without
    touching any other column."""
    pf = pq.ParquetFile(FEATURES_PATH)
    max_ts = 0
    for batch in pf.iter_batches(batch_size=BATCH_SIZE, columns=["timestamp"]):
        batch_max = pc.max(batch.column("timestamp")).as_py()
        max_ts = max(max_ts, batch_max)

    validation_start = max_ts - validation_days * 24 * 60 * 60
    train_end = validation_start - LABEL_HORIZON_SECONDS

    gap_hours = (validation_start - train_end) / 3600
    print(f"Time-based split: train up to {pd.to_datetime(train_end, unit='s')}, "
          f"validation from {pd.to_datetime(validation_start, unit='s')} "
          f"({gap_hours:.1f}h gap between them)")

    return train_end, validation_start


def stream_filter_clip_split(
    min_price: float, min_qty: float, floor: float, cap: float,
    train_end: int, validation_start: int,
) -> None:
    """Pass 2: streams the file again, this time reading every needed column,
    applying the liquidity filter + label clip + NaN-feature drop to each batch,
    and writing straight to train.parquet or validation.parquet via a streaming
    ParquetWriter - no batch's data outlives its own loop iteration."""
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

            mask = (df["avg_low_price"] >= min_price) & (df["label_achievable_qty"] >= min_qty)
            df = df.loc[mask]
            if df.empty:
                continue

            df = df.dropna(subset=FEATURE_COLUMNS)
            if df.empty:
                continue

            df[LABEL_COLUMN] = df[LABEL_COLUMN].clip(lower=floor, upper=cap)
            df = df[output_columns]

            train_part = df[df["timestamp"] <= train_end]
            validation_part = df[df["timestamp"] >= validation_start]

            if not train_part.empty:
                table = pa.Table.from_pandas(train_part, preserve_index=False)
                if train_writer is None:
                    train_writer = pq.ParquetWriter(OUTPUT_DIR / "train.parquet", table.schema)
                train_writer.write_table(table)
                train_rows += len(train_part)

            if not validation_part.empty:
                table = pa.Table.from_pandas(validation_part, preserve_index=False)
                if validation_writer is None:
                    validation_writer = pq.ParquetWriter(OUTPUT_DIR / "validation.parquet", table.schema)
                validation_writer.write_table(table)
                validation_rows += len(validation_part)
    finally:
        if train_writer is not None:
            train_writer.close()
        if validation_writer is not None:
            validation_writer.close()

    print(f"Wrote {train_rows:,} rows to {OUTPUT_DIR / 'train.parquet'}")
    print(f"Wrote {validation_rows:,} rows to {OUTPUT_DIR / 'validation.parquet'}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-price", type=float, default=10.0,
                         help="Minimum avg_low_price to keep a row (default: 10gp) - filters out near-worthless items whose margins are technically-correct but not meaningfully tradeable")
    parser.add_argument("--min-qty", type=float, default=5.0,
                         help="Minimum label_achievable_qty to keep a row (default: 5) - filters out single-unit trades that produce noisy, unrepresentative margins")
    parser.add_argument("--clip-percentile", type=float, default=0.995,
                         help="Clip label_margin_pct to this percentile and its mirror on the low end (default: 0.995, i.e. clip to the middle 99%%)")
    parser.add_argument("--validation-days", type=int, default=21,
                         help="How many trailing days to hold out as validation (default: 21)")
    args = parser.parse_args()

    if not FEATURES_PATH.exists():
        raise FileNotFoundError(f"{FEATURES_PATH} not found - run build_features.py first")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    floor, cap, _, _ = compute_clip_thresholds(args.min_price, args.min_qty, args.clip_percentile)
    train_end, validation_start = find_split_boundary(args.validation_days)
    stream_filter_clip_split(args.min_price, args.min_qty, floor, cap, train_end, validation_start)


if __name__ == "__main__":
    main()
