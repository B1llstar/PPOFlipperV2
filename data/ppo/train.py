"""
Trains a PPO flipping policy against GEMarketEnv, per PROPOSAL.md section 3.4.

Library: Stable-Baselines3's PPO (explicit choice in section 3.4) over a
vectorized GEMarketEnv. Starts with DummyVecEnv for correctness (see the
"Vectorization" note below for why SubprocVecEnv is deferred).

Network: SB3's stock MlpPolicy with a custom net_arch=[256, 128, 128] for both
the actor (pi) and critic (vf) heads, per section 3.3's "small shared trunk
256->128->128, ReLU, actor head (categorical), critic head (state-value)". Note
on "shared" vs SB3's actual net_arch semantics: SB3's MlpExtractor builds two
separate MLPs (one per pi/vf) from net_arch rather than one literal shared trunk
with two heads branching off partway through - giving pi and vf the same
net_arch=[256,128,128] is the standard, simplest way to express this network
shape in SB3 without writing a custom feature extractor/policy class (verified:
SB3 does not expose a "shared-trunk-then-split" option through net_arch alone).
A fully custom torch.nn.Module extractor could give a literal shared trunk, but
the proposal's own text says "should not require a fully custom network class
unless SB3's stock MLP policy can't express this shape" - it can, so that's what
this uses.

Checkpointing: a custom SB3 callback (CheckpointAndEvalCallback) that, every
--checkpoint-freq steps: saves a .pth (the policy's state_dict, not SB3's own
.zip format - PROPOSAL.md 3.4 explicitly calls for ".pth"), writes a sidecar
JSON (step count, git commit hash of this env/reward code, validation metrics),
runs a short validation-episode rollout on the held-out month split to score the
checkpoint, updates best.pth if it's the best validation reward seen so far, and
prunes older periodic checkpoints from this run (keeping the last
--keep-last-n plus best.pth forever, per PROPOSAL.md's Decisions section).

Usage:
    python train.py --timesteps 30000 --checkpoint-freq 5000 --max-items 40
"""

from __future__ import annotations

import argparse
import csv
import json
import pathlib
import subprocess
import time

import numpy as np
from stable_baselines3 import PPO
from stable_baselines3.common.callbacks import BaseCallback
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv, SubprocVecEnv

from env import GEMarketEnv
from market_data import MarketDataset, load_market_dataset

MODELS_DIR = pathlib.Path(__file__).parent.parent / "models" / "ppo"
CHECKPOINTS_DIR = MODELS_DIR / "checkpoints"
BEST_PATH = MODELS_DIR / "best.pth"
BEST_SIDECAR_PATH = MODELS_DIR / "best.json"
METRICS_LOG_PATH = MODELS_DIR / "training_log.csv"


def push_trained_items_to_firestore(dataset: MarketDataset, git_commit: str) -> None:
    """Records the exact item universe a training run used, as a durable Firestore document -
    not per-account state (accounts/{accountHash}/...), so it lives alongside the other
    project-wide reference data (tradableItems) rather than under any specific account. Pushed
    once per run, right after the dataset loads (the item set doesn't change mid-run), not on
    every checkpoint - checkpoints already carry their own trained_item_ids in their sidecar JSON
    (see CheckpointAndEvalCallback._save_pth) for a self-contained per-checkpoint record; this is
    the queryable, cross-run-comparable version of the same fact.

    Best-effort: a missing/unreadable service account key or any Firestore failure logs a
    warning and the training run continues unaffected - this is a nice-to-have record, never a
    dependency of training itself.
    """
    service_account_path = pathlib.Path(__file__).parent.parent.parent / "ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json"
    if not service_account_path.exists():
        print(f"Skipping trained-item Firestore push - no service account key at {service_account_path}")
        return

    try:
        from google.cloud import firestore
    except ImportError:
        print("Skipping trained-item Firestore push - google-cloud-firestore not installed")
        return

    try:
        db = firestore.Client.from_service_account_json(str(service_account_path))
        items = [
            {"itemId": series.item_id, "name": series.name, "buyLimit": series.buy_limit}
            for series in dataset.items.values()
        ]
        db.collection("modelTrainedItems").document(git_commit).set({
            "gitCommit": git_commit,
            "itemCount": len(items),
            "items": items,
            "trainedAt": firestore.SERVER_TIMESTAMP,
        })
        print(f"Pushed {len(items)} trained item(s) to Firestore modelTrainedItems/{git_commit}")
    except Exception as e:
        print(f"Warning: failed to push trained-item list to Firestore - {e}")


