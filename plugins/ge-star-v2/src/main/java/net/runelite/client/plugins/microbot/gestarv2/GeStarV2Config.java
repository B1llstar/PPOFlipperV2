package net.runelite.client.plugins.microbot.gestarv2;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("gestarv2")
@ConfigInformation(
    "Buys and sells items on the Grand Exchange from an order queue you manage in the sidebar" +
    " panel (add items by name/quantity/price, watch them fill live), with spend/price" +
    " guardrails.<br /><br />" +
    "Start at (or near) the Grand Exchange with the items or coins already in your inventory or bank.<br /><br />" +
    "made by billstar"
)
public interface GeStarV2Config extends Config {

    @ConfigSection(
        name = "Orders",
        description = "General order behavior",
        position = 0,
        closedByDefault = false
    )
    String ordersSection = "orders";

    @ConfigItem(
        keyName = "withdrawFromBank",
        name = "Withdraw from bank if needed",
        description = "If a sell item or GP shortfall isn't in your inventory, withdraw it from the bank before " +
            "offering. Off by default - keeps the script operating purely on what's already in inventory, since " +
            "GeStarPortfolio's held-quantity reporting is inventory-only too (bank contents are only visible to the " +
            "client when the bank has actually been opened, so counting on bank data risks acting on stale/incomplete " +
            "info). An order that can't be covered by inventory alone is skipped rather than triggering a bank trip.",
        position = 0,
        section = ordersSection
    )
    default boolean withdrawFromBank() {
        return false;
    }

    @ConfigSection(
        name = "Guardrails",
        description = "Safety limits enforced before any offer is submitted",
        position = 1,
        closedByDefault = false
    )
    String guardrailsSection = "guardrails";

    @ConfigItem(
        keyName = "guardrailsEnabled",
        name = "Guardrails enabled",
        description = "Master switch for all guardrail checks below. Off submits every order as-is with no safety checks.",
        position = 0,
        section = guardrailsSection
    )
    default boolean guardrailsEnabled() {
        return true;
    }

    @ConfigItem(
        keyName = "maxGpToSpend",
        name = "Max GP to spend (session)",
        description = "Hard cap on total coins spent on buy orders this session. 0 = no cap.",
        position = 1,
        section = guardrailsSection
    )
    default int maxGpToSpend() {
        return 0;
    }

    @ConfigItem(
        keyName = "maxQuantityPerItem",
        name = "Max quantity per item",
        description = "Refuse to place any single order above this quantity, regardless of what the order list says. 0 = no cap.",
        position = 2,
        section = guardrailsSection
    )
    default int maxQuantityPerItem() {
        return 0;
    }

    @ConfigItem(
        keyName = "maxPriceDeviationPercent",
        name = "Max price deviation from live price (%)",
        description = "Refuse an order if its price is more than this % away from the OSRS Wiki's current live " +
            "insta-buy/insta-sell price. Catches an order priced worse than the live market itself (e.g. a stale " +
            "queued price). 0 = disabled. Does NOT catch the live market itself having drifted far from an item's " +
            "normal value on a thin/cheap item - see \"Max deviation from base item value\" below for that.",
        position = 3,
        section = guardrailsSection
    )
    default int maxPriceDeviationPercent() {
        return 25;
    }

    @ConfigItem(
        keyName = "maxBaseValueDeviationPercent",
        name = "Max deviation from base item value (%)",
        description = "Refuse a BUY if its price is more than this % above the item's static base value (the " +
            "client's own item definition price, e.g. an onion seed's base value of 3gp - independent of live " +
            "trading, so this catches cases the live-price guardrail above can't: a thin/cheap item's live price " +
            "itself drifting absurdly far from what the item is normally worth, not just a stale queued price. " +
            "Default 150% (i.e. rejects paying more than 2.5x base value) is intentionally generous - many items " +
            "legitimately trade well above their base value even under normal conditions, this is a last-resort " +
            "sanity check for extreme cases, not a primary pricing signal. Only applies to BUY orders - there's no " +
            "equivalent risk selling for more than base value. 0 = disabled.",
        position = 4,
        section = guardrailsSection
    )
    default int maxBaseValueDeviationPercent() {
        return 150;
    }

    @ConfigItem(
        keyName = "stopOnGuardrailBreach",
        name = "Stop script on guardrail breach",
        description = "If off, a rejected order is just skipped and the script moves to the next one. If on, the whole script stops. No effect while guardrails are disabled.",
        position = 5,
        section = guardrailsSection
    )
    default boolean stopOnGuardrailBreach() {
        return false;
    }

    @ConfigSection(
        name = "Behavior",
        description = "General script behavior",
        position = 2,
        closedByDefault = true
    )
    String behaviorSection = "behavior";

    @ConfigItem(
        keyName = "maxActiveOffers",
        name = "Max concurrent offers",
        description = "Cap on how many of the 8 GE slots this script will use at once",
        position = 0,
        section = behaviorSection
    )
    default int maxActiveOffers() {
        return 8;
    }

    @ConfigItem(
        keyName = "collectToBank",
        name = "Collect completed offers to bank",
        description = "Off collects to inventory instead",
        position = 1,
        section = behaviorSection
    )
    default boolean collectToBank() {
        return true;
    }

    @ConfigItem(
        keyName = "stopWhenOrdersComplete",
        name = "Stop script when queue is empty",
        description = "Shuts the script down once every queued order has been filled and collected. Turn this OFF if " +
            "you want FlipperStar's auto-scan to run unattended - with this on, the script fully stops once the queue " +
            "drains and won't notice new orders FlipperStar queues later, requiring Execute to be clicked again.",
        position = 2,
        section = behaviorSection
    )
    default boolean stopWhenOrdersComplete() {
        return true;
    }

    @ConfigSection(
        name = "Web sync",
        description = "Pull orders from the PPOFlipperOpus web UI via Firestore",
        position = 3,
        closedByDefault = true
    )
    String webSyncSection = "webSync";

    @ConfigItem(
        keyName = "firestoreSyncEnabled",
        name = "Enable web sync",
        description = "Poll Firestore for orders submitted from the web UI and add them to this queue, pushing status/fills back",
        position = 0,
        section = webSyncSection
    )
    default boolean firestoreSyncEnabled() {
        return false;
    }

    @ConfigItem(
        keyName = "firestoreServiceAccountPath",
        name = "Service account JSON path",
        description = "Absolute path to the Firebase service account key file. Never share this file or commit it to git.",
        position = 1,
        section = webSyncSection
    )
    default String firestoreServiceAccountPath() {
        return "";
    }
}
