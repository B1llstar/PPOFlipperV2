#!/usr/bin/env bash
# Starts the PPO inference worker in the background, then runs the normal
# launch.sh unchanged. Separate script (not a modification of launch.sh) so
# the plain "just launch the game" path is untouched - this is an opt-in
# combined path for when you actually want PPOFlipperStar's DECIDE phase to
# have something answering it.
#
# Unlike the FlipperStar scoring service (an HTTP server with a /health
# endpoint - see launch-with-flipper.sh), the inference worker is a Firestore
# listener with no HTTP surface to poll for readiness. It also doesn't need
# an account hash passed in: it auto-discovers accounts via
# accounts/{accountHash}/presence/heartbeat, which PPOFlipperStarFirestoreSync
# only starts refreshing once you're actually logged in inside the client -
# so it's normal and expected for the worker's log to show nothing happening
# until you log in, at which point it should pick your account up within
# about a minute (see inference_worker.py's WorkerSupervisor.SCAN_INTERVAL_SECONDS).
#
# The worker is killed automatically when this script exits for any reason
# (client closed, launch.sh failed, Ctrl-C) via the trap below - never leaves
# an orphaned Python process behind.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="$REPO_DIR/data"
VENV_PYTHON="$DATA_DIR/venv/bin/python"
CHECKPOINT_PATH="$DATA_DIR/models/ppo/best.pth"
WORKER_LOG="$DATA_DIR/ppo/inference_worker.log"

WORKER_PID=""

cleanup() {
    if [ -n "$WORKER_PID" ] && kill -0 "$WORKER_PID" 2>/dev/null; then
        echo "==> Stopping PPO inference worker (pid $WORKER_PID)"
        kill "$WORKER_PID" 2>/dev/null || true
        wait "$WORKER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

if [ ! -x "$VENV_PYTHON" ]; then
    echo "==> No venv found at $DATA_DIR/venv - set it up first:" >&2
    echo "    cd $DATA_DIR && /opt/homebrew/bin/python3.13 -m venv venv && source venv/bin/activate && pip install -r requirements.txt" >&2
    exit 1
fi

if [ ! -f "$CHECKPOINT_PATH" ]; then
    echo "==> No trained checkpoint at $CHECKPOINT_PATH - train one first:" >&2
    echo "    cd $DATA_DIR/ppo && ../venv/bin/python train.py" >&2
    exit 1
fi

echo "==> Starting PPO inference worker, auto-discovering accounts via presence (log: $WORKER_LOG)"
(
    cd "$DATA_DIR/ppo"
    exec "$VENV_PYTHON" inference_worker.py --checkpoint "$CHECKPOINT_PATH"
) >"$WORKER_LOG" 2>&1 &
WORKER_PID=$!

# No /health endpoint to poll (this is a listener, not a server) - just
# confirm the process didn't die immediately (a bad checkpoint, a malformed
# service account key, a missing dependency) before moving on.
sleep 2
if ! kill -0 "$WORKER_PID" 2>/dev/null; then
    echo "==> PPO inference worker process died on startup - see $WORKER_LOG" >&2
    exit 1
fi
echo "==> PPO inference worker running (pid $WORKER_PID) - shadow-mode suggestions will appear in the panel once an account's presence is detected."

echo "==> Launching MicroBot client"
"$REPO_DIR/scripts/launch.sh"
