"""
GE Flipper scoring service: loads the trained LightGBM margin-prediction model
and exposes GET /candidates, which scans currently-liquid items and returns the
top-N ranked by predicted round-trip flip margin. This is what GE Star V2's
GE Flipper plugin (Java side, not yet built) calls over plain HTTP to decide what
to queue.

Design: scans a broad item universe rather than requiring the caller to already
know which items to ask about (see data/README.md's "Scoring service" section
for why) - matches how the flipper is actually meant to be used: "what should I
flip right now," not "how good is this specific item."

Run:
    uvicorn main:app --host 127.0.0.1 --port 8420

Everything binds to 127.0.0.1 only - this is a local sidecar process for the
plugin running on the same machine, not a public API.
"""

import pathlib
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass

import lightgbm as lgb
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel

from live_features import ItemFeatures, compute_live_features, compute_position_features, fetch_all_windows
from wiki_client import get_with_retry, make_client

MODEL_PATH = pathlib.Path(__file__).parent.parent / "models" / "margin_model.txt"
EXIT_MODEL_PATH = pathlib.Path(__file__).parent.parent / "models" / "exit_model.txt"

FEATURE_ORDER = [
    "spread_pct",
    "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
    "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
    "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
]

# Must match data/pipeline/prepare_exit_training_data.py's FEATURE_COLUMNS order exactly.
EXIT_FEATURE_ORDER = FEATURE_ORDER + ["unrealized_pnl_pct", "holding_duration_hours"]

# Below this predicted further-hold return, the exit model calls SELL rather than HOLD -
# see data/pipeline/train_exit_model.py's SELL_THRESHOLD_PCT, which must be kept in sync
# with this value for that script's threshold-decision eval to describe live behavior.
EXIT_SELL_THRESHOLD_PCT = 0.01

# Same liquidity bar used to build the training data (see
# pipeline/prepare_training_data.py's defaults) - scoring items the model
# never saw anything like during training would just be noise in the ranking.
MIN_PRICE = 10.0
MIN_VOLUME_1H = 5000.0

@dataclass
class ItemMeta:
    name: str
    ge_limit: int | None  # None if the wiki mapping had no limit for this item (some untradeable/unlimited items)


model_state: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not MODEL_PATH.exists():
        raise FileNotFoundError(f"{MODEL_PATH} not found - run pipeline/train_model.py first")
    model_state["booster"] = lgb.Booster(model_file=str(MODEL_PATH))

    # The exit model is optional at startup - /candidates must keep working even before
    # it's been trained (e.g. right after a fresh checkout). /should-sell 503s until it's
    # available, rather than this crashing the whole service.
    if EXIT_MODEL_PATH.exists():
        model_state["exit_booster"] = lgb.Booster(model_file=str(EXIT_MODEL_PATH))
    else:
        print(f"WARNING: {EXIT_MODEL_PATH} not found - /should-sell will return 503 until "
              f"pipeline/train_exit_model.py has been run")

    with make_client() as client:
        mapping_payload = get_with_retry(client, "/mapping")
    model_state["item_meta"] = {
        item["id"]: ItemMeta(name=item["name"], ge_limit=item.get("limit"))
        for item in mapping_payload
    }

    yield
    model_state.clear()


app = FastAPI(title="GE Flipper Scoring Service", lifespan=lifespan)


class Candidate(BaseModel):
    item_id: int
    item_name: str
    predicted_margin_pct: float
    current_buy_price: float
    current_sell_price: float
    absolute_margin_gp: float  # predicted_margin_pct * current_buy_price - per-unit profit if the prediction holds
    ge_limit: int | None  # max units tradeable per 4h reset window; None if unknown/unlimited
    max_position_value_gp: float | None  # ge_limit * current_buy_price - the real ceiling on how much capital one flip of this item can use


class CandidatesResponse(BaseModel):
    candidates: list[Candidate]
    items_scored: int
    items_skipped_insufficient_data: int


