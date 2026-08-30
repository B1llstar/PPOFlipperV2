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
│   └── afk-woodcutting/         # example plugin — copy this as a template
│       ├── build.gradle
│       └── src/main/java/net/runelite/client/plugins/microbot/afkwoodcutting/
│           ├── AfkWoodcuttingPlugin.java    # @PluginDescriptor, wires config/overlay/script
│           ├── AfkWoodcuttingScript.java    # the actual chop -> bank/drop -> repeat loop
│           ├── AfkWoodcuttingConfig.java    # in-client config panel
│           └── AfkWoodcuttingOverlay.java   # on-screen status overlay
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

MicroBot's client supports loading plugins from a local folder without
going through the in-client Plugin Hub. Point your client's side-load /
external-plugins directory at the built jar (check the client's settings
for the exact folder name/flag; it's covered in MicroBot's own docs under
`docs/`), copy or symlink the jar there, and restart/reload the client.

This is the entire distribution story for this repo: build locally,
side-load locally. There is intentionally no CI publishing step, no GitHub
Release, and no upstream PR — none of that machinery is needed unless you
later decide you want a plugin listed in the official Microbot-Hub
marketplace for other people to install.

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
