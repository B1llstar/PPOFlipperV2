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
        description = "If a sell item or GP shortfall isn't in your inventory, withdraw it from the bank before offering",
        position = 0,
        section = ordersSection
    )
    default boolean withdrawFromBank() {
        return true;
    }

    @ConfigSection(
        name = "Guardrails",
        description = "Safety limits enforced before any offer is submitted",
        position = 1,
        closedByDefault = false
    )
    String guardrailsSection = "guardrails";

    @ConfigItem(
        keyName = "maxGpToSpend",
        name = "Max GP to spend (session)",
        description = "Hard cap on total coins spent on buy orders this session. 0 = no cap.",
        position = 0,
        section = guardrailsSection
    )
    default int maxGpToSpend() {
        return 0;
    }

    @ConfigItem(
        keyName = "maxQuantityPerItem",
        name = "Max quantity per item",
        description = "Refuse to place any single order above this quantity, regardless of what the order list says. 0 = no cap.",
        position = 1,
        section = guardrailsSection
    )
    default int maxQuantityPerItem() {
        return 0;
    }

    @ConfigItem(
        keyName = "maxPriceDeviationPercent",
        name = "Max price deviation from guide price (%)",
        description = "Refuse an order if its price is more than this % away from the GE guide price. 0 = disabled.",
        position = 2,
        section = guardrailsSection
    )
    default int maxPriceDeviationPercent() {
        return 25;
    }

    @ConfigItem(
        keyName = "maxActiveOffers",
        name = "Max concurrent offers",
        description = "Cap on how many of the 8 GE slots this script will use at once",
        position = 3,
        section = guardrailsSection
    )
    default int maxActiveOffers() {
        return 4;
    }

    @ConfigItem(
        keyName = "stopOnGuardrailBreach",
        name = "Stop plugin on guardrail breach",
        description = "If off, a rejected order is just skipped and the script moves to the next one. If on, the whole plugin stops.",
        position = 4,
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
        keyName = "collectToBank",
        name = "Collect completed offers to bank",
        description = "Off collects to inventory instead",
        position = 0,
        section = behaviorSection
    )
    default boolean collectToBank() {
        return true;
    }

    @ConfigItem(
        keyName = "stopWhenOrdersComplete",
        name = "Stop script when queue is empty",
        description = "Shuts the script down once every queued order has been filled and collected",
        position = 1,
        section = behaviorSection
    )
    default boolean stopWhenOrdersComplete() {
        return true;
    }
}