def score_all_candidates() -> tuple[list[Candidate], int, int]:
    booster: lgb.Booster = model_state["booster"]
    item_meta: dict[int, ItemMeta] = model_state["item_meta"]

    with make_client() as client:
        windows = fetch_all_windows(client)

    # Only consider items present in the 1h window at all - anything not
    # trading in the last hour can't pass the volume filter below regardless,
    # so this keeps the per-item loop scoped to plausible candidates only.
    candidate_ids = list(windows["1h"].keys())

    item_features: list[ItemFeatures] = []
    skipped = 0
    for item_id in candidate_ids:
        result = compute_live_features(item_id, windows)
        if result is None:
            skipped += 1
            continue
        if result.current_buy_price < MIN_PRICE or result.features["volume_1h"] < MIN_VOLUME_1H:
            skipped += 1
            continue
        item_features.append(result)

    if not item_features:
        return [], 0, skipped

    feature_matrix = [[f.features[col] for col in FEATURE_ORDER] for f in item_features]
    predictions = booster.predict(feature_matrix)

    candidates = []
    for f, pred in zip(item_features, predictions):
        meta = item_meta.get(f.item_id)
        name = meta.name if meta else f"Unknown item {f.item_id}"
        ge_limit = meta.ge_limit if meta else None
        margin_pct = float(pred)
        candidates.append(Candidate(
            item_id=f.item_id,
            item_name=name,
            predicted_margin_pct=margin_pct,
            current_buy_price=f.current_buy_price,
            current_sell_price=f.current_sell_price,
            absolute_margin_gp=margin_pct * f.current_buy_price,
            ge_limit=ge_limit,
            max_position_value_gp=ge_limit * f.current_buy_price if ge_limit else None,
        ))
    candidates.sort(key=lambda c: -c.predicted_margin_pct)

    return candidates, len(item_features), skipped


@app.get("/candidates", response_model=CandidatesResponse)
def get_candidates(limit: int = Query(default=20, ge=1, le=200)) -> CandidatesResponse:
    candidates, scored, skipped = score_all_candidates()
    return CandidatesResponse(
        candidates=candidates[:limit],
        items_scored=scored,
        items_skipped_insufficient_data=skipped,
    )


class PositionQuery(BaseModel):
    item_id: int
    quantity_held: int
    average_cost_per_unit: float
    purchase_timestamp: float  # unix seconds


class ShouldSellRequest(BaseModel):
    positions: list[PositionQuery]
    now_timestamp: float | None = None  # defaults to server's current time if omitted


class SellDecision(BaseModel):
    item_id: int
    item_name: str
    decision: str  # "HOLD" or "SELL"
    predicted_further_return_pct: float
    unrealized_pnl_pct: float
    holding_duration_hours: float
    current_sell_price: float  # live insta-sell reference - what a SELL order should price against
    sell_threshold_used: float


class ShouldSellResponse(BaseModel):
    decisions: list[SellDecision]
    items_skipped_insufficient_data: int


@app.post("/should-sell", response_model=ShouldSellResponse)
def should_sell(req: ShouldSellRequest) -> ShouldSellResponse:
    """Batched hold/sell decision for every currently-held position, in one call - fetches the
    live wiki windows once for the whole batch (see fetch_all_windows's bulk-endpoint
    discipline), not once per position, matching /candidates' precedent. See
    data/pipeline/train_exit_model.py for what the underlying model predicts and
    HANDOFF_FLIPPER_EXIT_MODEL.md for why a dedicated exit model, not the buy-side one."""
    if "exit_booster" not in model_state:
        raise HTTPException(status_code=503, detail=f"Exit model not loaded - run pipeline/train_exit_model.py "
                                                      f"to produce {EXIT_MODEL_PATH}")
    if not req.positions:
        return ShouldSellResponse(decisions=[], items_skipped_insufficient_data=0)

    booster: lgb.Booster = model_state["exit_booster"]
    item_meta: dict[int, ItemMeta] = model_state["item_meta"]
    now = req.now_timestamp if req.now_timestamp is not None else time.time()

    with make_client() as client:
        windows = fetch_all_windows(client)

    feature_rows = []
    live_infos = []
    skipped = 0
    for position in req.positions:
        result = compute_live_features(position.item_id, windows)
        if result is None:
            skipped += 1
            continue

        position_features = compute_position_features(
            result.current_sell_price, position.average_cost_per_unit, position.purchase_timestamp, now,
        )
        combined = {**result.features, **position_features}
        feature_rows.append([combined[col] for col in EXIT_FEATURE_ORDER])
        live_infos.append((position, result, position_features))

    if not feature_rows:
        return ShouldSellResponse(decisions=[], items_skipped_insufficient_data=skipped)

    predictions = booster.predict(feature_rows)

    decisions = []
    for (position, result, position_features), pred in zip(live_infos, predictions):
        meta = item_meta.get(position.item_id)
        name = meta.name if meta else f"Unknown item {position.item_id}"
        predicted_further_return_pct = float(pred)
        decisions.append(SellDecision(
            item_id=position.item_id,
            item_name=name,
            decision="SELL" if predicted_further_return_pct <= EXIT_SELL_THRESHOLD_PCT else "HOLD",
            predicted_further_return_pct=predicted_further_return_pct,
            unrealized_pnl_pct=position_features["unrealized_pnl_pct"],
            holding_duration_hours=position_features["holding_duration_hours"],
            current_sell_price=result.current_sell_price,
            sell_threshold_used=EXIT_SELL_THRESHOLD_PCT,
        ))

    return ShouldSellResponse(decisions=decisions, items_skipped_insufficient_data=skipped)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "model_loaded": "booster" in model_state,
        "exit_model_loaded": "exit_booster" in model_state,
    }
