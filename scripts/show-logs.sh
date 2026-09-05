#!/usr/bin/env bash
# Brings up (live-tails) one of PPOFlipperStar's or nmz-debug's log files, or RuneLite's own
# shared client.log. Run with no argument for an interactive menu, or pass a name directly:
#
#   ./scripts/show-logs.sh ppo     # ~/.runelite/ppoflipperstar-decide.log
#   ./scripts/show-logs.sh nmz     # ~/.runelite/nmzdebug-disconnect.log
#   ./scripts/show-logs.sh client  # ~/.runelite/logs/client.log (everything, all plugins)
#
# Windows equivalent: scripts/show-logs.ps1 (same options, same file locations under
# %USERPROFILE%\.runelite - RuneLite uses that same directory layout on every platform).
set -uo pipefail

RUNELITE_DIR="${RUNELITE_DIR:-$HOME/.runelite}"
PPO_LOG="$RUNELITE_DIR/ppoflipperstar-decide.log"
NMZ_LOG="$RUNELITE_DIR/nmzdebug-disconnect.log"
CLIENT_LOG="$RUNELITE_DIR/logs/client.log"

tail_log() {
    local path="$1"
    local label="$2"
    if [ ! -f "$path" ]; then
        echo "==> $label not found yet at $path"
        echo "    (it's created the first time the plugin actually logs something - e.g. after Execute is clicked, or a decide tick/disconnect happens)"
        exit 1
    fi
    echo "==> Tailing $label ($path) - Ctrl+C to stop"
    echo
    tail -n 40 -f "$path"
}

choose_and_tail() {
    case "$1" in
        ppo|ppoflipperstar|flipper)
            tail_log "$PPO_LOG" "PPOFlipperStar decide log"
            ;;
        nmz|nmzdebug)
            tail_log "$NMZ_LOG" "nmz-debug disconnect log"
            ;;
        client|runelite)
            tail_log "$CLIENT_LOG" "RuneLite client.log (all plugins)"
            ;;
        *)
            echo "Unknown log '$1'. Valid options: ppo, nmz, client" >&2
            exit 1
            ;;
    esac
}

if [ $# -ge 1 ]; then
    choose_and_tail "$1"
    exit 0
fi

echo "Which log do you want to tail?"
echo "  1) PPOFlipperStar decide log  ($PPO_LOG)"
echo "  2) nmz-debug disconnect log   ($NMZ_LOG)"
echo "  3) RuneLite client.log        ($CLIENT_LOG, everything - all plugins)"
read -r -p "Enter 1, 2, or 3: " choice

case "$choice" in
    1) choose_and_tail ppo ;;
    2) choose_and_tail nmz ;;
    3) choose_and_tail client ;;
    *) echo "Not a valid choice." >&2; exit 1 ;;
esac
