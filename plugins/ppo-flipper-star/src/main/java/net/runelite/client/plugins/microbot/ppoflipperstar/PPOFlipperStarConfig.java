package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("ppoflipperstar")
@ConfigInformation(
    "Buys and sells items on the Grand Exchange from an order queue you manage in the sidebar" +
    " panel or via right-click (add items by name/quantity/price, watch them fill live), with" +
    " spend/price guardrails.<br /><br />" +
    "Milestone 1: manual-first mechanics only - everything here can be driven entirely by hand." +
    " The PPO section below is a placeholder for a later milestone; no autonomous decision-" +
    "making or HTTP calls happen yet.<br /><br />" +
    "Start at (or near) the Grand Exchange with the items or coins already in your inventory or bank.<br /><br />" +
    "made by billstar"
)
public interface PPOFlipperStarConfig extends Config {

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
            "offering. An order that can't be covered by inventory alone is skipped rather than triggering a bank " +
            "trip when this is off.",
        position = 0,
        section = ordersSection
    )
    default boolean withdrawFromBank() {
        return false;
    }

    @ConfigItem(
        keyName = "inventoryOnlyMode",
        name = "Inventory-only mode (holdings)",
        description = "When on, PortfolioManager reports held quantities from inventory only, matching ge-star-v2's " +
            "conservative default - Rs2Bank's cache is only populated once the bank has actually been opened this " +
            "session, so counting on stale/never-refreshed bank data risks acting on wrong info. When off (the " +
            "default here), holdings are inventory + bank, kept trustworthy by BankManager proactively refreshing " +
            "the bank on the interval below. See PortfolioManager's javadoc for the full reasoning.",
        position = 1,
        section = ordersSection
    )
    default boolean inventoryOnlyMode() {
        return false;
    }

    @ConfigItem(
        keyName = "bankRefreshIntervalSeconds",
        name = "Bank refresh interval (seconds)",
        description = "How often PortfolioManager proactively opens/refreshes the bank to keep inventory+bank " +
            "holdings trustworthy. 0 disables proactive refresh (bank data then only updates when something else " +
            "happens to open the bank). No effect while inventory-only mode is on.",
        position = 2,
        section = ordersSection
    )
    default int bankRefreshIntervalSeconds() {
        return 0;
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
        description = "Master switch for the soft guardrail checks below. Off still enforces the hard checks " +
            "(sell-exceeds-held, buy-exceeds-GE-limit, duplicate-buy) since those can never usefully succeed " +
            "anyway; it skips maxGpToSpend/maxQuantityPerItem/maxPriceDeviationPercent.",
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
            "queued price). 0 = disabled.",
        position = 3,
        section = guardrailsSection
    )
    default int maxPriceDeviationPercent() {
        return 25;
    }

    @ConfigItem(
        keyName = "stopOnGuardrailBreach",
        name = "Stop script on guardrail breach",
        description = "If off, a rejected order is just skipped and the script moves to the next one. If on, the whole script stops. No effect on the hard checks that always apply regardless of the guardrails master switch.",
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
        keyName = "decisionTickIntervalSeconds",
        name = "Decision tick interval (seconds)",
        description = "How often the script's main loop re-evaluates state (submits next queued order, polls " +
            "offers, etc). Purely a mechanical execution-loop cadence in this milestone - there is no autonomous " +
            "decision-making yet, so this does not control any PPO inference call.",
        position = 2,
        section = behaviorSection
    )
    default int decisionTickIntervalSeconds() {
        return 1;
    }

    @ConfigSection(
        name = "PPO (future milestone)",
        description = "Placeholder settings for the autonomous PPO policy - not wired to any inference calls yet",
        position = 3,
        closedByDefault = true
    )
    String ppoSection = "ppo";

    @ConfigItem(
        keyName = "inferenceServerUrl",
        name = "Inference server URL",
        description = "Base URL of the local PPO inference server (see PROPOSAL.md §3.6). Not called by this " +
            "version of the plugin - stored now so the config shape is stable when that milestone lands.",
        position = 0,
        section = ppoSection
    )
    default String inferenceServerUrl() {
        return "http://127.0.0.1:8600";
    }

    @ConfigItem(
        keyName = "shadowMode",
        name = "Shadow mode",
        description = "When the PPO policy is wired up in a later milestone, shadow mode means the model only " +
            "proposes actions for manual confirmation rather than executing unattended. Defaults to on (the safe " +
            "default) even though nothing reads this yet.",
        position = 1,
        section = ppoSection
    )
    default boolean shadowMode() {
        return true;
    }
}
