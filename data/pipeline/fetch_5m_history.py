"""
Pulls 5-minute OHLC-ish price/volume candles for every tradeable item over the last
N months from the OSRS Wiki API's /5m endpoint, and writes them to monthly Parquet
files under data/raw/5m/.

One HTTP request per 5-minute block returns every item's candle for that block (the
efficient bulk shape the wiki API is built around - see wiki_client.py's docstring
for why this pulls by timestamp rather than looping per item). A small thread pool
(default 8 workers) pulls blocks concurrently with brief pacing between requests,
balancing "finish in a reasonable time" against "don't hammer a free community API."

Resumable: each month's output file is only written once every block in that month
has been fetched, and a month whose file already exists is skipped entirely on a
re-run - interrupting and restarting just picks up wherever it left off, at month
granularity.

Usage:
    python fetch_5m_history.py --months 6
    python fetch_5m_history.py --months 6 --workers 10
"""

import argparse
import pathlib
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone

import pandas as pd
from tqdm import tqdm

from wiki_client import get_with_retry, make_client

FIVE_MINUTES = 300
OUTPUT_DIR = pathlib.Path(__file__).parent.parent / "raw" / "5m"

# Brief pacing between request dispatches (not a hard rate limit - the thread pool
# already caps concurrency - just avoids bursting many requests in the same instant).
DISPATCH_DELAY_SECONDS = 0.05


def latest_complete_block(now: datetime | None = None) -> int:
    """The most recent 5-minute block that should be fully aggregated by now.
    The wiki's most-recent block is sometimes still filling in, so back off by one
    block from the current time to avoid pulling a partial/empty window."""
    now = now or datetime.now(timezone.utc)
    ts = int(now.timestamp())
    ts = ts - (ts % FIVE_MINUTES)
    return ts - FIVE_MINUTES


def month_key(timestamp: int) -> str:
    dt = datetime.fromtimestamp(timestamp, tz=timezone.utc)
    return f"{dt.year:04d}-{dt.month:02d}"


def fetch_block(client, timestamp: int) -> list[dict]:
    """Fetches one 5-minute block, returning one row per item with data."""
    payload = get_with_retry(client, "/5m", params={"timestamp": timestamp})
    rows = []
    for item_id_str, candle in payload.get("data", {}).items():
        rows.append({
            "timestamp": timestamp,
            "item_id": int(item_id_str),
            "avg_high_price": candle.get("avgHighPrice"),
            "high_price_volume": candle.get("highPriceVolume"),
            "avg_low_price": candle.get("avgLowPrice"),
            "low_price_volume": candle.get("lowPriceVolume"),
        })
    return rows


def month_output_path(key: str) -> pathlib.Path:
    return OUTPUT_DIR / f"{key}.parquet"


def fetch_months(months: int, workers: int) -> None:
    end_block = latest_complete_block()
    start_block = end_block - int(timedelta(days=months * 30).total_seconds()) + FIVE_MINUTES
    start_block -= start_block % FIVE_MINUTES

    all_blocks = list(range(start_block, end_block + FIVE_MINUTES, FIVE_MINUTES))

    # Group blocks by month so completed months can be written and skipped on resume.
    blocks_by_month: dict[str, list[int]] = {}
    for block in all_blocks:
        blocks_by_month.setdefault(month_key(block), []).append(block)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    for month, blocks in sorted(blocks_by_month.items()):
        out_path = month_output_path(month)
        if out_path.exists():
            print(f"{month}: already fetched ({out_path}), skipping")
            continue

        print(f"{month}: fetching {len(blocks)} blocks ({len(blocks) * FIVE_MINUTES / 3600:.1f}h of data)")
        rows: list[dict] = []
        failed_blocks: list[int] = []

        with make_client() as client, ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {}
            for block in blocks:
                futures[pool.submit(fetch_block, client, block)] = block
                time.sleep(DISPATCH_DELAY_SECONDS)

            for future in tqdm(as_completed(futures), total=len(futures), desc=month):
                block = futures[future]
                try:
                    rows.extend(future.result())
                except Exception as exc:
                    failed_blocks.append(block)
                    tqdm.write(f"  block {block} failed: {exc}")

        if failed_blocks:
            print(f"{month}: {len(failed_blocks)} block(s) failed after retries, not writing output for this month")
            print(f"  failed timestamps: {failed_blocks[:10]}{'...' if len(failed_blocks) > 10 else ''}")
            print("  re-run the script to retry this month (it will be picked up again since its file wasn't written)")
            continue

        df = pd.DataFrame(rows)
        df.to_parquet(out_path, index=False)
        print(f"{month}: wrote {len(df)} rows to {out_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--months", type=int, default=6, help="How many months of history to pull (default: 6)")
    parser.add_argument("--workers", type=int, default=8, help="Concurrent fetch workers (default: 8)")
    args = parser.parse_args()

    fetch_months(args.months, args.workers)


if __name__ == "__main__":
    main()
