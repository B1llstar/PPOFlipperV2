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

## Preparing the training dataset

```bash
cd pipeline
python prepare_training_data.py
```

Applies the liquidity filter + outlier clip called for above, on top of
`features.parquet`, then splits chronologically into `processed/train.parquet`
and `processed/validation.parquet` (last 21 days by default, `--validation-days`
to change) - **never a random split**, since a random split would let the
model train on rows whose 4-hour forward label window overlaps
validation-period data it's being tested on, making validation error look
better than it really is. A 4-hour gap (matching the label horizon) is left
between the two periods so no training row's label reaches into validation
data at all - verified on the actual output (`train.timestamp.max()` to
`validation.timestamp.min()` is exactly 4.0h).

Defaults (`--min-price 10 --min-qty 5 --min-volume-1h 5000 --clip-percentile 0.995`)
keep ~30% of rows and clip label_margin_pct to roughly [-10%, 200%] - down
from a raw max of 432,000%. Resulting train set: 3.87M rows, no nulls.

**`--min-volume-1h` matters more than it looks:** the achievable-qty filter
alone only checks the two specific 5-minute blocks a trade actually used,
not the item's overall recent liquidity - an item can clear `--min-qty` on
one thin trade while being fundamentally illiquid. Without a trailing-volume
filter, rows with >50% label margin turned out to have ~16x lower median
`volume_1h` than normal rows even after the qty filter, and dominated the
trained model's top-K rankings with implausible, unexecutable "flips" (see
"Training a model" below for the concrete effect this had on validation
metrics). `--min-volume-1h 5000` cuts that contamination meaningfully while
keeping ~81% of the liquidity-filtered rows.

**Memory design:** this machine runs with genuinely tight, sustained memory
pressure (confirmed via `sysctl vm.swapusage` showing real swap usage even
at idle, not just app noise) - a first version that loaded the full 3.3GB
`features.parquet` via `pd.read_parquet()` got this process killed. The
script now streams through the file's row groups (`pyarrow.parquet`'s
`iter_batches`, 50k rows at a time) in two passes - one to compute clip
thresholds from just the label column, one to filter/clip/split and write
incrementally - so peak memory stays bounded by one small batch at a time
regardless of the input file's total size. In practice this finishes the
full 52M-row dataset in under 20 seconds.

## Training a model

```bash
cd pipeline
python train_model.py --num-boost-round 1500
```

Trains a LightGBM regressor (`objective=regression`, target `label_margin_pct`)
on the 13 rolling spread/volatility/volume/momentum features, with early
stopping against the validation split. Saves `models/margin_model.txt`
(LightGBM's native text format - loadable via `lgb.Booster(model_file=...)`,
no pickle/version coupling to this exact sklearn/lightgbm install) and
`models/margin_model_metrics.json` (RMSE/MAE, ranking quality, feature
importance, and the params used - so a later run can be compared against
what came before it).

Beyond RMSE/MAE, `train_model.py` reports a ranking-quality metric that
matters more for how this model actually gets used: of the top-K items by
predicted margin, what's their *true* mean margin, versus the true mean
across everything (the no-model baseline) and the true best-possible top-K
(the ceiling)? A model can have mediocre RMSE and still be very useful here
if it reliably ranks the genuinely good flips near the top - which is
exactly what the flipper needs, not precise margin regression on every item.

**Model selection was not just "run it once and ship it" -** the first run
(500 rounds, pre-volume-filter data) hit the round cap without early
stopping ever triggering, and its top-100-by-prediction ranking metric
looked suspicious: best-possible top-100 true margin landed exactly at the
label clip cap (2.0 / 200%), which turned out to mean the "best" validation
rows were dominated by the low-volume outlier contamination described
above, not genuinely good flips. Diagnosed that before trusting the model,
added `--min-volume-1h` to the prep script, and retrained: early stopping
now triggers properly (811 rounds), RMSE roughly halved (0.0565 -> 0.0291),
and top-100-by-prediction's true mean margin (58.5%) now sits close to the
true best-possible top-100 (59.2%) instead of being pinned at the clip
ceiling - a model that's actually finding good flips, not artifacts.

**Memory:** same tight-machine constraints as the rest of this pipeline
(see prepare_training_data.py's memory-design note) - LightGBM's own
histogram-based training doesn't need the full dataset as a dense matrix,
and `--max-bin 63` / `--num-threads 2` (both LightGBM defaults halved or
reduced) keep its footprint predictable rather than left at library
defaults. Measured peak RSS ~1.8GB training on 3.87M rows, finishes in
under 90 seconds.

## Next steps (not yet built)

- A small scoring service the GE Flipper plugin calls over HTTP to get
  ranked flip candidates from `models/margin_model.txt`.
