# BotStar

Personal MicroBot plugins. This repo builds its own scripts against the
MicroBot client and side-loads them locally — it does not go through the
official Microbot-Hub marketplace and does not open PRs upstream.

## What this repo is, and what it depends on

Three different repositories are in play here, and they relate to this one
in three different ways. This is the part that's easy to get backwards, so
it's spelled out explicitly:

| Repo | Relationship | Why |
|---|---|---|
| [chsami/microbot](https://github.com/chsami/microbot) | **Binary dependency only** — never cloned, never forked | We only need the compiled client jar (the `Plugin`/`Script` API, game-state caches, utility classes like `Rs2Bank`/`Rs2Inventory`). We never touch or need its source. |
| [chsami/Microbot-Hub](https://github.com/chsami/Microbot-Hub) | **Reference-only git submodule** at `vendor/microbot-hub/` | Not a dependency — there's no publishable artifact of "the Hub" to depend on. It's a monorepo of other people's plugin *source*. We keep a local read-only mirror to grep through for patterns and utility usage, but nothing in this repo's build ever compiles or links against it. |
| `BotStar` (this repo) | **Source of truth** | Your own plugins, own history, own build. |

### Why `chsami/microbot` is a dependency, not a clone

The MicroBot client publishes compiled jars as GitHub releases
(`microbot-<version>.jar`). Our root [build.gradle](build.gradle) resolves
that jar the same way Microbot-Hub's own build does: via a custom Ivy
repository pointed at the release URL pattern, with the client added as a
`compileOnly` dependency. We link against the published API surface, same
as any Maven/Gradle dependency — there's no reason to have their source on
disk.

### Why `chsami/Microbot-Hub` is a submodule, not a dependency

Microbot-Hub has no build artifact that represents "the whole Hub" — it's a
collection of independently-built plugins, each compiled separately against
the MicroBot client. There is nothing to add to `dependencies {}`. The only
thing worth pulling from it is *specific plugins' source code*, as a
one-time copy — e.g. "grab AutoFishingPlugin as a starting point, then make
it mine." A submodule gets you an easy, periodically-refreshable local copy
to read and copy from, without ever becoming a build input:

```bash
# refresh the reference copy to Microbot-Hub's latest
git submodule update --remote vendor/microbot-hub

# grep it for a utility or pattern
grep -rn "Rs2Bank.walkToBank" vendor/microbot-hub/src

# lift a specific plugin's source as a starting point for your own
cp -r vendor/microbot-hub/src/main/java/net/runelite/client/plugins/microbot/somepluginname \
      plugins/my-new-plugin/src/main/java/net/runelite/client/plugins/microbot/mynewplugin
```

From that point on, the copied code is yours — there's no ongoing sync. If
Microbot-Hub improves the original later, you get that only by copying the
diff yourself.

## Staying up to date, every build

There's nothing to "keep in sync" manually for the client. `build.gradle`
queries `https://microbot.cloud/api/version/client` on every build and
resolves whatever version comes back (falling back to a pinned default if
the endpoint is unreachable, e.g. offline). This means:

- A normal `./gradlew build` always compiles against the current MicroBot
  client release.
- Pin a specific version instead with `-PmicrobotClientVersion=<version>`.
- Build fully offline against a local client jar you already have with
  `-PmicrobotClientPath=/absolute/path/to/microbot-<version>.jar`.

The `vendor/microbot-hub` submodule does *not* auto-update on its own —
submodules are pinned to a specific commit on purpose, so your reference
copy doesn't silently drift while you're mid-edit. Refresh it deliberately
with `git submodule update --remote vendor/microbot-hub` whenever you want
the latest Hub source to browse.

## Layout

```
BotStar/
├── build.gradle                # root: resolves the MicroBot client jar, shared JDK/config
├── settings.gradle              # declares plugin subprojects
├── gradlew, gradle/wrapper/     # self-contained Gradle 8.2 wrapper
├── plugins/
│   ├── afk-woodcutting/          # example plugin — copy this as a template
│   │   ├── build.gradle
│   │   └── src/main/java/net/runelite/client/plugins/microbot/afkwoodcutting/
│   │       ├── AfkWoodcuttingPlugin.java    # @PluginDescriptor, wires config/overlay/script
│   │       ├── AfkWoodcuttingScript.java    # the actual chop -> bank/drop -> repeat loop
│   │       ├── AfkWoodcuttingConfig.java    # in-client config panel
│   │       └── AfkWoodcuttingOverlay.java   # on-screen status overlay
│   └── nmz-star-v2/              # Nightmare Zone combat bot skeleton (WIP, see its docs/README.md)
│       ├── build.gradle
│       └── src/main/java/net/runelite/client/plugins/microbot/nmzstarv2/
│           ├── NmzStarV2Plugin.java   # @PluginDescriptor, wires config/overlay/script
│           ├── NmzStarV2Script.java   # dream -> navigate -> attack state machine (stubbed)
│           ├── NmzStarV2Config.java   # in-client config panel
│           ├── NmzStarV2Overlay.java  # on-screen state/target overlay
│           └── NmzNpcIds.java         # verified NMZ boss/host NPC ids
└── vendor/microbot-hub/          # git submodule, reference-only, never built
```

## Building

```bash
# build everything, against the latest MicroBot client
./gradlew build

# build one plugin
./gradlew :plugins:afk-woodcutting:build

# pin a client version
./gradlew build -PmicrobotClientVersion=2.6.9

# offline, against a local client jar
./gradlew build -PmicrobotClientPath=/path/to/microbot-2.6.9.jar
```

Each plugin subproject produces a shaded jar under
`plugins/<name>/build/libs/`.

## Running it (side-loading — no marketplace, no PR)

```bash
./scripts/launch.sh
```

This does everything, fresh, every time — nothing here is a one-off manual
step:

1. Resolves the latest MicroBot client version from
   `https://microbot.cloud/api/version/client` (falls back to the newest
   already-downloaded jar if offline).
2. Downloads `microbot-<version>.jar` from
   [chsami/Microbot releases](https://github.com/chsami/Microbot/releases)
   into `~/microbot-client/` if it isn't already cached there.
3. Runs `./gradlew build -PmicrobotClientVersion=<version>`, so every
   plugin in `plugins/` compiles against that exact client build.
4. Copies every plugin's built jar into `~/.runelite/sideloaded-plugins/` —
   this is RuneLite's real side-load directory, backing
   `PluginManager.loadSideLoadPlugins()` / `PluginManager.SIDELOADED_PLUGINS`
   (`new File(RuneLite.RUNELITE_DIR, "sideloaded-plugins")`), auto-scanned
   on client startup. No client settings/flags to configure by hand.

   Note: `~/.runelite/plugins/` (`RuneLite.PLUGINS_DIR`) is a *different*
   directory used by `ExternalPluginManager` for plugins matched against
   the official Plugin Hub manifest — an unlisted jar dropped there is
   silently ignored, with no log line at all. This was verified the hard
   way: jars copied there across several sessions never once produced a
   "Plugin loaded" line, which sent us back to inspect
   `PluginManager.class` directly (see
   [plugins/VERIFYING_THE_API.md](plugins/VERIFYING_THE_API.md) for the
   verify-against-the-jar approach) and find the actual field.
5. Launches the client jar with the JDK 11 runtime Gradle already
   provisioned (`~/.gradle/jdks/`), so no separate system Java install is
   required.

This is the entire distribution story for this repo: one script, always
current, local only. There is intentionally no CI publishing step, no
GitHub Release, and no upstream PR — none of that machinery is needed
unless you later decide you want a plugin listed in the official
Microbot-Hub marketplace for other people to install.

Note: if the client has saved login credentials from a previous session
that are no longer valid, it will show failed auto-login attempts on
startup — that's Jagex account state on your machine, unrelated to this
repo's build. Log in manually from the client window when that happens.

**With FlipperStar's scoring service:** `./scripts/launch-with-flipper.sh`
starts the FlipperStar scoring service (`data/service/`) in the background,
waits for it to report healthy, then runs `launch.sh` as normal — one
command instead of running both separately. Kills the service automatically
when the client closes. A separate script, not a flag on `launch.sh`, so
the plain path stays untouched when you don't need the scoring service
running (e.g. testing GE Star V2 alone). See `data/README.md`'s "Scoring
service" section for details, and `plugins/flipper-star/`'s docs for what
consumes it.

On macOS, both scripts also have Launchpad icons ("BotStar Launcher" and
"BotStar + FlipperStar") for a one-click launch without opening a terminal
manually.

**Viewing plugin logs:** `./scripts/show-logs.sh` (macOS/Linux) or
`.\scripts\show-logs.ps1` (Windows) live-tails PPOFlipperStar's decide log,
nmz-debug's disconnect log, or RuneLite's shared `client.log` — run with no
argument for an interactive menu, or pass `ppo`, `nmz`, or `client` directly
(e.g. `./scripts/show-logs.sh ppo`). Both scripts read from the same
`~/.runelite`/`%USERPROFILE%\.runelite` layout `launch.sh`/
`setup-windows.ps1` already use.

## Adding a new plugin

1. `mkdir -p plugins/<name>/src/main/java/net/runelite/client/plugins/microbot/<name>`
2. Add `plugins/<name>/build.gradle` (copy `plugins/afk-woodcutting/build.gradle`
   and rename the archive base name).
3. Add `include ':plugins:<name>'` to [settings.gradle](settings.gradle).
4. Write `<Name>Plugin.java` with a `@PluginDescriptor` (required fields:
   `name`, `version`, `minClientVersion`), a `Script` subclass with the real
   loop, and a `Config` interface if it needs settings. Use
   `plugins/afk-woodcutting` as the template.
5. `./gradlew :plugins:<name>:build`, then side-load the resulting jar.

### Threading rule (from MicroBot's own docs)

Widget access, uncached game objects, varbits, and similar client-state
reads must run on the client thread:

```java
Microbot.getClientThread().invoke(() -> {
    // client-thread-only code
});
```

`@Subscribe` event handlers (`onGameTick`, etc.) already run on the client
thread automatically — no need to wrap those.

## PPOFlipperStar web dashboard (Firebase Hosting)

`firebase/web/` is a Vue 3 dashboard that reads PPOFlipperStar's Firestore
data (portfolio, trade history, buy-limit ledger, watchlist, live model
suggestions) read-only, for observability — it can never place trades. It
deploys to Firebase Hosting under the `ppoflipperopus` project, live at
https://ppoflipperopus.web.app. See
[plugins/ppo-flipper-star/PROPOSAL.md](plugins/ppo-flipper-star/PROPOSAL.md)
§4 for the full Firestore schema it reads.

### Deploying an update

```bash
cd firebase/web
npm install
npm run build              # writes into ../public/, the Hosting root

cd ..
firebase deploy --only hosting --project ppoflipperopus
```

If `firestore.rules` or `firestore.indexes.json` changed too (e.g. a new
collection the dashboard needs to read), deploy those in the same pass:

```bash
firebase deploy --only firestore:rules,firestore:indexes,hosting --project ppoflipperopus
```

`firebase/public/` is gitignored (build output only, same convention as
`firebase/functions/lib/`) — always rebuild before deploying, don't deploy
stale output.

### Adding a new user to the dashboard's email allowlist

Access is gated by a hardcoded email allowlist in `firebase/firestore.rules`
(`isAllowlistedDashboardViewer()`), not an open sign-in — anyone can sign in
with Google, but only an allowlisted email can actually read any data.

1. Edit the email array in `firebase/firestore.rules`:
   ```
   function isAllowlistedDashboardViewer() {
     return request.auth != null
       && request.auth.token.email != null
       && request.auth.token.email in [
         'billborkowski7@gmail.com',
         'crigne4lyfe@gmail.com',
         'new-person@example.com'   // add here
       ];
   }
   ```
2. Deploy just the rules: `firebase deploy --only firestore:rules --project ppoflipperopus`
   (no need to rebuild/redeploy hosting for an allowlist change).
3. Also update `ALLOWLISTED_EMAILS` in
   `firebase/web/src/firebase/config.js` to match — it's not what enforces
   access (the rules above are), but it's what the dashboard uses to show a
   fast, clear "you don't have access" message instead of letting a
   non-allowlisted user sign in and only then hit permission-denied errors
   on every read.

One-time setup this all depends on (already done for the current project,
listed here in case the project is ever recreated): Firebase console →
`ppoflipperopus` → Authentication → Sign-in method → enable the **Google**
provider.
