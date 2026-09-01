package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One buy/sell order, created either from the panel's add-order form or from the right-click
 * "Buy more"/"Sell" menu entries, and tracked through its whole lifecycle in {@link OrderQueue}
 * - the same instance is mutated in place as it moves from queued to submitted to done, so the
 * panel can render live progress.
 *
 * <p>This milestone (manual-order-execution only, see PROPOSAL.md build order §5.1) never
 * originates an order from anywhere but a human action - there is no DECIDE phase yet - but the
 * shape already matches what an autonomous phase will need to produce later, so nothing here is
 * a throwaway "manual-only" design that would need reworking for milestone 4.
 */
@Getter
public class PPOFlipperOrder {

    public enum Status {
        QUEUED,
        SUBMITTED,
        DONE,
        SKIPPED,
        FAILED
    }

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final long id = NEXT_ID.getAndIncrement();
    private final GrandExchangeAction action;
    private final int itemId;
    private final String itemName;
    private final int quantity;
    private final int price;

    @Setter
    private volatile Status status = Status.QUEUED;

    /** How many units have filled so far. Only meaningful once SUBMITTED. */
    @Setter
    private volatile int quantityFilled = 0;

    /**
     * The price actually offered to the GE, once submitted - 0 until then. Distinct from
     * {@link #price} (what was requested) because {@code PPOFlipperStarScript.clampToLivePrice}
     * can lower a BUY (or raise a SELL) to the live Wiki insta-buy/insta-sell price at submit
     * time, regardless of whether the order came from a human's typed price or, later, the PPO
     * policy - see that method's javadoc. The panel shows both so a clamp is visible rather than
     * silently producing a fill price that doesn't match what was asked for.
     */
    @Setter
    private volatile int submittedPrice = 0;

    /** Set when status is SKIPPED or FAILED, for display in the panel. Also used to note a live-price clamp on an otherwise-normal SUBMITTED order - see {@link #submittedPrice}. */
    @Setter
    private volatile String statusDetail;

    @Setter
    private volatile GrandExchangeSlots slot;

    public PPOFlipperOrder(GrandExchangeAction action, int itemId, String itemName, int quantity, int price) {
        this.action = action;
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.price = price;
    }

    public long totalValue() {
        return (long) quantity * price;
    }

    public int getProgressPercentage() {
        if (quantity <= 0) return 0;
        return Math.min(100, (int) (quantityFilled * 100L / quantity));
    }

    @Override
    public String toString() {
        if (submittedPrice > 0 && submittedPrice != price) {
            return String.format("%s %dx %s @ %d gp (requested %d gp)", action, quantity, itemName, submittedPrice, price);
        }
        return String.format("%s %dx %s @ %d gp", action, quantity, itemName, price);
    }
}
