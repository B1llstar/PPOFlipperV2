# Verifying the MicroBot client API before writing a plugin

Two different things determine what a plugin looks like, and they live in
two different places. Don't rely on memory or docs alone for either —
verify against what's actually on disk.

## 1. The authoring contract (plugin shape, not API signatures)

Where: `vendor/microbot-hub/AGENTS.md` (mirrored as `CLAUDE.md`), plus
`vendor/microbot-hub/docs/`.

This covers things that don't change between client releases: the
`@PluginDescriptor` required fields (`name`, `version`, `minClientVersion`),
the `Plugin`/`Script`/`Config`/`Overlay` file layout convention, the
client-thread rule (`Microbot.getClientThread().invoke(...)` for widgets/
varbits/uncached objects, not needed inside `@Subscribe` handlers), and the
debugging/hot-reload workflow. Read this before structuring a new plugin.

## 2. The actual API surface (class names, method signatures)

Where: **the resolved client jar itself**, not any doc or memory of a past
API. AGENTS.md says as much directly (`vendor/microbot-hub/AGENTS.md:175`):
the authoritative client source is a separate `Microbot` checkout, which we
don't have here — this repo only pulls the compiled jar as a dependency
(see root [README.md](../README.md)). Docs and examples go stale as the
client evolves (e.g. utility classes get deprecated and replaced); the jar
is always current because it's re-resolved every build.

**Before using a class/method you haven't verified against this specific
client version, check the real jar:**

```bash
# find the jar Gradle already resolved for the current build
find ~/.gradle/caches/modules-2/files-2.1/com.microbot -iname "microbot-*.jar"

# list classes matching a name
unzip -l <path-to-jar> | grep -i Rs2Bank

# inspect real method signatures on a class
mkdir -p /tmp/microbot-inspect && cd /tmp/microbot-inspect
unzip -o -q <path-to-jar> "net/runelite/client/plugins/microbot/util/bank/Rs2Bank.class"
javap net/runelite/client/plugins/microbot/util/bank/Rs2Bank.class
```

This caught two real mismatches while scaffolding `afk-woodcutting`:
`OverlayPanel` actually lives under `net.runelite.client.ui.overlay`, not
`net.runelite.client.plugins.microbot.util.overlay`; and
`Rs2GameObject.findObject(String)` doesn't exist — the working call is
`Rs2GameObject.interact(String name, String action)`.

`Rs2GameObject` itself compiles with a `[removal]` deprecation warning as
of client 2.6.21 — the debugging notes mention a newer "Queryable API"
superseding it, but its replacement isn't documented anywhere in this
submodule yet. Re-check `javap` on it before starting a new plugin, in
case it's gone by then.

This same verify-don't-assume rule applies beyond API *classes* — it
caught a real, costly mistake in the *side-load directory* claim in the
root README. `~/.runelite/plugins/` (`RuneLite.PLUGINS_DIR`) looks like
the obvious answer and was asserted there as fact, but it's actually
consumed by `ExternalPluginManager` against the official Plugin Hub
manifest — an unlisted jar dropped there is silently ignored, with zero
log output either way. Across seven separate build-and-launch sessions,
our own side-loaded jars never once produced a "Plugin loaded" line, and
the silence looked identical to "plugin loaded fine but its scheduled
loop never fires" — which is what sent us chasing a phantom logic bug for
a while first. The real directory, found by `javap`-ing
`PluginManager.class` directly instead of trusting the earlier note:

```bash
unzip -o -q <path-to-jar> "net/runelite/client/plugins/PluginManager.class"
javap -c -p -constants net/runelite/client/plugins/PluginManager.class \
  | grep -B15 "SIDELOADED_PLUGINS:Ljava/io/File;"
# -> new File(RuneLite.RUNELITE_DIR, "sideloaded-plugins")
```

`~/.runelite/sideloaded-plugins/` is the real one, backing
`PluginManager.loadSideLoadPlugins()`. `scripts/launch.sh` now targets it.

## 3. Reference plugins

Where: `vendor/microbot-hub/src/main/java/net/runelite/client/plugins/microbot/`
once you've pulled a specific plugin's source in (see root README's
"Why `chsami/Microbot-Hub` is a submodule" section) — real, working
examples of both the contract and the API used together.
