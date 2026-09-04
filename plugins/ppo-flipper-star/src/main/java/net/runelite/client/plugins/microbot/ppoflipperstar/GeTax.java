package net.runelite.client.plugins.microbot.ppoflipperstar;

/**
 * OSRS Grand Exchange tax: 2% of a sale's gross proceeds, floored to a whole gp amount, capped at
 * 5,000,000gp per sale, waived entirely when the item's per-unit price is below 50gp (confirmed
 * current as of the 2025-05-29 rate increase from 1% to 2% - see
 * https://oldschool.runescape.wiki/w/Grand_Exchange). Applies to SELL only, never BUY - a BUY
 * spends exactly its own price with no tax involved.
 *
 * <p>Deliberately mirrors {@code data/ppo/env.py}'s {@code compute_ge_tax} exactly (same rate,
 * cap, and per-unit exemption floor) - the model was trained against reward signals computed
 * with this formula, so realized profit reported here should match what the model itself was
 * optimizing for, not silently diverge from it.
 *
 * <p><b>Does not model the ~45-item tax-exempt list</b> (bonds, basic tools, certain low-tier
 * food/ammo, teleport items - see the wiki's "Category:Items exempt from Grand Exchange tax").
 * That list has no field in the wiki's price-mapping API (confirmed: {@code /mapping} entries
 * carry no tax-related key), so modeling it precisely would mean hand-maintaining a second list
 * that can silently drift out of sync with the game. Deliberately out of scope for now - those
 * items are overwhelmingly cheap, so treating them as taxed when they're actually exempt
 * understates realized profit by at most a few gp per sale, not enough to change a real trading
 * decision. The blanket 2%/cap/50gp-floor rule this class implements is the part that actually
 * matters for margin correctness on any item worth flipping.
 */
public final class GeTax {

    /** Exposed (not private) for callers that need to solve for a price given a target net margin - see PPOFlipperStarScript.applyMinSellMargin. */
    public static final double RATE = 0.02;
    private static final long CAP = 5_000_000L;
    private static final int EXEMPT_BELOW_UNIT_PRICE = 50;

    private GeTax() {
    }

    /**
     * Tax owed on a sale of {@code quantity} units at {@code unitPrice} gp each - 0 if
     * {@code unitPrice} is below the exemption floor. Never negative, never more than
     * {@link #CAP}.
     */
    public static long compute(int unitPrice, int quantity) {
        if (unitPrice < EXEMPT_BELOW_UNIT_PRICE || quantity <= 0) {
            return 0L;
        }
        long gross = (long) unitPrice * quantity;
        long tax = (long) Math.floor(gross * RATE);
        return Math.min(tax, CAP);
    }

    /** {@code grossProceeds - compute(unitPrice, quantity)} - what actually lands in inventory/bank from a SELL fill. */
    public static long netProceeds(int unitPrice, int quantity, long grossProceeds) {
        return grossProceeds - compute(unitPrice, quantity);
    }
}
