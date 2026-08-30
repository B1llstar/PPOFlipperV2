package net.runelite.client.plugins.microbot.gestarv2;

import lombok.Getter;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

/**
 * One line of a GE Star order list: {@code itemName,quantity,price}.
 * Price is the per-item offer price the user wants submitted (not a live market price).
 */
@Getter
public class GeStarOrder {

    private final GrandExchangeAction action;
    private final String itemName;
    private final int quantity;
    private final int price;

    private GeStarOrder(GrandExchangeAction action, String itemName, int quantity, int price) {
        this.action = action;
        this.itemName = itemName;
        this.quantity = quantity;
        this.price = price;
    }

    /**
     * Parses a single "name,quantity,price" line. Returns null (rather than throwing) on a
     * malformed line so one bad row in the config box doesn't take down the whole batch.
     */
    public static GeStarOrder parse(GrandExchangeAction action, String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;

        String[] parts = trimmed.split(",");
        if (parts.length != 3) return null;

        String name = parts[0].trim();
        if (name.isEmpty()) return null;

        try {
            int quantity = Integer.parseInt(parts[1].trim());
            int price = Integer.parseInt(parts[2].trim());
            if (quantity <= 0 || price <= 0) return null;
            return new GeStarOrder(action, name, quantity, price);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public long totalValue() {
        return (long) quantity * price;
    }

    @Override
    public String toString() {
        return String.format("%s %dx %s @ %d gp", action, quantity, itemName, price);
    }
}
