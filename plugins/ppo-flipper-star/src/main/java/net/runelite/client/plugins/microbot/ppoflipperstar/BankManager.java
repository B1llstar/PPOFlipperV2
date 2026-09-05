package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over {@link Rs2Bank}. Unlike {@link InventoryManager}, a bank snapshot is backed
 * by a client-side cache ({@code Rs2BankData}) that is only populated/refreshed reactively once
 * the bank interface has actually been opened this session - {@link #snapshotByItemId()} can
 * legitimately be stale or empty if the bank hasn't been opened (or opened recently). Callers
 * that need trustworthy bank contents (see {@link PortfolioManager}) are responsible for opening
 * the bank first (directly, or via this plugin's bank-refresh behavior, see
 * {@code PPOFlipperStarScript#maybeRefreshBank()}) rather than assuming this class does it
 * implicitly on every read - a read here is deliberately non-blocking and never walks/opens
 * anything itself.
 *
 * <p><b>Noted items are normalized to their unnoted id</b> in {@link #snapshotByItemId()} via
 * {@link ItemNameResolver} - see {@link InventoryManager#canonicalItemId}'s javadoc for the full
 * incident history (two confirmed-broken prior approaches: {@code Rs2ItemModel.getUnNotedId()},
 * then {@code Rs2ItemManager.getItemIdByName}, which is NOT a real name-to-id lookup - it checks
 * live bank/inventory state first). The bank routinely holds noted stock (that's the entire point
 * of noting - compact storage), so this matters here at least as much as it does for inventory.
 */
@Singleton
public class BankManager {

    private final ItemNameResolver itemNameResolver;

    @Inject
    public BankManager(ItemNameResolver itemNameResolver) {
        this.itemNameResolver = itemNameResolver;
    }

    public boolean isOpen() {
        return Rs2Bank.isOpen();
    }

    public boolean open() {
        return Rs2Bank.openBank();
    }

    public boolean close() {
        return Rs2Bank.closeBank();
    }

    /** Live snapshot of the bank's cached contents - see class javadoc for staleness caveats. */
    public List<Rs2ItemModel> snapshot() {
        return Rs2Bank.bankItems();
    }

    /** Aggregated quantity-by-item-id snapshot, keyed by unnoted id - see class javadoc. */
    public Map<Integer, Integer> snapshotByItemId() {
        Map<Integer, Integer> holdings = new HashMap<>();
        for (Rs2ItemModel item : Rs2Bank.bankItems()) {
            holdings.merge(canonicalItemId(item), item.getQuantity(), Integer::sum);
        }
        return holdings;
    }

    /** See {@link InventoryManager#canonicalItemId}'s javadoc - identical fix, same reasoning. */
    private int canonicalItemId(Rs2ItemModel item) {
        if (!item.isNoted()) {
            return item.getId();
        }
        int resolvedId = itemNameResolver.resolveId(item.getName());
        return resolvedId > 0 ? resolvedId : item.getId();
    }

    public boolean withdrawX(String itemName, int quantity) {
        return Rs2Bank.withdrawX(itemName, quantity);
    }

    public boolean withdrawX(int itemId, int quantity) {
        return Rs2Bank.withdrawX(itemId, quantity);
    }

    public boolean depositAll() {
        return Rs2Bank.depositAll();
    }
}
