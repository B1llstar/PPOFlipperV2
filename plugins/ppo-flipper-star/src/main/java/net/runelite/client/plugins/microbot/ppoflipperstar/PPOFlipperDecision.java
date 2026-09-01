package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.Getter;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One model-proposed action from a {@code decision/response} document, held in memory for the
 * panel's "Model suggestions" section until a human either confirms or dismisses it.
 *
 * <p><b>This class never causes an order to be submitted by itself.</b> Confirming a suggestion
 * (see {@code PPOFlipperStarPanel}'s confirm-button handler) converts it into a brand-new
 * {@link PPOFlipperOrder} pushed onto {@link OrderQueue} exactly the way a manual right-click/
 * panel add-order does - it passes through {@link Guardrails#check} identically, with no
 * special-cased bypass. See {@code PPOFlipperStarScript}'s DECIDE-phase javadoc for why shadow
 * mode is unconditional in this milestone.
 */
@Getter
public class PPOFlipperDecision {

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final long id = NEXT_ID.getAndIncrement();
    private final long tickId;
    private final int itemId;
    private final String itemName;
    /** HOLD, BUY_SMALL, BUY_MEDIUM, BUY_LARGE, SELL_25%, SELL_50%, or SELL_100% - the raw action name from the response doc's {@code action} field, mirroring data/ppo/env.py's ACTION_NAMES. */
    private final String actionName;
    /** The GE action this suggestion would actually submit if confirmed - null for HOLD (nothing to confirm/submit). */
    private final GrandExchangeAction geAction;
    private final int quantity;
    private final int price;
    private final double confidence;
    private final String checkpointVersion;
    private final long receivedAtMillis;

    public PPOFlipperDecision(long tickId, int itemId, String itemName, String actionName,
                               GrandExchangeAction geAction, int quantity, int price, double confidence,
                               String checkpointVersion, long receivedAtMillis) {
        this.tickId = tickId;
        this.itemId = itemId;
        this.itemName = itemName;
        this.actionName = actionName;
        this.geAction = geAction;
        this.quantity = quantity;
        this.price = price;
        this.confidence = confidence;
        this.checkpointVersion = checkpointVersion;
        this.receivedAtMillis = receivedAtMillis;
    }

    /** True for a non-HOLD suggestion that actually has something a human could confirm into an order. */
    public boolean isActionable() {
        return geAction != null && quantity > 0;
    }

    @Override
    public String toString() {
        if (!isActionable()) {
            return String.format("HOLD %s (confidence %.2f)", itemName, confidence);
        }
        return String.format("%s %dx %s @ %d gp (confidence %.2f)", actionName, quantity, itemName, price, confidence);
    }
}
