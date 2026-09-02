# Running PPOFlipperStar on a Linux alt (headless, and scaling to a fleet)

## The short version

Everything on the Firestore/model side is already fleet-ready — zero changes
needed. The only real work is on the RuneLite client side: it needs a
display to render into even when nobody's watching, and login can't be
automated with a plaintext username/password, so there's a one-time
per-account interactive step before it can run unattended.

## Why the Firestore/model side needs nothing extra

- `data/ppo/inference_worker.py`'s `WorkerSupervisor` already auto-discovers
  every account by scanning `accounts/*/presence/heartbeat` in Firestore -
  one worker process can serve your entire fleet, no per-machine config, no
  `--account-hash` needed anywhere.
- Every account is keyed by its real Jagex `accountHash`, so portfolio,
  buy-limit ledger, watchlist, and trade history all stay naturally isolated
  per account with no extra work on your part.
- The web dashboard (https://ppoflipperopus.web.app) already lists every
  account under your allowlisted Google login.
- `scripts/launch.sh` / `scripts/launch-with-ppo.sh` are plain, portable bash
  - `$HOME`-relative paths, `curl`/`gradlew`/`java`, no macOS-specific
  anything. They run on Linux as-is.

## What actually needs setup: a display, and login

### 1. Virtual display (Xvfb)

RuneLite's client (Swing UI + OpenGL rendering) needs *some* display target,
even fully headless with no monitor ever attached:

```bash
sudo apt install xvfb
Xvfb :99 -screen 0 1280x1024x24 &
export DISPLAY=:99
```

Each running client instance needs its own display number (`:99`, `:100`,
`:101`, ...) if you're running more than one on the same box.

Wrap the existing launch scripts with the display set, e.g.:

```bash
DISPLAY=:99 ./scripts/launch-with-ppo.sh
```

or use `xvfb-run -a ./scripts/launch-with-ppo.sh` to have it spin up and
tear down a throwaway display automatically for a single run.

### 2. Login - no shortcut exists, verified against the actual client jar

Checked directly against the running `microbot-2.6.21.jar`'s bytecode (not
just docs) for every plausible headless-login mechanism:

- RuneLite's own CLI flags (`--session <file>`, `--proxy`,
  `--clean-jagex-launcher`, `--insecure-write-credentials`) - none accept a
  raw username/password.
- `~/.runelite/credentials.properties` (the file behind the "read 5
  credentials from disk" log line) - this is a **cached Jagex OAuth
  session** (`JX_CHARACTER_ID`, `JX_SESSION_ID`, `JX_REFRESH_TOKEN`,
  `JX_DISPLAY_NAME`, `JX_ACCESS_TOKEN`), written only after a human
  completes the real Jagex login flow once. It lets a *returning* session
  skip re-authentication - it is not something you can hand-populate with a
  plaintext password to bootstrap a brand-new login.
- Microbot's `LoginManager`/`Login` classes pull credentials from an
  existing encrypted RuneLite config profile - itself only ever populated
  through the GUI.
- The `agentserver` plugin's local HTTP API (`127.0.0.1:8081`, token-authed)
  has a `POST /login` endpoint, but its request body only accepts
  `world`/`wait`/`timeout` - it triggers login against whatever profile is
  already configured, it cannot inject new credentials.

**Conclusion: every path bottoms out at a pre-existing encrypted profile or
cached session that a human must create once via the visible login UI.**
There is no bulk-credentials file or JVM-flag injection point in this
version of Microbot.

### The practical workflow this implies

**One-time setup per account** (needs a display you can actually see):

1. Start Xvfb and a VNC server pointed at it:
   ```bash
   Xvfb :99 -screen 0 1280x1024x24 &
   x11vnc -display :99 -forever -shared &
   ```
2. VNC in from your main machine, then run:
   ```bash
   DISPLAY=:99 ./scripts/launch.sh
   ```
3. Log into the alt account through the normal Jagex login UI.
4. This populates `~/.runelite/credentials.properties` and the RuneLite
   config profile for that account. Close the client.

**Every subsequent launch** (fully headless, no VNC needed):

- The cached session in `credentials.properties` should let relaunches
  under Xvfb alone resume without hitting the login screen again, until the
  token expires/needs refreshing (RuneLite handles refresh automatically in
  most cases). **This is stated as expected behavior, not yet verified
  empirically in this project** - confirm it holds on your first alt before
  relying on it for a whole fleet.

## Scaling to many accounts

- Each instance needs its own `$HOME` (or at minimum its own
  `~/.runelite/` directory), since credentials and config are account-
  specific - run each instance under a separate Linux user, or override
  whatever home-directory env var Microbot respects, or containerize one
  account per container.
- Each instance needs its own Xvfb display number, even ones you never VNC
  into again after the one-time login.
- The Python side needs nothing new - one `inference_worker.py` process
  already serves every account it discovers via presence heartbeats.

## Open follow-up

A small provisioning script (spin up an isolated Xvfb display + home
directory + launch, parameterized by an account label, so adding account
N+1 is a one-liner) was proposed but not yet built - worth doing once the
first headless alt is confirmed working end-to-end, so the pattern is
proven before automating it.
