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
    " consulted every decision tick for every watchlisted item. By default its proposed" +
    " actions only appear in the panel's \"Model suggestions\" section and require an explicit" +
    " Confirm click before ever reaching the order queue. The PPO section's \"Autonomous mode" +
    " (LIVE TRADING)\" setting, OFF by default, is the one switch that changes this - when on," +
    " suggestions above the confidence threshold submit automatically with no confirmation." +
    " Every order, autonomous or manual, still passes through the same Guardrails checks.<br /><br />" +
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
        keyName = "goldReserveTarget",
        name = "Gold reserve target (session)",
        description = "When a BUY order needs more coins than are currently in inventory and a bank trip happens " +
            "(withdrawFromBank above), top inventory coins up to this amount instead of withdrawing just enough " +
            "for that one order - later orders this session can then draw from the reserve already in inventory " +
            "without triggering another bank visit each time. Defaults to 1,000,000gp; 0 withdraws exactly what " +
            "the current order needs every time instead, with no standing reserve. Has no effect if inventory " +
            "coins already meet or exceed this target (nothing is deposited back - this only ever tops up, never " +
            "trims down).",
        position = 1,
        section = ordersSection
    )
    default int goldReserveTarget() {
        return 1_000_000;
    }

    @ConfigItem(
        keyName = "inventoryOnlyMode",
        name = "Inventory-only mode (holdings)",
        description = "When on, PortfolioManager reports held quantities from inventory only, matching ge-star-v2's " +
            "conservative default - Rs2Bank's cache is only populated once the bank has actually been opened this " +
            "session, so counting on stale/never-refreshed bank data risks acting on wrong info. When off (the " +
            "default here), holdings are inventory + bank, kept trustworthy by BankManager proactively refreshing " +
            "the bank on the interval below. See PortfolioManager's javadoc for the full reasoning.",
        position = 2,
        section = ordersSection
    )
    default boolean inventoryOnlyMode() {
        return false;
    }

    @ConfigItem(
        keyName = "bankRefreshIntervalSeconds",
        name = "Bank refresh interval (seconds)",
        description = "How often the script proactively opens then immediately closes the bank (a pure read, never " +
            "a withdrawal) to keep inventory+bank holdings trustworthy - only takes effect once the GE is open, " +
            "matching this plugin's stay-at-the-GE operating assumption. 0 disables proactive refresh entirely " +
            "(bank data then only updates when something else happens to open the bank, e.g. a withdrawal - so " +
            "anything held only in the bank will read as 0 until then). No effect while inventory-only mode is on. " +
            "A short interval (e.g. 30-60s) is recommended if you keep meaningful stock in the bank and want the " +
            "portfolio panel/model to actually see it.",
        position = 3,
        section = ordersSection
    )
    default int bankRefreshIntervalSeconds() {
        return 0;
    }

    @ConfigItem(
        keyName = "minSellProfitMarginPercent",
        name = "Minimum SELL profit margin (%)",
        description = "The trained policy's sell price is set purely from the live market spread (see env.py's " +
            "SELL_PRICE_OFFSET_FRAC - a SELL_100 can concede up to 30% of the spread for a faster fill), with no " +
            "awareness of what you actually paid for the position - it can undersell a real profit margin to " +
            "guarantee a quicker fill. When this is above 0, a SELL is never actually offered below your tracked " +
            "average cost for that item plus this percentage - the price is raised to meet that floor if needed " +
            "(the order still submits, just less aggressively priced, which may fill slower). Only applies when a " +
            "real tracked average cost exists for the item (0 for untracked/pre-existing stock, e.g. holdings from " +
            "before this ledger started tracking it) - in that case only the live insta-sell floor above applies, " +
            "same as before this setting existed. 0 disables this floor entirely.",
        position = 4,
        section = ordersSection
    )
    default double minSellProfitMarginPercent() {
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
        description = "Hard cap on total coins spent on buy orders this session. Defaults to a conservative 5,000,000 " +
            "gp now that autonomous mode (see the PPO section below) can submit orders with no human confirmation - " +
            "this is the main brake on how much real GP an unattended run can lose before it stops itself. 0 = no cap, " +
            "if you deliberately want uncapped spending.",
        position = 1,
        section = guardrailsSection
    )
    default int maxGpToSpend() {
        return 5_000_000;
    }

    @ConfigItem(
        keyName = "maxGpPerOrder",
        name = "Max GP per single order",
        description = "Hard cap on how much a SINGLE buy order can spend, checked before the session cap above and " +
            "independent of it - the session cap alone can't stop one large order from consuming most or all of the " +
            "remaining session budget in one shot. This matters because the trained policy's BUY_SMALL/MEDIUM/LARGE " +
            "action tiers size themselves as a fraction of an item's GE buy limit, not its price - a \"small\" tier " +
            "order on an expensive item (e.g. Grimy ranarr weed) can still come out to several million gp. 0 = no " +
            "cap, if you deliberately want a single order able to spend up to the full session budget.",
        position = 5,
        section = guardrailsSection
    )
    default int maxGpPerOrder() {
        return 500_000;
    }

    @ConfigItem(
        keyName = "maxQuantityPerItem",
        name = "Max quantity per item",
        description = "Refuse to place any single order above this quantity, regardless of what the order list says. " +
            "Defaults to a conservative 50,000 units now that autonomous mode (see the PPO section below) can submit " +
            "orders with no human confirmation. 0 = no cap, if you deliberately want uncapped quantity.",
        position = 2,
        section = guardrailsSection
    )
    default int maxQuantityPerItem() {
        return 50_000;
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

    @ConfigItem(
        keyName = "staleOfferTimeoutMinutes",
        name = "Stale offer timeout (minutes)",
        description = "An offer that's been live on the GE this long without fully filling becomes eligible to be " +
            "aborted and collected back to inventory/bank, freeing that GE slot - but it's ONLY actually pulled " +
            "once something else is genuinely queued and waiting for a slot. An idle slot holding a slow-moving " +
            "offer costs nothing while nothing else wants it, so a stale offer with no queued replacement is left " +
            "alone indefinitely rather than force-cycled just because a timer elapsed. When it IS pulled, rather " +
            "than a hardcoded reprice rule, the item is simply left to the next DECIDE tick, which re-evaluates it " +
            "using the same model judgment (spread/volatility/momentum/holding-duration) as any fresh HOLD/BUY/SELL " +
            "decision. If the model still thinks the trade is worthwhile it'll naturally requeue at a then-current " +
            "price; if conditions changed it can just as easily propose HOLD instead. A PARTIALLY filled offer is " +
            "left alone regardless of age or queue pressure - only a fully-unfilled offer is ever aborted this way, " +
            "since pulling a partial fill would strand the already-filled portion's exit strategy. 0 disables this " +
            "entirely (offers wait indefinitely, matching behavior before this setting existed). Applies to both " +
            "BUY and SELL offers, manual or autonomous - this is about GE slot hygiene, not a trading strategy.",
        position = 3,
        section = behaviorSection
    )
    default int staleOfferTimeoutMinutes() {
        return 10;
    }

    @ConfigSection(
        name = "PPO",
        description = "The PPO policy, consulted every decision tick over Firestore (PROPOSAL.md §3.6). By " +
            "default the model only proposes actions in the panel's \"Model suggestions\" section, requiring an " +
            "explicit manual Confirm click before anything reaches the order queue. \"Autonomous mode (LIVE " +
            "TRADING)\" below is the one setting that changes this - read its description carefully before " +
            "enabling it with real GP at stake.",
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
            "proposed. This same filter also gates autonomous execution below - a suggestion never auto-submits " +
            "unless it also clears this threshold, identically to how it's decided whether to show it as a manual " +
            "suggestion. Defaults to 0.5 (raised from 0.0) so a low-confidence proposal is neither displayed nor " +
            "auto-executed by default. 0 disables this filter entirely.",
        position = 1,
        section = ppoSection
    )
    default double modelConfidenceThreshold() {
        return 0.5;
    }

    @ConfigItem(
        keyName = "buySuggestionCooldownSeconds",
        name = "BUY suggestion cooldown (seconds)",
        description = "The trained policy's buy-size formula scales with an item's GE buy limit (see env.py's " +
            "_apply_buy), which biases it toward repeatedly proposing cheap, high-buy-limit staples (Flax, Steel " +
            "knives, arrowheads, etc.) over the rest of the watchlist - a real bias in the model, not something " +
            "fixable on this side without a retrain. This setting dampens the symptom: once a BUY suggestion for " +
            "an item has been shown or auto-submitted, no new BUY suggestion for that same item is surfaced again " +
            "until this many seconds have passed, giving other watchlisted items room to appear instead. Applies " +
            "identically to the panel's \"Model suggestions\" display and to autonomous submission - one filter, " +
            "same as the confidence threshold above. Never applies to SELL suggestions (you should always see a " +
            "SELL proposal the moment the model makes one, since it concerns stock you already hold). 0 disables " +
            "this filter entirely, restoring the model's raw, unthrottled suggestion behavior.",
        position = 2,
        section = ppoSection
    )
    default int buySuggestionCooldownSeconds() {
        return 120;
    }

    @ConfigItem(
        keyName = "autonomousRejectionCooldownSeconds",
        name = "Autonomous rejection cooldown (seconds)",
        description = "Once autonomous submission finds that an item+action would be rejected by Guardrails (e.g. " +
            "a SELL for something not actually held, or a price too far from guide price), that exact item+action " +
            "is withheld from autonomous submission for this many seconds before being eligible again - stops the " +
            "model re-proposing, and this plugin re-queuing-then-rejecting, the same doomed order every single " +
            "DECIDE tick. This is purely a churn-prevention measure, not a trading-strategy setting: keep it short " +
            "enough that a rejection whose cause actually clears (the item is acquired, price moves back in range) " +
            "is picked up again within a couple of ticks rather than sitting silent for a long stretch - a value " +
            "much longer than decisionTickIntervalSeconds means a rejected item can go quiet for many consecutive " +
            "ticks, which looks like \"nothing is happening\" even while the model is producing plenty of other " +
            "suggestions. 0 disables this entirely, restoring the original reject-every-tick behavior.",
        position = 6,
        section = ppoSection
    )
    default int autonomousRejectionCooldownSeconds() {
        return 15;
    }

    @ConfigItem(
        keyName = "shadowMode",
        name = "Shadow mode",
        description = "Documentation of the staged-rollout design (see PROPOSAL.md §3.7) - this setting itself " +
            "does not change plugin behavior and is not read anywhere. The real on/off switch for autonomous " +
            "execution is \"Autonomous mode (LIVE TRADING)\" below; whether or not a suggestion requires a manual " +
            "Confirm click is controlled entirely by that setting, independent of this one.",
        position = 3,
        section = ppoSection
    )
    default boolean shadowMode() {
        return true;
    }

    @ConfigItem(
        keyName = "autonomousModeEnabled",
        name = "Autonomous mode (LIVE TRADING)",
        description = "DANGER - when ON, every model suggestion that clears the confidence threshold above is " +
            "submitted straight to the order queue with NO manual confirmation - real GP is at risk unattended, " +
            "exactly as if you had clicked Confirm yourself on every proposal the model makes. When OFF (the " +
            "default), behavior is unchanged from today: every suggestion only ever becomes an order via an " +
            "explicit Confirm click in the panel's \"Model suggestions\" section. This is independent of the " +
            "Shadow mode setting above (that one is inert documentation of the staged-rollout design in " +
            "PROPOSAL.md §3.7 - this flag is the actual switch). Every autonomously-submitted order still passes " +
            "through the exact same Guardrails checks (max GP/session, max quantity/item, price deviation, buy " +
            "limits, held-quantity checks) as a manual order - guardrails are never bypassed for autonomous orders. " +
            "Turning this off immediately stops any NEW autonomous order from being submitted, but does not cancel " +
            "offers already live on the GE - use \"Cancel all offers\" for that. Read PROPOSAL.md §3.7's staged " +
            "rollout recommendation before ever turning this on with real GP.",
        position = 4,
        section = ppoSection
    )
    default boolean autonomousModeEnabled() {
        return false;
    }

    @ConfigItem(
        keyName = "sellOffModeEnabled",
        name = "Sell-off mode (test SELL path)",
        description = "Automatically submits every SELL the model recommends for items already held in your " +
            "portfolio, using the exact same model suggestions as autonomous mode - but BUY suggestions are " +
            "dropped before they're ever shown or submitted, and every BUY order (autonomous or manual) is " +
            "rejected outright by Guardrails while this is on, regardless of the guardrails master switch. Meant " +
            "for verifying the SELL execution path actually works end-to-end (ideally at a profit) without risking " +
            "a fresh BUY going out at the same time. Independent of \"Autonomous mode (LIVE TRADING)\" above - " +
            "sell-off mode auto-submits SELLs even if that setting is off, since the whole point is exercising the " +
            "SELL path without a manual Confirm click. Still passes through every other Guardrails check (held-" +
            "quantity, price deviation) exactly like any other order. Turning this off does not cancel offers " +
            "already live on the GE - use \"Cancel all offers\" for that.",
        position = 5,
        section = ppoSection
    )
    default boolean sellOffModeEnabled() {
        return false;
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
