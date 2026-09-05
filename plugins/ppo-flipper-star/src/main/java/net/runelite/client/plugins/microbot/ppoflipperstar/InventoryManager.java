package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import javax.inject.Inject;
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

    private final ItemNameResolver itemNameResolver;

    @Inject
    public InventoryManager(ItemNameResolver itemNameResolver) {
        this.itemNameResolver = itemNameResolver;
    }

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
     * section for why this exists at all.
     *
     * <p><b>Two confirmed-broken approaches ruled out before landing on {@link ItemNameResolver}:</b>
     * <ul>
     *   <li>{@link Rs2ItemModel#getUnNotedId()} - confirmed broken via bytecode decompilation: for
     *   a noted item, its noted branch reads {@code ItemComposition.getLinkedNoteId()}, which is
     *   the FORWARD link (unnoted composition -&gt; its noted variant's id), not the reverse. On an
     *   already-noted item's own composition, that field is unset/wrong, so it falls back to
     *   {@code item.getId()} - the noted id itself, unchanged.</li>
     *   <li>{@code Rs2ItemManager.getItemIdByName(name, true)} (this class's own previous fix) -
     *   ALSO confirmed broken, via a second bytecode decompilation, for the same class of item:
     *   it is NOT a name-to-id database lookup at all. It checks {@code Rs2Bank.hasBankItem(name)}
     *   first, then {@code Rs2Inventory.hasItem(name)}, and returns whichever LIVE-HELD item's raw
     *   id it finds - only falling back to a genuine {@code ItemManager.search(name)} database
     *   lookup if the name isn't currently held anywhere. For a noted item genuinely sitting in
     *   inventory (exactly this method's use case), the inventory check fires first and hands back
     *   the noted item's own raw id, completely unresolved. Confirmed live: EVERY noted item held
     *   across a real inventory (Yew longbow (u), Grapes, Ruby amulet, Sapphire ring, and more)
     *   silently resolved this way, each returning its own noted id back unchanged.</li>
     * </ul>
     * {@link ItemNameResolver} is a genuine, static name-&gt;id lookup against the wiki's own
     * mapping data instead - unaffected by what happens to be sitting in the account's live
     * inventory/bank, since it never reads either.
     */
    private int canonicalItemId(Rs2ItemModel item) {
        if (!item.isNoted()) {
            return item.getId();
        }
        int resolvedId = itemNameResolver.resolveId(item.getName());
        if (resolvedId <= 0) {
            log.warn("PPOFlipperStar: canonicalItemId - noted item \"{}\" (rawId={}) - ItemNameResolver found no " +
                "unnoted id at all, falling back to the raw noted id unchanged - this item's held quantity will " +
                "be invisible to anything keyed by its real unnoted id.",
                item.getName(), item.getId());
            return item.getId();
        }
        return resolvedId;
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
