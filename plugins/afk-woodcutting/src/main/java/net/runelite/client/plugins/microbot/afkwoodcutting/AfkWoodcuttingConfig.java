package net.runelite.client.plugins.microbot.afkwoodcutting;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("afkwoodcutting")
public interface AfkWoodcuttingConfig extends Config {

    @ConfigItem(
        keyName = "treeName",
        name = "Tree",
        description = "The tree object name to chop, e.g. 'Tree', 'Oak', 'Willow'",
        position = 0
    )
    default String treeName() {
        return "Tree";
    }

    @ConfigItem(
        keyName = "dropLogs",
        name = "Drop logs instead of banking",
        description = "If enabled, drop logs when the inventory is full instead of walking to a bank",
        position = 1
    )
    default boolean dropLogs() {
        return true;
    }
}