def push_training_progress_to_firestore(db, git_commit: str, step: int, total_timesteps: int,
                                         train_mean_reward: float, val_metrics: dict, is_best: bool) -> None:
    """Records this checkpoint's metrics to a project-wide (not per-account) Firestore
    collection, so the web dashboard can show a live training-progress view instead of a rented
    GPU box's raw SSH log being the only way to watch a run - requested after a real run's
    progress was only visible by tailing a remote log file.

    One document per (git_commit, step) under trainingRuns/{git_commit}/checkpoints/{step}, plus a
    parent trainingRuns/{git_commit} document holding run-level fields (total_timesteps, latest
    step/is_best) that update on every checkpoint - lets the dashboard show "run in progress, N%
    done, latest metrics" from the parent doc alone without needing to list every checkpoint, while
    the full per-checkpoint history is still there for a progress chart if wanted later.

    Best-effort and silent on missing setup, same as push_trained_items_to_firestore: a training
    run's own progress is never gated on this succeeding. Takes an already-constructed `db` client
    (unlike push_trained_items_to_firestore's one-off connection) since this fires once per
    checkpoint over a long run, not once at startup - reconnecting/re-authenticating every time
    would be wasteful and, on a flaky connection, a needless extra failure point per checkpoint.
    """
    if db is None:
        return
    try:
        from google.cloud import firestore
        now = firestore.SERVER_TIMESTAMP
        progress_pct = min(100.0, 100.0 * step / max(total_timesteps, 1))

        run_ref = db.collection("trainingRuns").document(git_commit)
        run_ref.set({
            "gitCommit": git_commit,
            "totalTimesteps": total_timesteps,
            "latestStep": step,
            "progressPct": progress_pct,
            "latestValMeanEpisodeReward": val_metrics["mean_episode_reward"],
            "latestValMeanRealizedPnl": val_metrics["mean_realized_pnl"],
            "latestValMeanWinRate": val_metrics["mean_win_rate"],
            "latestValMeanGuardrailViolations": val_metrics["mean_guardrail_violations"],
            "isBest": is_best,
            "updatedAt": now,
        }, merge=True)

        run_ref.collection("checkpoints").document(str(step)).set({
            "step": step,
            "trainMeanEpReward": train_mean_reward,
            "valMeanEpisodeReward": val_metrics["mean_episode_reward"],
            "valMeanRealizedPnl": val_metrics["mean_realized_pnl"],
            "valMeanWinRate": val_metrics["mean_win_rate"],
            "valMeanGuardrailViolations": val_metrics["mean_guardrail_violations"],
            "isBest": is_best,
            "recordedAt": now,
        })
    except Exception as e:
        print(f"Warning: failed to push training progress to Firestore - {e}")


def get_git_commit(override: str | None = None) -> str:
    """Tags a checkpoint with the git commit of the env/reward code that
    produced it, per PROPOSAL.md 3.4's "agent versioning" requirement - reward/
    env changes make checkpoints from different code versions non-comparable.

    override: pass the real local commit hash explicitly (--git-commit) when running
    against a plain file copy with no .git directory present - e.g. code deployed to a
    rented training instance via scp/tarball rather than a git clone. Without this, a
    remote run silently tagged every checkpoint and its Firestore modelTrainedItems
    push as commit "unknown", discovered live during the first real GPU training run
    when /workspace/botstar/data/ppo had no .git anywhere up to the filesystem root.
    """
    if override:
        return override
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=pathlib.Path(__file__).parent, text=True
        ).strip()
    except Exception as e:
        return f"unknown ({e})"


def make_env_fn(dataset: MarketDataset, split: str, watchlist_size: int, episode_length: int, seed: int):
    def _init():
        env = GEMarketEnv(
            dataset=dataset,
            watchlist_size=watchlist_size,
            episode_length_blocks=episode_length,
            split=split,
            seed=seed,
        )
        # Monitor wraps the env to record per-episode reward/length into `info`
        # on episode end, which SB3's ep_info_buffer (and this script's
        # train_mean_reward logging) reads from - without it, model.ep_info_buffer
        # stays empty and the "training-side" reward metric can't be reported.
        return Monitor(env)
    return _init


