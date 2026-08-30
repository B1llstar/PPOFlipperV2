"""
Trains a LightGBM regression model to predict label_margin_pct (realistic
achievable round-trip flip margin over the next 4 hours) from the rolling
spread/volatility/volume/momentum features in processed/train.parquet.

Model choice: LightGBM over a neural net or full random forest because this
machine runs under genuine, sustained memory pressure (confirmed via
`sysctl vm.swapusage` showing real swap usage even at idle - see
prepare_training_data.py's memory-design note). LightGBM's histogram-based
splitting doesn't need the full dataset resident as a dense matrix the way
many other approaches do, and its own memory footprint during training is
predictable and boundable via max_bin / num_leaves, both set conservatively
below rather than left at library defaults.

Usage:
    python train_model.py
    python train_model.py --num-leaves 31 --num-threads 2
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

PROCESSED_DIR = pathlib.Path(__file__).parent.parent / "processed"
MODEL_DIR = pathlib.Path(__file__).parent.parent / "models"

FEATURE_COLUMNS = [
    "spread_pct",
    "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
    "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
    "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
]
LABEL_COLUMN = "label_margin_pct"


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024 / 1024


def load_split(name: str) -> tuple[pd.DataFrame, pd.Series]:
    path = PROCESSED_DIR / f"{name}.parquet"
    if not path.exists():
        raise FileNotFoundError(f"{path} not found - run prepare_training_data.py first")
    df = pd.read_parquet(path, columns=FEATURE_COLUMNS + [LABEL_COLUMN])
    return df[FEATURE_COLUMNS], df[LABEL_COLUMN]


def evaluate_ranking(y_true: np.ndarray, y_pred: np.ndarray, top_k: int) -> dict:
    """Since the flipper cares about *which* items look best right now, not
    just precise margin regression, this reports how good the model's
    top-K-by-prediction picks actually are: their true mean margin, versus
    the true mean margin across everything (the "no model" baseline) and
    the true best-possible top-K (the ceiling). A model with mediocre RMSE
    can still be very useful here if it reliably ranks the good flips near
    the top."""
    order = np.argsort(-y_pred)
    top_k_true = y_true[order[:top_k]]
    ceiling_true = np.sort(y_true)[::-1][:top_k]

    return {
        "top_k": top_k,
        "predicted_top_k_mean_true_margin": float(top_k_true.mean()),
        "overall_mean_true_margin": float(y_true.mean()),
        "best_possible_top_k_mean_true_margin": float(ceiling_true.mean()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--num-leaves", type=int, default=31,
                         help="Max leaves per tree (default: 31, LightGBM's own conservative default) - higher can overfit and costs more memory per tree")
    parser.add_argument("--max-bin", type=int, default=63,
                         help="Max histogram bins per feature (default: 63, half LightGBM's usual 255 default) - fewer bins means a smaller memory footprint for the binned dataset, at some cost to split precision")
    parser.add_argument("--num-boost-round", type=int, default=500,
                         help="Max boosting rounds (default: 500) - early stopping will usually halt well before this")
    parser.add_argument("--early-stopping-rounds", type=int, default=30)
    parser.add_argument("--num-threads", type=int, default=2,
                         help="LightGBM thread count (default: 2) - kept low so training doesn't compete hard for CPU/memory bandwidth alongside whatever else is running on this machine")
    args = parser.parse_args()

    print(f"Loading train/validation splits... (peak RSS so far: {peak_rss_mb():.0f} MB)")
    x_train, y_train = load_split("train")
    x_val, y_val = load_split("validation")
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
    rmse = float(np.sqrt(mean_squared_error(y_val, y_pred)))
    mae = float(mean_absolute_error(y_val, y_pred))
    ranking_100 = evaluate_ranking(y_val.to_numpy(), y_pred, top_k=100)
    ranking_500 = evaluate_ranking(y_val.to_numpy(), y_pred, top_k=500)

    print(f"\nValidation RMSE: {rmse:.4f}, MAE: {mae:.4f}")
    print(f"Top-100 by prediction: true mean margin {ranking_100['predicted_top_k_mean_true_margin']:.4f} "
          f"(overall mean {ranking_100['overall_mean_true_margin']:.4f}, "
          f"best-possible top-100 {ranking_100['best_possible_top_k_mean_true_margin']:.4f})")
    print(f"Top-500 by prediction: true mean margin {ranking_500['predicted_top_k_mean_true_margin']:.4f} "
          f"(overall mean {ranking_500['overall_mean_true_margin']:.4f}, "
          f"best-possible top-500 {ranking_500['best_possible_top_k_mean_true_margin']:.4f})")

    importance = dict(zip(FEATURE_COLUMNS, booster.feature_importance(importance_type="gain").tolist()))
    print("\nFeature importance (by gain):")
    for feature, gain in sorted(importance.items(), key=lambda kv: -kv[1]):
        print(f"  {feature}: {gain:.1f}")

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    model_path = MODEL_DIR / "margin_model.txt"
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
        "feature_importance_gain": importance,
        "params": params,
    }
    metrics_path = MODEL_DIR / "margin_model_metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2))

    print(f"\nSaved model to {model_path}")
    print(f"Saved metrics to {metrics_path}")


if __name__ == "__main__":
    main()
