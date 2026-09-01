package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

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
 * the bank first (directly, or via this plugin's bank-refresh behavior) rather than assuming
 * this class does it implicitly on every read - a read here is deliberately non-blocking and
 * never walks/opens anything itself.
 */
@Singleton
public class BankManager {

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

    public Map<Integer, Integer> snapshotByItemId() {
        Map<Integer, Integer> holdings = new HashMap<>();
        for (Rs2ItemModel item : Rs2Bank.bankItems()) {
            holdings.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return holdings;
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