def run_validation_episodes(
    dataset: MarketDataset, model: PPO, watchlist_size: int, episode_length: int,
    n_episodes: int = 5, base_seed: int = 10_000,
) -> dict:
    """Rolls out n_episodes fresh validation-split episodes with the current
    policy (deterministic actions) and aggregates the metrics PROPOSAL.md 3.4
    asks for: mean episode reward, realized P&L, win rate, guardrail-violation
    count. A fresh single env per call (not vectorized) - this runs only every
    --checkpoint-freq steps, not in the hot training loop, so it doesn't need
    to be fast."""
    rewards, pnls, win_rates, violations = [], [], [], []
    for i in range(n_episodes):
        eval_env = GEMarketEnv(
            dataset=dataset, watchlist_size=watchlist_size,
            episode_length_blocks=episode_length, split="validation", seed=base_seed + i,
        )
        obs, _ = eval_env.reset(seed=base_seed + i)
        done = False
        while not done:
            action, _ = model.predict(obs, deterministic=True)
            obs, reward, terminated, truncated, _ = eval_env.step(int(action))
            done = terminated or truncated

        stats = eval_env.stats
        rewards.append(stats.total_reward)
        pnls.append(stats.realized_pnl)
        win_rates.append(stats.winning_trades / stats.closed_trades if stats.closed_trades > 0 else 0.0)
        violations.append(stats.guardrail_violations)

    return {
        "mean_episode_reward": float(np.mean(rewards)),
        "mean_realized_pnl": float(np.mean(pnls)),
        "mean_win_rate": float(np.mean(win_rates)),
        "mean_guardrail_violations": float(np.mean(violations)),
    }


