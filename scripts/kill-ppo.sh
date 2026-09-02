#!/usr/bin/env bash
# Force-stops every process belonging to the PPOFlipperStar stack: the
# MicroBot/RuneLite client, the Python inference worker, and the
# launch-with-ppo.sh wrapper script - in case any of them got left running
# (e.g. the client froze and had to be force-quit, orphaning the worker) or
# you just want an immediate, no-questions-asked stop.
#
# Deliberately matches processes by their exact command line (the client
# jar's full path, inference_worker.py's script name, launch-with-ppo.sh's
# script name) rather than a broad `pkill java`/`pkill python` - this must
# never kill an unrelated Java or Python process on this machine (a Gradle
# daemon, another script, etc).
#
# This does NOT cancel any GE offers already live in-game - if the client
# was mid-trade when killed, those offers are exactly as they were left
# in-game; log back in and use the panel's "Cancel all offers" button, or
# handle them manually, once you're ready to look at the account again.
set -uo pipefail

echo "==> Looking for PPOFlipperStar-related processes..."

killed_any=0

kill_matching() {
    local pattern="$1"
    local label="$2"
    local pids
    pids=$(pgrep -f "$pattern" 2>/dev/null || true)
    if [ -z "$pids" ]; then
        return
    fi
    for pid in $pids; do
        echo "==> Killing $label (pid $pid)"
        kill "$pid" 2>/dev/null || true
        killed_any=1
    done
}

# Order matters only cosmetically here (nothing depends on shutdown sequence
# the way startup does) - the wrapper script's own trap-based cleanup won't
# fire reliably if the client already froze/died out from under it, so every
# piece is killed independently and unconditionally.
kill_matching "microbot-.*\.jar" "MicroBot client"
kill_matching "inference_worker\.py" "PPO inference worker"
kill_matching "launch-with-ppo\.sh" "launch-with-ppo.sh wrapper"

if [ "$killed_any" -eq 0 ]; then
    echo "==> Nothing found running - already stopped."
    exit 0
fi

# Give normal SIGTERM a moment before checking for stragglers.
sleep 2

force_killed_any=0
for pattern_label in "microbot-.*\.jar:MicroBot client" "inference_worker\.py:PPO inference worker" "launch-with-ppo\.sh:launch-with-ppo.sh wrapper"; do
    pattern="${pattern_label%%:*}"
    label="${pattern_label#*:}"
    pids=$(pgrep -f "$pattern" 2>/dev/null || true)
    for pid in $pids; do
        echo "==> $label (pid $pid) still alive after SIGTERM, sending SIGKILL"
        kill -9 "$pid" 2>/dev/null || true
        force_killed_any=1
    done
done

if [ "$force_killed_any" -eq 1 ]; then
    echo "==> Done (some processes needed a forced kill)."
else
    echo "==> Done."
fi
