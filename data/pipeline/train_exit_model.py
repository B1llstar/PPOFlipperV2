"""
Trains a LightGBM regression model to predict label_hold_return_pct (the best
achievable round-trip margin over the next 24h if selling now and buying back in
later) from the rolling spread/volatility/volume/momentum features plus
position-aware features (unrealized P&L%, holding duration) in
processed/exit_train.parquet. This is the exit-side counterpart to
train_model.py's buy-side margin model - same LightGBM approach and memory-
conscious params (see that module's docstring for the full rationale), different
label and feature set.

Serving-time use: this predicts a continuous "value of continuing to hold" - the
HOLD/SELL decision itself is a threshold applied at serving time (see
data/service/main.py's EXIT_SELL_THRESHOLD_PCT), not baked into training. This
script's evaluate_threshold_decision reports precision/recall at that same
threshold, since that's the metric that actually maps to real behavior.

Usage:
    python train_exit_model.py
    python train_exit_model.py --num-leaves 31 --num-threads 2
"""

import argparse
import json
import pathlib
import resource
import time

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import mean_absolute_error, mean_squared_error

from train_model import evaluate_ranking

PROCESSED_DIR = pathlib.Path(__file__).parent.parent / "processed"
MODEL_DIR = pathlib.Path(__file__).parent.parent / "models"

FEATURE_COLUMNS = [
    "spread_pct",
    "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
    "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
    "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
    "unrealized_pnl_pct", "holding_duration_hours",
]
LABEL_COLUMN = "label_hold_return_pct"

# Must match data/service/main.py's EXIT_SELL_THRESHOLD_PCT - kept in sync
# manually since one is Python training code and the other is a serving
# constant; if this drifts from the serving threshold, this script's
# threshold-decision metrics stop describing what the live service will
# actually do.
SELL_THRESHOLD_PCT = 0.01


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024 / 1024


def load_split(name: str) -> tuple[pd.DataFrame, pd.Series]:
    path = PROCESSED_DIR / f"{name}.parquet"
    if not path.exists():
        raise FileNotFoundError(f"{path} not found - run prepare_exit_training_data.py first")
    df = pd.read_parquet(path, columns=FEATURE_COLUMNS + [LABEL_COLUMN])
    return df[FEATURE_COLUMNS], df[LABEL_COLUMN]


