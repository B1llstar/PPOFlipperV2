package net.runelite.client.plugins.microbot.gestarv2;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One buy/sell order, created from the panel's add-order form and tracked through its whole
 * lifecycle in {@link GeStarOrderQueue} - the same instance is mutated in place as it moves
 * from queued to submitted to done, so the panel can render live progress.
 */
@Getter
public class GeStarOrder {

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
    private final String itemName;
    private final int quantity;
    private final int price;

    @Setter
    private volatile Status status = Status.QUEUED;

    /** How many units have filled so far. Only meaningful once SUBMITTED. */
    @Setter
    private volatile int quantityFilled = 0;

    /** Set when status is SKIPPED or FAILED, for display in the panel. */
    @Setter
    private volatile String statusDetail;

    @Setter
    private volatile GrandExchangeSlots slot;

    public GeStarOrder(GrandExchangeAction action, String itemName, int quantity, int price) {
        this.action = action;
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
        return String.format("%s %dx %s @ %d gp", action, quantity, itemName, price);
    }
}
