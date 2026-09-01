package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over {@link Rs2Inventory} - a live, always-up-to-date client-side read, no
 * caching needed (unlike {@link BankManager}, whose backing cache is only populated reactively
 * once the bank has been opened). Exists mainly so the rest of this plugin's classes depend on
 * a plugin-owned type rather than reaching into the Microbot util package directly from every
 * call site, and so a slot/snapshot query only has one place to change if the backing API ever
 * does.
 */
@Singleton
public class InventoryManager {

    /** Full live snapshot of every inventory item right now. */
    public List<Rs2ItemModel> snapshot() {
        return Rs2Inventory.all();
    }

    /** Aggregated quantity-by-item-id snapshot, summing stacked/unstacked slots of the same item. */
    public Map<Integer, Integer> snapshotByItemId() {
        Map<Integer, Integer> holdings = new HashMap<>();
        for (Rs2ItemModel item : Rs2Inventory.all()) {
            holdings.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return holdings;
    }

    public Rs2ItemModel getItemInSlot(int slot) {
        return Rs2Inventory.getItemInSlot(slot);
    }

    public int getQuantity(int itemId) {
        return Rs2Inventory.itemQuantity(itemId);
    }

    public int getQuantity(String itemName) {
        return Rs2Inventory.itemQuantity(itemName);
    }

    public boolean waitForChanges(int timeoutMillis) {
        return Rs2Inventory.waitForInventoryChanges(timeoutMillis);
    }
}
