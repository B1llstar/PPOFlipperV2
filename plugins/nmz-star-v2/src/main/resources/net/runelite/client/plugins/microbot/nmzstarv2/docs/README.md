# Nmz Star V2

Skeleton Nightmare Zone combat plugin, built as a standalone state machine
rather than a copy of Microbot-Hub's `nmz` plugin (kept at
`vendor/microbot-hub/.../nmz/` for reference only — see root README for why
that repo is never a build dependency).

## Current state: skeleton, not a finished bot

This compiles and runs the happy path — walk to the NMZ entrance, start a
dream, find the nearest rotated-in boss via `NmzNpcIds`, right-click
`Attack`, repeat — but real combat/potion/prayer/shop logic is stubbed out
with `TODO`s in [NmzStarV2Script.java](../../../../../../../../java/net/runelite/client/plugins/microbot/nmzstarv2/NmzStarV2Script.java).
Fill in against a live client before relying on it unattended.

## Files

- `NmzStarV2Plugin.java` — `@PluginDescriptor`, wires config/overlay/script, stops on death if configured.
- `NmzStarV2Script.java` — the state machine: `IDLE -> WALKING_TO_ENTRANCE -> STARTING_DREAM -> IN_ARENA_NAVIGATE/IN_ARENA_COMBAT`.
- `NmzStarV2Config.java` — attack-nearest / walk-to-center / stop-after-death toggles.
- `NmzStarV2Overlay.java` — shows current state and target name.
- `NmzNpcIds.java` — every NMZ boss id (normal + hard mode), pulled directly from
  `net.runelite.api.gameval.NpcID` in the Microbot client jar. The dream host
  (`Dominic Onion`) is looked up by id too (`NZONE_HOST`).

## What's stubbed and needs live verification

- `startDream()` — real flow needs the dream-select widget (pick "Previous:",
  confirm, handle the "Agree to pay" numeric prompt). Reference:
  `vendor/microbot-hub/.../nmz/NmzScript.java#startNmzDream`.
- `isInsideArena()` — uses the same `y > 4500` heuristic as the reference
  plugin; unverified against a live client.
- `navigateArena()` — arena-center `WorldPoint` and power-up orb pickup
  (`NZONE_POWERUP_ZAPPER` / `_DAMAGEMULTIPLIER` / `_SPECIALATTACK`) not wired up.
- `managePrayer()`, `managePotions()`, `manageRewardShop()` — empty hooks.

## Building

```bash
./gradlew :plugins:nmz-star-v2:build
```