def evaluate_threshold_decision(y_true: np.ndarray, y_pred: np.ndarray, threshold: float) -> dict:
    """Reports how well the model's HOLD/SELL call (predicted <= threshold means
    SELL) matches what the true label says was actually the better call. This is
    the metric that maps directly to live behavior - RMSE and ranking quality
    both matter, but this is what determines how often the bot sells too early or
    holds too long."""
    predicted_sell = y_pred <= threshold
    true_sell = y_true <= threshold

    accuracy = float((predicted_sell == true_sell).mean())

    def safe_div(a: float, b: float) -> float:
        return float(a / b) if b > 0 else 0.0

    true_positive_sell = int((predicted_sell & true_sell).sum())
    predicted_sell_count = int(predicted_sell.sum())
    true_sell_count = int(true_sell.sum())

    precision_sell = safe_div(true_positive_sell, predicted_sell_count)
    recall_sell = safe_div(true_positive_sell, true_sell_count)

    true_positive_hold = int((~predicted_sell & ~true_sell).sum())
    predicted_hold_count = int((~predicted_sell).sum())
    true_hold_count = int((~true_sell).sum())

    precision_hold = safe_div(true_positive_hold, predicted_hold_count)
    recall_hold = safe_div(true_positive_hold, true_hold_count)

    return {
        "threshold": threshold,
        "accuracy": accuracy,
        "precision_sell": precision_sell,
        "recall_sell": recall_sell,
        "precision_hold": precision_hold,
        "recall_hold": recall_hold,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--num-leaves", type=int, default=31)
    parser.add_argument("--max-bin", type=int, default=63)
    parser.add_argument("--num-boost-round", type=int, default=500)
    parser.add_argument("--early-stopping-rounds", type=int, default=30)
    parser.add_argument("--num-threads", type=int, default=2)
    parser.add_argument("--sell-threshold-pct", type=float, default=SELL_THRESHOLD_PCT,
                         help=f"Threshold for the threshold-decision eval metric (default: {SELL_THRESHOLD_PCT}) - must match data/service/main.py's EXIT_SELL_THRESHOLD_PCT to be meaningful")
    args = parser.parse_args()

    print(f"Loading train/validation splits... (peak RSS so far: {peak_rss_mb():.0f} MB)")
    x_train, y_train = load_split("exit_train")
    x_val, y_val = load_split("exit_validation")
    print(f"train: {len(x_train):,} rows, validation: {len(x_val):,} rows "
          f"(peak RSS: {peak_rss_mb():.0f} MB)")

    train_set = lgb.Dataset(x_train, label=y_train, free_raw_data=True)
    val_set = lgb.Dataset(x_val, label=y_val, reference=train_set, free_raw_data=True)

    params = {
        "objective": "regression",
        "metric": "rmse",
        "num_leaves": args.num_leaves,
        "max_bin": args.max_bin,
        "num_threads": args.num_threads,
        "learning_rate": 0.05,
        "feature_fraction": 0.8,
        "bagging_fraction": 0.8,
        "bagging_freq": 5,
        "verbose": -1,
    }

    print(f"Training LightGBM (num_leaves={args.num_leaves}, max_bin={args.max_bin}, "
          f"num_threads={args.num_threads})...")
    start = time.time()
    booster = lgb.train(
        params,
        train_set,
        num_boost_round=args.num_boost_round,
        valid_sets=[val_set],
        valid_names=["validation"],
        callbacks=[
            lgb.early_stopping(args.early_stopping_rounds, verbose=True),
            lgb.log_evaluation(period=25),
        ],
    )
    elapsed = time.time() - start
    print(f"Training finished in {elapsed:.1f}s, {booster.current_iteration()} rounds "
          f"(peak RSS: {peak_rss_mb():.0f} MB)")

    y_pred = booster.predict(x_val, num_iteration=booster.best_iteration)
    y_val_arr = y_val.to_numpy()
    rmse = float(np.sqrt(mean_squared_error(y_val, y_pred)))
    mae = float(mean_absolute_error(y_val, y_pred))
    ranking_100 = evaluate_ranking(y_val_arr, y_pred, top_k=100)
    ranking_500 = evaluate_ranking(y_val_arr, y_pred, top_k=500)
    threshold_decision = evaluate_threshold_decision(y_val_arr, y_pred, args.sell_threshold_pct)

    print(f"\nValidation RMSE: {rmse:.4f}, MAE: {mae:.4f}")
    print(f"Top-100 by prediction: true mean margin {ranking_100['predicted_top_k_mean_true_margin']:.4f} "
          f"(overall mean {ranking_100['overall_mean_true_margin']:.4f}, "
          f"best-possible top-100 {ranking_100['best_possible_top_k_mean_true_margin']:.4f})")
    print(f"Top-500 by prediction: true mean margin {ranking_500['predicted_top_k_mean_true_margin']:.4f} "
          f"(overall mean {ranking_500['overall_mean_true_margin']:.4f}, "
          f"best-possible top-500 {ranking_500['best_possible_top_k_mean_true_margin']:.4f})")
    print(f"\nThreshold decision @ {args.sell_threshold_pct}: accuracy {threshold_decision['accuracy']:.4f}, "
          f"SELL precision {threshold_decision['precision_sell']:.4f} / recall {threshold_decision['recall_sell']:.4f}, "
          f"HOLD precision {threshold_decision['precision_hold']:.4f} / recall {threshold_decision['recall_hold']:.4f}")

    importance = dict(zip(FEATURE_COLUMNS, booster.feature_importance(importance_type="gain").tolist()))
    print("\nFeature importance (by gain):")
    for feature, gain in sorted(importance.items(), key=lambda kv: -kv[1]):
        print(f"  {feature}: {gain:.1f}")

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    model_path = MODEL_DIR / "exit_model.txt"
    booster.save_model(str(model_path))

    metrics = {
        "trained_at": pd.Timestamp.now(tz="UTC").isoformat(),
        "train_rows": len(x_train),
        "validation_rows": len(x_val),
        "best_iteration": booster.best_iteration,
        "validation_rmse": rmse,
        "validation_mae": mae,
        "ranking_top_100": ranking_100,
        "ranking_top_500": ranking_500,
        "threshold_decision": threshold_decision,
        "feature_importance_gain": importance,
        "params": params,
    }
    metrics_path = MODEL_DIR / "exit_model_metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2))

    print(f"\nSaved model to {model_path}")
    print(f"Saved metrics to {metrics_path}")


if __name__ == "__main__":
    main()
