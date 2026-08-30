package net.runelite.client.plugins.microbot.gestarv2;

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
