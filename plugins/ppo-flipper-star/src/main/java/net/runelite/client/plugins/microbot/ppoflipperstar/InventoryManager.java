package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

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
 * <p><b>Noted items are normalized to their unnoted id.</b> {@code Rs2ItemModel.getId()} returns
 * the item's raw, as-held id - a noted stack's id is the game's genuinely distinct noted-variant
 * item id, never folded back to the unnoted id by the model itself. Every quantity method here
 * resolves a noted item down to its unnoted counterpart before counting/keying (see
 * {@link #canonicalItemId}'s javadoc for exactly how, and a real bug that approach avoids), since
 * every other id this plugin deals with (the GE, the wiki price API, the watchlist, guardrails)
 * is unnoted - without this, a noted holding is invisible to portfolio/guardrail checks keyed by
 * the unnoted id.
 */
@Slf4j
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
            holdings.merge(canonicalItemId(item), item.getQuantity(), Integer::sum);
        }
        return holdings;
    }

    /**
     * Resolves {@code item} to its canonical (unnoted) id - see class javadoc's noted-item
     * section for why this exists at all, and this method's own javadoc for why it does NOT use
     * {@link Rs2ItemModel#getUnNotedId()} despite that method existing for exactly this purpose.
     *
     * <p><b>{@code getUnNotedId()} is confirmed broken for a genuinely noted item</b> - found live
     * (a real, confirmed-noted 3x Marrentill and a noted Rune pickaxe both read back as 0 held
     * despite this class already normalizing noted ids). Traced via bytecode decompilation: for a
     * noted item, {@code getUnNotedId()}'s noted branch reads {@code ItemComposition.getLinkedNoteId()}
     * - but that field is the FORWARD link (unnoted composition -&gt; its noted variant's id), not the
     * reverse. On an already-noted item's own composition, that field is unset/wrong, so the method
     * falls back to {@code item.getId()} - returning the noted id itself, unchanged, not the true
     * unnoted id. Every downstream consumer keyed off that "unnoted id" was actually keying off the
     * noted id instead, so a held quantity keyed by the real unnoted id (e.g. 251 for Marrentill)
     * found nothing.
     *
     * <p>Fix: resolve via {@link Rs2ItemManager#getItemIdByName(String, boolean)} on the item's own
     * display name instead - separately confirmed correct for both noted and unnoted items (a
     * noted item's {@code ItemComposition.getName()} is the plain unnoted display name, e.g.
     * "Marrentill", not "Marrentill (noted)" - the client only appends that in tooltips, not the
     * composition itself). Only takes this path when {@link Rs2ItemModel#isNoted()} is true - an
     * unnoted item's own id is already canonical, no resolution needed, and skipping the extra
     * lookup keeps the common case cheap.
     */
    private int canonicalItemId(Rs2ItemModel item) {
        if (!item.isNoted()) {
            return item.getId();
        }
        int resolvedId = Rs2ItemManager.getItemIdByName(item.getName(), true);
        int result = resolvedId > 0 ? resolvedId : item.getId();
        // Logged at WARN, always on (no logger config needed), ONLY on the two failure cases - this
        // is the exact resolution step a real, still-unexplained incident keeps pointing back to
        // (a noted stack's real held quantity not being found by the id the rest of the plugin -
        // the wiki mapping, DecisionEngine, order.getItemId() - uses for the same real item).
        // Two distinct ways this can go wrong, both worth flagging separately:
        //   1. getItemIdByName finds nothing at all (resolvedId <= 0) - falls back to the raw
        //      noted id unchanged, an obviously-wrong id for anything expecting the unnoted one.
        //   2. getItemIdByName "succeeds" but returns the SAME id as the raw noted item
        //      (resolvedId == item.getId()) - a silent, more insidious failure: looks like a
        //      normal resolved id, but is actually still the noted variant's own id, not the true
        //      unnoted one. This is the shape a caller keying off the wiki's unnoted id (which is
        //      never the noted id) would see as "not found" with no obvious error anywhere.
        // Deliberately silent on genuine success (called on every inventory snapshot, for every
        // noted stack held - logging every call, not just failures, would flood client.log for no
        // benefit once this is confirmed working).
        if (resolvedId <= 0) {
            log.warn("PPOFlipperStar: canonicalItemId - noted item \"{}\" (rawId={}) - getItemIdByName found NO " +
                "unnoted id at all, falling back to the raw noted id unchanged - this item's held quantity will " +
                "be invisible to anything keyed by its real unnoted id.",
                item.getName(), item.getId());
        } else if (resolvedId == item.getId()) {
            log.warn("PPOFlipperStar: canonicalItemId - noted item \"{}\" (rawId={}) - getItemIdByName resolved " +
                "back to the SAME id as the raw noted item, not a different unnoted id - resolution likely did " +
                "NOT actually find the true unnoted id, even though it didn't fail outright.",
                item.getName(), item.getId());
        }
        return result;
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
        Map<Integer, Integer> snapshot = snapshotByItemId();
        int result = snapshot.getOrDefault(itemId, 0);
        // Logged at INFO, always on - pairs with canonicalItemId's own logging above: if a caller
        // queries an id that genuinely isn't in the snapshot at all (result 0) while the inventory
        // is NOT actually empty, that's the exact live evidence needed to pin down a still-
        // unexplained noted-item mismatch report - shows up in client.log automatically, no logger
        // config needed to see it.
        if (result == 0 && !snapshot.isEmpty()) {
            log.info("PPOFlipperStar: getQuantity({}) -> 0, but inventory snapshot has these ids/qty: {}", itemId, snapshot);
        }
        return result;
    }

    public int getQuantity(String itemName) {
        return Rs2Inventory.itemQuantity(itemName);
    }

    public boolean waitForChanges(int timeoutMillis) {
        return Rs2Inventory.waitForInventoryChanges(timeoutMillis);
    }
}
