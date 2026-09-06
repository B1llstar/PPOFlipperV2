package net.runelite.client.plugins.microbot.ppoflipperstar;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Shared "is this item's live spread wide enough to still profit after GE tax, right now" check
 * used by both rapid-flipping sub-modes (see {@link PPOFlipperStarConfig}'s "Rapid flipping"
 * section for the full feature description):
 * <ul>
 *   <li><b>Rapid PPO</b> ({@code PPOFlipperStarScript#autonomouslySubmit}) - an extra gate on top
 *   of every existing guardrail, filtering out a model suggestion whose item doesn't currently
 *   clear this bar, independent of whatever price the model itself proposed.</li>
 *   <li><b>Rapid non-PPO</b> ({@link RapidFlipScanner}) - the sole basis for proposing a BUY at
 *   all, with no model involved.</li>
 * </ul>
 * Both read the exact same live {@link WikiPriceClient.Price} and apply the exact same margin
 * check, so "rapid" means the same thing regardless of which sub-mode found the opportunity.
 */
@Singleton
public class RapidFlipEngine {

    private final PPOFlipperStarConfig config;
    private final WikiPriceClient wikiPriceClient;

    @Inject
    public RapidFlipEngine(PPOFlipperStarConfig config, WikiPriceClient wikiPriceClient) {
        this.config = config;
        this.wikiPriceClient = wikiPriceClient;
    }

    /** One item's live buy/sell prices and whether they currently clear the configured rapid-flipping margin bar. */
    public static final class Evaluation {
        public final int itemId;
        public final int instaBuyPrice;
        public final int instaSellPrice;
        /** Net profit per unit after 2% GE tax (see GeTax), assuming a buy at instaBuyPrice and sell at instaSellPrice - may be negative. */
        public final int netMarginPerUnit;
        public final boolean qualifies;

        Evaluation(int itemId, int instaBuyPrice, int instaSellPrice, int netMarginPerUnit, boolean qualifies) {
            this.itemId = itemId;
            this.instaBuyPrice = instaBuyPrice;
            this.instaSellPrice = instaSellPrice;
            this.netMarginPerUnit = netMarginPerUnit;
            this.qualifies = qualifies;
        }
    }

    /**
     * Evaluates {@code itemId} against the currently cached live wiki price (never performs
     * network I/O itself - see {@link WikiPriceClient#getLatestPrice}'s own contract) and the
     * configured margin bar. Returns {@code null} if no live price is cached yet for this item
     * (nothing to evaluate against) - never guesses/falls back to a stale or model-proposed price,
     * since the whole point of this check is verifying the CURRENT market, not a past snapshot.
     */
    public Evaluation evaluate(int itemId) {
        WikiPriceClient.Price price = wikiPriceClient.getLatestPrice(itemId);
        if (price == null || price.instaBuyPrice <= 0 || price.instaSellPrice <= 0) {
            return null;
        }

        // A single-unit margin check: GeTax's cap/exemption floor operate on the ACTUAL trade
        // quantity, which this method has no opinion on (RapidFlipScanner sizes the real BUY
        // quantity separately, from budget/buy-limit) - per-unit tax at quantity=1 is the
        // conservative, quantity-independent proxy for "is this spread worth it at all," matching
        // how PPOFlipperStarScript.applyMinSellMargin already reasons about margin independent of
        // quantity (see that method's own comment: cost basis and proceeds both scale linearly
        // with quantity, so a per-unit check is quantity-independent by construction above the tax
        // exemption floor).
        long netProceedsPerUnit = GeTax.netProceeds(price.instaSellPrice, 1, price.instaSellPrice);
        int netMarginPerUnit = (int) (netProceedsPerUnit - price.instaBuyPrice);

        boolean qualifies;
        if (config.rapidMarginType() == PPOFlipperStarConfig.RapidMarginType.FLAT_GP_PER_UNIT) {
            qualifies = netMarginPerUnit >= config.rapidMinMarginGp();
        } else {
            double requiredMargin = price.instaBuyPrice * (config.rapidMinMarginPercent() / 100.0);
            qualifies = netMarginPerUnit >= requiredMargin;
        }

        return new Evaluation(itemId, price.instaBuyPrice, price.instaSellPrice, netMarginPerUnit, qualifies);
    }
}
