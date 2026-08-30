#!/usr/bin/env bash
# Starts the FlipperStar scoring service in the background, then runs the
# normal launch.sh unchanged. Separate script (not a modification of
# launch.sh) so the plain "just launch the game" path is untouched - this is
# an opt-in combined path for when you actually want FlipperStar available.
#
# The scoring service is killed automatically when this script exits for any
# reason (client closed, launch.sh failed, Ctrl-C) via the trap below - never
# leaves an orphaned uvicorn process behind.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="$REPO_DIR/data"
VENV_PYTHON="$DATA_DIR/venv/bin/python"
MODEL_PATH="$DATA_DIR/models/margin_model.txt"
SERVICE_HOST="127.0.0.1"
SERVICE_PORT="8420"
SERVICE_LOG="$DATA_DIR/service.log"

SERVICE_PID=""

cleanup() {
    if [ -n "$SERVICE_PID" ] && kill -0 "$SERVICE_PID" 2>/dev/null; then
        echo "==> Stopping FlipperStar scoring service (pid $SERVICE_PID)"
        kill "$SERVICE_PID" 2>/dev/null || true
        wait "$SERVICE_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

if [ ! -x "$VENV_PYTHON" ]; then
    echo "==> No venv found at $DATA_DIR/venv - set it up first:" >&2
    echo "    cd $DATA_DIR && /opt/homebrew/bin/python3.13 -m venv venv && source venv/bin/activate && pip install -r requirements.txt" >&2
    exit 1
fi

if [ ! -f "$MODEL_PATH" ]; then
    echo "==> No trained model at $MODEL_PATH - train one first:" >&2
    echo "    cd $DATA_DIR/pipeline && ../venv/bin/python train_model.py" >&2
    exit 1
fi

if curl -fsS "http://$SERVICE_HOST:$SERVICE_PORT/health" >/dev/null 2>&1; then
    echo "==> FlipperStar scoring service already running on $SERVICE_PORT, reusing it"
else
    echo "==> Starting FlipperStar scoring service (log: $SERVICE_LOG)"
    (
        cd "$DATA_DIR/service"
        exec "$VENV_PYTHON" -m uvicorn main:app --host "$SERVICE_HOST" --port "$SERVICE_PORT"
    ) >"$SERVICE_LOG" 2>&1 &
    SERVICE_PID=$!

    echo "==> Waiting for scoring service to become healthy..."
    for _ in $(seq 1 30); do
        if curl -fsS "http://$SERVICE_HOST:$SERVICE_PORT/health" >/dev/null 2>&1; then
            echo "==> Scoring service healthy (pid $SERVICE_PID)"
            break
        fi
        if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
            echo "==> Scoring service process died on startup - see $SERVICE_LOG" >&2
            exit 1
        fi
        sleep 1
    done
fi

echo "==> Launching MicroBot client"
"$REPO_DIR/scripts/launch.sh"