class CheckpointAndEvalCallback(BaseCallback):
    """Every `checkpoint_freq` env steps: saves a .pth + sidecar JSON, evaluates
    on the validation split, updates best.pth, logs metrics to CSV, and prunes
    old periodic checkpoints (keep best.pth + last `keep_last_n` from this run)."""

    def __init__(
        self, dataset: MarketDataset, checkpoint_freq: int, watchlist_size: int,
        episode_length: int, keep_last_n: int = 3, n_eval_episodes: int = 5, verbose: int = 1,
        git_commit_override: str | None = None, total_timesteps: int = 0,
    ):
        super().__init__(verbose)
        self.dataset = dataset
        self.checkpoint_freq = checkpoint_freq
        self.watchlist_size = watchlist_size
        self.episode_length = episode_length
        self.keep_last_n = keep_last_n
        self.n_eval_episodes = n_eval_episodes
        self.git_commit = get_git_commit(git_commit_override)
        self.total_timesteps = total_timesteps
        self.best_val_reward = -np.inf
        self._run_checkpoints: list[pathlib.Path] = []
        self._last_checkpoint_step = 0
        self._firestore_db = self._connect_firestore()

        CHECKPOINTS_DIR.mkdir(parents=True, exist_ok=True)
        if BEST_SIDECAR_PATH.exists():
            try:
                prior_best = json.loads(BEST_SIDECAR_PATH.read_text())
                self.best_val_reward = prior_best.get("validation", {}).get("mean_episode_reward", -np.inf)
            except Exception:
                pass

        if not METRICS_LOG_PATH.exists():
            with open(METRICS_LOG_PATH, "w", newline="") as f:
                csv.writer(f).writerow([
                    "timestamp", "step", "git_commit", "train_mean_ep_reward",
                    "val_mean_episode_reward", "val_mean_realized_pnl",
                    "val_mean_win_rate", "val_mean_guardrail_violations", "is_best",
                ])

    @staticmethod
    def _connect_firestore():
        """One connection attempt at callback construction, reused for every checkpoint's push -
        see push_training_progress_to_firestore's javadoc for why this isn't reconnected per
        checkpoint. Returns None (silently) on any failure, same best-effort stance as the rest of
        this project's Firestore usage - never a dependency of training itself."""
        service_account_path = pathlib.Path(__file__).parent.parent.parent / "ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json"
        if not service_account_path.exists():
            print(f"Skipping training-progress Firestore push - no service account key at {service_account_path}")
            return None
        try:
            from google.cloud import firestore
            return firestore.Client.from_service_account_json(str(service_account_path))
        except Exception as e:
            print(f"Skipping training-progress Firestore push - {e}")
            return None

    def _on_step(self) -> bool:
        if self.num_timesteps - self._last_checkpoint_step < self.checkpoint_freq:
            return True
        self._last_checkpoint_step = self.num_timesteps
        self._checkpoint_and_eval()
        return True

    def _checkpoint_and_eval(self) -> None:
        step = self.num_timesteps
        val_metrics = run_validation_episodes(
            self.dataset, self.model, self.watchlist_size, self.episode_length,
            n_episodes=self.n_eval_episodes,
        )

        # Training-side rollout reward, if SB3's own ep_info_buffer has entries yet.
        train_mean_reward = float("nan")
        if len(self.model.ep_info_buffer) > 0:
            train_mean_reward = float(np.mean([ep["r"] for ep in self.model.ep_info_buffer]))

        ckpt_path = CHECKPOINTS_DIR / f"ppo_{step}.pth"
        self._save_pth(ckpt_path, step, val_metrics)
        self._run_checkpoints.append(ckpt_path)

        is_best = val_metrics["mean_episode_reward"] > self.best_val_reward
        if is_best:
            self.best_val_reward = val_metrics["mean_episode_reward"]
            self._save_pth(BEST_PATH, step, val_metrics, sidecar_path=BEST_SIDECAR_PATH)
            if self.verbose:
                print(f"[step {step}] new best.pth (val mean episode reward = {self.best_val_reward:.4f})")

        with open(METRICS_LOG_PATH, "a", newline="") as f:
            csv.writer(f).writerow([
                time.time(), step, self.git_commit, train_mean_reward,
                val_metrics["mean_episode_reward"], val_metrics["mean_realized_pnl"],
                val_metrics["mean_win_rate"], val_metrics["mean_guardrail_violations"], is_best,
            ])

        push_training_progress_to_firestore(
            self._firestore_db, self.git_commit, step, self.total_timesteps,
            train_mean_reward, val_metrics, is_best,
        )

        if self.verbose:
            print(f"[step {step}] train_ep_reward={train_mean_reward:.4f} "
                  f"val_ep_reward={val_metrics['mean_episode_reward']:.4f} "
                  f"val_pnl={val_metrics['mean_realized_pnl']:.1f} "
                  f"val_win_rate={val_metrics['mean_win_rate']:.3f} "
                  f"val_guardrail_violations={val_metrics['mean_guardrail_violations']:.1f}")

        self._prune_old_checkpoints()

    def _save_pth(self, path: pathlib.Path, step: int, val_metrics: dict, sidecar_path: pathlib.Path | None = None) -> None:
        import torch
        torch.save(self.model.policy.state_dict(), path)
        sidecar = sidecar_path if sidecar_path is not None else path.with_suffix(".json")
        sidecar.write_text(json.dumps({
            "step": step,
            "git_commit": self.git_commit,
            "saved_at": time.time(),
            "validation": val_metrics,
            # The exact item universe this checkpoint was trained against - a watchlisted item
            # outside this set has zero real training experience behind it, so the model's
            # suggestions for it are pure extrapolation (the same failure mode a prior version of
            # this project hit: an item showing up as a live candidate with zero rows in its own
            # training data). Recorded here (and pushed to Firestore once per run - see
            # push_trained_items_to_firestore) so it's checkable after the fact, not just implied
            # by whatever --max-items happened to be passed on the command line.
            "trained_item_ids": self.dataset.item_ids(),
        }, indent=2))

    def _prune_old_checkpoints(self) -> None:
        """Keeps best.pth (handled separately, never touched here) plus the last
        `keep_last_n` periodic checkpoints from this run; deletes older ones -
        per PROPOSAL.md's Decisions section ("Checkpoint retention: pruned")."""
        if len(self._run_checkpoints) <= self.keep_last_n:
            return
        to_delete = self._run_checkpoints[:-self.keep_last_n]
        self._run_checkpoints = self._run_checkpoints[-self.keep_last_n:]
        for ckpt in to_delete:
            ckpt.unlink(missing_ok=True)
            ckpt.with_suffix(".json").unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--timesteps", type=int, default=30_000, help="Total training timesteps (env steps).")
    parser.add_argument("--checkpoint-freq", type=int, default=50_000, help="Save+evaluate a checkpoint every N steps.")
    parser.add_argument("--keep-last-n", type=int, default=3, help="Periodic checkpoints to retain per run, besides best.pth.")
    parser.add_argument("--n-envs", type=int, default=4, help="Number of parallel env copies.")
    parser.add_argument("--vec-backend", choices=["dummy", "subproc"], default="dummy",
                         help="DummyVecEnv (single-process, correctness-first) or SubprocVecEnv (real parallelism).")
    parser.add_argument("--watchlist-size", type=int, default=8, help="Items tracked per episode.")
    parser.add_argument("--episode-length", type=int, default=2016, help="5-minute blocks per episode (2016 = 1 simulated week).")
    parser.add_argument("--max-items", type=int, default=60, help="Cap on eligible items loaded into RAM (keeps load time reasonable for short/dev runs).")
    parser.add_argument("--min-rows", type=int, default=500, help="Skip items with fewer than this many traded blocks.")
    parser.add_argument("--n-eval-episodes", type=int, default=5, help="Validation episodes rolled out per checkpoint.")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--git-commit", type=str, default=None,
                         help="Override the git commit checkpoints/Firestore are tagged with - required when running "
                              "against a plain file copy with no .git directory (e.g. code deployed via scp/tarball "
                              "to a rented GPU instance rather than a git clone), otherwise every checkpoint silently "
                              "gets tagged 'unknown'. Pass the local machine's `git rev-parse HEAD` output.")
    args = parser.parse_args()

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    git_commit = get_git_commit(args.git_commit)

    print("Loading market dataset into RAM...")
    dataset = load_market_dataset(max_items=args.max_items, min_rows=args.min_rows)
    push_trained_items_to_firestore(dataset, git_commit)

    env_fns = [
        make_env_fn(dataset, "train", args.watchlist_size, args.episode_length, args.seed + i)
        for i in range(args.n_envs)
    ]
    if args.vec_backend == "subproc":
        # Noted per the task spec: SubprocVecEnv gives real parallelism (separate
        # processes, no GIL contention) but pickles `dataset` (potentially large,
        # tens of items x thousands of rows x feature columns) into every worker
        # process. DummyVecEnv is the default here specifically because it's the
        # "fastest path to a correct, tested" setup for this validation run -
        # SubprocVecEnv is documented as the real-training-speed next step (see
        # README note below / PROPOSAL.md 3.5) rather than the default, per the
        # task's explicit "start with DummyVecEnv for correctness" instruction.
        vec_env = SubprocVecEnv(env_fns)
    else:
        vec_env = DummyVecEnv(env_fns)

    policy_kwargs = dict(net_arch=dict(pi=[256, 128, 128], vf=[256, 128, 128]))

    # Note on tensorboard: SB3 will log to it "for free" (no custom logger code
    # needed) if the `tensorboard` package happens to be installed, but it is
    # NOT part of this repo's existing dependency set and pulling it in isn't
    # "minimal extra code" (it's a sizable extra dependency for a nice-to-have) -
    # per the task's "no need for tensorboard/wandb unless SB3 gives it for free"
    # instruction, this intentionally omits tensorboard_log and relies on the
    # stdout progress output SB3 already prints (verbose=1) plus the CSV/JSON
    # metrics this script writes itself (training_log.csv, sidecar JSONs).
    # n_epochs=4/batch_size=64/gamma=0.95/ent_coef=0.02/lr=3e-4 (vs. SB3's PPO
    # defaults of n_epochs=10/batch_size=64/gamma=0.99/ent_coef=0.0/lr=3e-4):
    # ent_coef raised off its 0.0 default and gamma shortened from 0.99 to keep
    # the effective credit-assignment horizon more tractable relative to this
    # env's long (watchlist_size * episode_length_blocks, e.g. 4000-step)
    # episodes - found empirically during this task's validation runs to avoid
    # the policy collapsing onto a single constant action (see
    # GUARDRAIL_VIOLATION_PENALTY's comment in env.py for the other half of that
    # fix). n_epochs lowered from SB3's default 10 to reduce overfitting each
    # rollout batch before the next collection pass, standard PPO stability advice
    # for a small, fast-to-collect env like this one.
    model = PPO(
        "MlpPolicy",
        vec_env,
        policy_kwargs=policy_kwargs,
        n_steps=256,
        batch_size=64,
        n_epochs=4,
        gamma=0.95,
        ent_coef=0.02,
        learning_rate=3e-4,
        verbose=1,
        seed=args.seed,
    )

    callback = CheckpointAndEvalCallback(
        dataset=dataset,
        checkpoint_freq=args.checkpoint_freq,
        watchlist_size=args.watchlist_size,
        episode_length=args.episode_length,
        keep_last_n=args.keep_last_n,
        n_eval_episodes=args.n_eval_episodes,
        git_commit_override=args.git_commit,
        total_timesteps=args.timesteps,
    )

    print(f"Starting training for {args.timesteps:,} timesteps "
          f"({args.n_envs} envs, backend={args.vec_backend}, checkpoint every {args.checkpoint_freq:,} steps)...")
    model.learn(total_timesteps=args.timesteps, callback=callback, progress_bar=False)

    # Always checkpoint/evaluate once at the very end, even if it doesn't land
    # exactly on a checkpoint_freq boundary, so a short run always produces at
    # least one artifact.
    callback._checkpoint_and_eval()
    print(f"Training complete. Checkpoints in {CHECKPOINTS_DIR}, best.pth in {MODELS_DIR}, "
          f"metrics log at {METRICS_LOG_PATH}")


if __name__ == "__main__":
    main()
