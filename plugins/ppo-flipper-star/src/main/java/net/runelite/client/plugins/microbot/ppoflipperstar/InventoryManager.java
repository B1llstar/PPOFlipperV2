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
 *
 * <p><b>Noted items are normalized to their unnoted id.</b> Verified via bytecode decompilation:
 * {@code Rs2ItemModel.getId()} returns the item's raw, as-held id - a noted stack's id is the
 * game's genuinely distinct noted-variant item id, never folded back to the unnoted id by the
 * model itself. {@code Rs2Inventory.itemQuantity(int)} matches strictly on that exact id (unlike
 * its {@code itemQuantity(String)} overload, separately confirmed to sum noted+unnoted by display
 * name - see {@code PPOFlipperStarScript#withdrawPreferringNotes}'s javadoc), so passing an
 * unnoted id would silently miss noted stock entirely rather than counting it. Every quantity
 * method here resolves noted ids down to their unnoted counterpart before counting/keying, since
 * every other id this plugin deals with (the GE, the wiki price API, the watchlist, guardrails)
 * is unnoted - without this, a noted holding would be invisible to portfolio/guardrail checks
 * keyed by the unnoted id.
 */
@Singleton
public class InventoryManager {

    /** Full live snapshot of every inventory item right now. */
    public List<Rs2ItemModel> snapshot() {
        return Rs2Inventory.all();
    }

    /**
     * Aggregated quantity-by-item-id snapshot, keyed by unnoted id, summing stacked/unstacked
     * AND noted/unnoted slots of the same logical item together - see class javadoc.
     */
    public Map<Integer, Integer> snapshotByItemId() {
        Map<Integer, Integer> holdings = new HashMap<>();
        for (Rs2ItemModel item : Rs2Inventory.all()) {
            int unnotedId = item.getUnNotedId();
            holdings.merge(unnotedId != -1 ? unnotedId : item.getId(), item.getQuantity(), Integer::sum);
        }
        return holdings;
    }

    public Rs2ItemModel getItemInSlot(int slot) {
        return Rs2Inventory.getItemInSlot(slot);
    }

    /**
     * Total held quantity for this (unnoted) item id, inventory-wide, counting a noted stack of
     * the same logical item too - see class javadoc. {@code itemId} is expected to already be
     * the unnoted id (as every other id in this plugin is).
     *
     * <p>Deliberately implemented via {@link #snapshotByItemId()} rather than
     * {@code Rs2Inventory.itemQuantity(int)} plus a separate noted-id resolution call - the
     * static {@code Rs2ItemModel.getNotedId(int)}/{@code getUnNotedId(int)} helpers each do a
     * fresh blocking client-thread round-trip per call (confirmed via bytecode decompilation:
     * they re-fetch the item's {@code ItemComposition} via {@code Client.getItemDefinition},
     * which is only safe to call on the client thread). {@link Rs2ItemModel}'s equivalent
     * <b>instance</b> methods ({@code getUnNotedId()}/{@code isNoted()}) are free - the
     * composition is already cached on the model - so building one snapshot from
     * {@code Rs2Inventory.all()} and reading it back is strictly cheaper than resolving a noted
     * id from a bare int. This was a real bug: an earlier version of this method (and
     * {@link BankManager#snapshotByItemId()}) called the static per-id overload inside/adjacent
     * to a loop invoked from the Swing panel's refresh timer on the AWT Event Dispatch Thread,
     * which froze the entire client window while the bank was open.
     */
    public int getQuantity(int itemId) {
        return snapshotByItemId().getOrDefault(itemId, 0);
    }

    public int getQuantity(String itemName) {
        return Rs2Inventory.itemQuantity(itemName);
    }

    public boolean waitForChanges(int timeoutMillis) {
        return Rs2Inventory.waitForInventoryChanges(timeoutMillis);
    }
}
