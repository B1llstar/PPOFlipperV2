package net.runelite.client.plugins.microbot.gestarv2;

import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import javax.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared order list between the sidebar panel (EDT, adds/removes orders) and the script
 * (its own scheduled-executor thread, submits/updates orders). Backed by a
 * {@link CopyOnWriteArrayList} - reads and iteration are frequent (panel repaint, script
 * ticks), writes are rare (user adds/removes an order, script advances one order's status),
 * so the copy-on-write cost is a non-issue at the handful of rows this plugin expects.
 *
 * Survives across Execute/Stop so pausing and resuming doesn't lose the queue; only
 * {@link #clear()} (an explicit user action) empties it.
 *
 * <p><b>Cross-plugin use (e.g. GE Flipper):</b> a sideloaded plugin gets its own
 * {@link ClassLoader}, so a separately-built plugin like GE Flipper cannot construct a
 * {@link GeStarOrder} directly - that class is a different, incompatible type in its
 * classloader. {@link #addOrder(GrandExchangeAction, String, int, int)} exists for exactly
 * this: it only takes types that come from the shared Microbot client jar or java.lang
 * (loaded once by the classloader every plugin's loader delegates to as parent), so another
 * plugin can call it via reflection - see GE Flipper's docs for the calling side of this.
 */
@Singleton
public class GeStarOrderQueue {

    public interface Listener {
        void onQueueChanged();
    }

    private final List<GeStarOrder> orders = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void add(GeStarOrder order) {
        orders.add(order);
        notifyChanged();
    }

    /**
     * Cross-plugin-safe order creation - see this class's javadoc. Returns the new order's id
     * (a primitive, safe to cross the classloader boundary) so a caller like GE Flipper can
     * track its own submitted orders without holding a reference to the GeStarOrder itself.
     */
    public long addOrder(GrandExchangeAction action, String itemName, int quantity, int price) {
        GeStarOrder order = new GeStarOrder(action, itemName, quantity, price);
        add(order);
        return order.getId();
    }

    /**
     * Cross-plugin-safe status lookup - see this class's javadoc. Returns the order's
     * {@link GeStarOrder.Status} as a plain {@link String} (via {@code name()}, safe to cross
     * the classloader boundary, unlike the enum constant itself) so a caller like GE Flipper
     * can check whether an order it queued has finished, without needing GeStarOrder.Status to
     * be the same type in both classloaders. Returns null if no order with that id exists
     * (e.g. it was removed).
     */
    public String getOrderStatusName(long orderId) {
        return orders.stream()
            .filter(o -> o.getId() == orderId)
            .findFirst()
            .map(o -> o.getStatus().name())
            .orElse(null);
    }

    public void remove(long orderId) {
        orders.removeIf(o -> o.getId() == orderId);
        notifyChanged();
    }

    public void clear() {
        orders.clear();
        notifyChanged();
    }

    public List<GeStarOrder> getAll() {
        return orders;
    }

    public Optional<GeStarOrder> nextQueued() {
        return orders.stream().filter(o -> o.getStatus() == GeStarOrder.Status.QUEUED).findFirst();
    }

    public List<GeStarOrder> getByStatus(GeStarOrder.Status status) {
        return orders.stream().filter(o -> o.getStatus() == status).collect(java.util.stream.Collectors.toList());
    }

    public long countByStatus(GeStarOrder.Status status) {
        return orders.stream().filter(o -> o.getStatus() == status).count();
    }

    /** Called after any mutation on an order object already in the queue (status/fill changes). */
    public void notifyChanged() {
        listeners.forEach(Listener::onQueueChanged);
    }
}
