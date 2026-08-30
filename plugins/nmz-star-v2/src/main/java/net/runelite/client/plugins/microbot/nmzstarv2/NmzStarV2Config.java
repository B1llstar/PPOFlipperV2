package net.runelite.client.plugins.microbot.nmzstarv2;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("nmzstarv2")
public interface NmzStarV2Config extends Config {

    @ConfigSection(
        name = "Combat",
        description = "Combat behavior inside the dream",
        position = 0,
        closedByDefault = false
    )
    String combatSection = "combat";

    @ConfigItem(
        keyName = "attackNearest",
        name = "Attack nearest boss",
        description = "If on, always attacks whichever rotated-in boss is closest instead of a preferred target",
        position = 0,
        section = combatSection
    )
    default boolean attackNearest() {
        return true;
    }

    @ConfigItem(
        keyName = "walkToCenter",
        name = "Walk to arena center",
        description = "Keep pathing back to the middle of the arena between fights",
        position = 1,
        section = combatSection
    )
    default boolean walkToCenter() {
        return true;
    }

    @ConfigItem(
        keyName = "stopAfterDeath",
        name = "Stop after death",
        description = "Shut the plugin down if the player dies",
        position = 2,
        section = combatSection
    )
    default boolean stopAfterDeath() {
        return true;
    }
}
