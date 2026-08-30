# GE Flipper data pipeline

Pulls historical Grand Exchange price/volume data from the OSRS Wiki's
real-time prices API to train the margin-prediction model backing the
upcoming GE Flipper plugin (see `plugins/ge-star-v2/`'s docs for the
execution side this feeds into).

## Setup

```bash
cd data
/opt/homebrew/bin/python3.13 -m venv venv   # repo's system python3 is 3.9, too old for current pandas/pyarrow
source venv/bin/activate
pip install -r requirements.txt
```

## Pulling data

```bash
cd pipeline
python fetch_mapping.py                       # item id -> name/limit/members metadata, ~4700 rows, seconds
python fetch_5m_history.py --months 6          # 5-minute price/volume candles, ~50k requests, resumable
```

`fetch_5m_history.py` pulls one HTTP request per 5-minute block (each
request returns every actively-traded item's candle for that block - the
wiki API's efficient bulk shape, not a per-item loop) using a small thread
pool (`--workers`, default 8) with brief pacing between dispatches. Output
is written as one Parquet file per month under `raw/5m/`; a month is
skipped on re-run once its file exists, so interrupting and restarting
picks up wherever it left off.

**Why 5-minute data only goes back to ~March 2021:** confirmed empirically
by probing the live API (`/5m?timestamp=...` at various historical
timestamps) - the wiki's real-time price API doesn't have 5-minute
resolution data before that, regardless of how far back you ask.

**Why modest concurrency (8-10 workers), not more:** the wiki API has no
published rate limit but explicitly asks against heavy per-item looping and
reserves the right to block abusive User-Agents. It's a free
community-run service other people (including in-game price checkers) rely
on - a blocked User-Agent would stall this pipeline and reflects badly on
whatever contact info is in it. See `pipeline/wiki_client.py`.

## Output

- `raw/item_mapping.parquet` — item id, name, GE buy limit, members status,
  alch values. ~4700 rows.
- `raw/5m/YYYY-MM.parquet` — one row per (timestamp, item_id) with
  `avg_high_price`, `high_price_volume`, `avg_low_price`, `low_price_volume`.
  Nulls where the wiki had no trades in that item/block. Not committed to
  git (`data/raw/` is gitignored) - regenerate locally via the fetch
  scripts above.

## Building features + labels

```bash
cd pipeline
python build_features.py --min-rows 200
```

Reads every monthly file in `raw/5m/`, and for each item with at least
`--min-rows` traded blocks (default 200 - too little history to compute
meaningful rolling features below that), computes:

- **Features** (as of each 5-minute block): current spread %, rolling
  volatility/mean-price/volume over 1h/6h/24h windows, and momentum
  (price change %) over those same windows.
- **Label** (`label_margin_pct`, `label_achievable_qty`): the realistic
  achievable round-trip margin over the next 4 hours - buy at that block's
  `avg_low_price` sized by `low_price_volume`, then find the best net-profit
  sell within the next 48 blocks, sized by the smaller of the buy/sell
  volumes at those two points. This is a fill-feasibility-aware label, not
  just "best price seen in the future window" - see
  `build_features.py`'s docstring and `compute_forward_label` for the exact
  logic. Manually verified correct against a brute-force scan on real data.

Each item's full 6-month timeseries is processed independently (median
~1800 rows/item/month, a few thousand rows total even for the most liquid
items) rather than holding the whole cross-item dataset in wide/rolling
form at once - measured peak memory for the full run is ~2.4GB, comfortable
on an 8.6GB machine. Runs in about 3.5 minutes for the full 6-month, ~4000
item dataset.

Output: `processed/features.parquet` (~3.3GB, 52M rows, not committed to
git - `data/processed/` is gitignored alongside `raw/`). Rows with no
computable label (near the end of the dataset, or no valid future trade
within the horizon) are dropped before writing.

**Known data-quality issue, deliberately left unfiltered here:** ~0.33% of
rows have extreme label margins (up to 432,000%) from near-worthless items
(1-4gp) trading at tiny volume (1-2 units) - mathematically correct given
the label definition, but not a realistic tradeable flip. This dataset is
kept as the full unfiltered computed truth; liquidity filtering (min price,
min achievable quantity) and outlier clipping belong in the training step
instead, so different thresholds can be experimented with without
re-running this ~3.5 minute build. Even a fairly strict filter
(price >= 100gp, achievable qty >= 10) still leaves some 1800%+ outliers,
so training will need an explicit percentile clip on top of a liquidity
filter, not liquidity filtering alone.

## Next steps (not yet built)

- Training dataset prep: liquidity filter + outlier clipping on top of
  `features.parquet` (see above), train/validation split (careful to split
  by time, not randomly - a model trained on data that leaks future
  information back into "past" training rows would look better than it is).
- Training a margin-prediction model on the resulting dataset.
- A small scoring service the GE Flipper plugin calls over HTTP to get
  ranked flip candidates.
