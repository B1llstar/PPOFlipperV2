package net.runelite.client.plugins.microbot.flipperstar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("flipperstar")
@ConfigInformation(
    "Scans currently-liquid Grand Exchange items via a local scoring service (a trained " +
    "margin-prediction model, see data/ at the repo root) and queues promising flips into " +
    "GE Star V2's order queue - GE Star V2 must also be running to actually execute anything, " +
    "FlipperStar only decides what to queue.<br /><br />" +
    "Requires the scoring service running locally: <code>cd data/service && uvicorn main:app " +
    "--host 127.0.0.1 --port 8420</code><br /><br />" +
    "made by billstar"
)
public interface FlipperStarConfig extends Config {

    @ConfigSection(
        name = "Scoring service",
        description = "Connection to the local scoring service",
        position = 0,
        closedByDefault = false
    )
    String serviceSection = "service";

    @ConfigItem(
        keyName = "serviceUrl",
        name = "Service URL",
        description = "Base URL of the scoring service (see data/service/ at the repo root)",
        position = 0,
        section = serviceSection
    )
    default String serviceUrl() {
        return "http://127.0.0.1:8420";
    }

    @ConfigItem(
        keyName = "candidateLimit",
        name = "Candidates per scan",
        description = "How many top-ranked candidates to fetch per scan",
        position = 1,
        section = serviceSection
    )
    default int candidateLimit() {
        return 20;
    }

    @ConfigSection(
        name = "Sizing",
        description = "How large a position to take on a queued flip",
        position = 1,
        closedByDefault = false
    )
    String sizingSection = "sizing";

    @ConfigItem(
        keyName = "gpPerFlip",
        name = "Max GP per flip",
        description = "Cap on how much GP a single queued buy order can use, before also capping by GE limit",
        position = 0,
        section = sizingSection
    )
    default int gpPerFlip() {
        return 100_000;
    }

    @ConfigItem(
        keyName = "minPredictedMarginPct",
        name = "Min predicted margin %",
        description = "Only queue candidates with at least this much predicted margin (e.g. 5 = 5%)",
        position = 1,
        section = sizingSection
    )
    default double minPredictedMarginPct() {
        return 5.0;
    }

    @ConfigItem(
        keyName = "maxOpenFlips",
        name = "Max open flips",
        description = "Don't queue new buys while this many FlipperStar-originated orders are still QUEUED or SUBMITTED in GE Star V2's queue",
        position = 2,
        section = sizingSection
    )
    default int maxOpenFlips() {
        return 5;
    }

    @ConfigSection(
        name = "Automation",
        description = "Unattended scanning - off by default, use the panel's Scan button for manual control",
        position = 2,
        closedByDefault = true
    )
    String automationSection = "automation";

    @ConfigItem(
        keyName = "autoScanEnabled",
        name = "Auto-scan and queue",
        description = "If on, scans and queues candidates automatically on an interval instead of only when you click Scan",
        position = 0,
        section = automationSection
    )
    default boolean autoScanEnabled() {
        return false;
    }

    @ConfigItem(
        keyName = "autoScanIntervalMinutes",
        name = "Auto-scan interval (minutes)",
        description = "How often to scan and queue automatically, when auto-scan is enabled",
        position = 1,
        section = automationSection
    )
    default int autoScanIntervalMinutes() {
        return 15;
    }
}
