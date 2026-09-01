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
    "Manual ordering always works by hand, exactly as before. A PPO policy (trained offline," +
    " served by a separate Python Firestore-listening worker - see PROPOSAL.md §3.6) is now" +
    " consulted every decision tick for every watchlisted item, in shadow mode: its proposed" +
    " actions appear in the panel's \"Model suggestions\" section and require an explicit" +
    " Confirm click before ever reaching the order queue - the model can never submit an order" +
    " on its own in this build.<br /><br />" +
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
        name = "PPO",
        description = "The autonomous PPO policy, consulted every decision tick over Firestore (PROPOSAL.md §3.6) " +
            "in shadow mode - the model proposes actions in the panel's \"Model suggestions\" section, but nothing " +
            "is submitted to the order queue without an explicit manual Confirm click. Wiring shadow mode off to " +
            "real unattended execution is an explicit future milestone, not something any setting here enables.",
        position = 3,
        closedByDefault = true
    )
    String ppoSection = "ppo";

    @ConfigItem(
        keyName = "decisionResponseTimeoutSeconds",
        name = "Decision response timeout (seconds)",
        description = "How long the DECIDE phase waits for a matching decision/response document (tickId echoed " +
            "back) after writing decision/request, before giving up on that tick and defaulting every item to " +
            "HOLD (PROPOSAL.md §3.6: \"a slow/unreachable model must never block the trading loop\"). Manual " +
            "order submission via OrderQueue is unaffected by this timeout either way.",
        position = 0,
        section = ppoSection
    )
    default int decisionResponseTimeoutSeconds() {
        return 5;
    }

    @ConfigItem(
        keyName = "modelConfidenceThreshold",
        name = "Model confidence threshold",
        description = "A proposed action whose confidence (from the response's per-action \"confidence\" field) " +
            "is below this is forced to HOLD before it's even shown as a suggestion, regardless of what the model " +
            "proposed. 0 disables this filter.",
        position = 1,
        section = ppoSection
    )
    default double modelConfidenceThreshold() {
        return 0.0;
    }

    @ConfigItem(
        keyName = "shadowMode",
        name = "Shadow mode",
        description = "The model only proposes actions for manual confirmation in the panel's \"Model " +
            "suggestions\" section - nothing is ever submitted to the order queue without an explicit Confirm " +
            "click, regardless of this setting's value. Wiring this to real unattended execution is an explicit " +
            "future milestone; this toggle exists now so the config shape is stable when that milestone lands, " +
            "but this build enforces manual confirmation unconditionally - see PPOFlipperStarScript's DECIDE " +
            "phase javadoc.",
        position = 2,
        section = ppoSection
    )
    default boolean shadowMode() {
        return true;
    }

    @ConfigSection(
        name = "Cloud sync",
        description = "Firestore-backed persistence: portfolio, buy-limit ledger, watchlist, and trade history " +
            "synced under this RuneScape account's own accountHash, so state follows the account across " +
            "sessions/machines. Local ConfigManager storage always stays as a fast/offline cache - turning this " +
            "off just means it's never reconciled against a cloud copy.",
        position = 4,
        closedByDefault = true
    )
    String cloudSyncSection = "cloudSync";

    @ConfigItem(
        keyName = "firestoreSyncEnabled",
        name = "Enable Firestore sync",
        description = "Pull portfolio/buy-limit/watchlist state from Firestore on startup (Firestore wins as " +
            "source of truth when reachable) and push every local mutation back to it in the background, best-" +
            "effort. A failed/unreachable Firestore call never blocks local operation - it only logs a warning " +
            "and this session runs local-only. Off runs purely on local ConfigManager storage, exactly as before " +
            "this feature existed.",
        position = 0,
        section = cloudSyncSection
    )
    default boolean firestoreSyncEnabled() {
        return true;
    }

    @ConfigItem(
        keyName = "firestoreServiceAccountPath",
        name = "Service account JSON path",
        description = "Absolute path to the Firebase service account key file. Never share this file or commit it to git.",
        position = 1,
        section = cloudSyncSection
    )
    default String firestoreServiceAccountPath() {
        return "/Users/b1llstar/Documents/GitHub/BotStar/ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json";
    }
}
