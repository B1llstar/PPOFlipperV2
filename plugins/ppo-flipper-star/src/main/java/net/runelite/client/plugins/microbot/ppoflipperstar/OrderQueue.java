package net.runelite.client.plugins.microbot.ppoflipperstar;

import javax.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Shared order list between the sidebar panel (EDT, adds/removes orders), the right-click menu
 * handlers (also EDT), and {@link PPOFlipperStarScript} (its own scheduled-executor thread,
 * submits/updates orders). Backed by a {@link CopyOnWriteArrayList} - reads and iteration are
 * frequent (panel repaint, script ticks), writes are rare (user adds/removes an order, script
 * advances one order's status), so the copy-on-write cost is a non-issue at the handful of rows
 * this plugin expects.
 *
 * <p>Survives across Execute/Stop so pausing and resuming doesn't lose the queue; only
 * {@link #clear()} (an explicit user action) empties it. Deliberately a plain singleton with no
 * cross-plugin/reflection surface (unlike ge-star-v2's equivalent, which exposes an
 * id/String-only API for flipper-star to call across the classloader boundary) - PPOFlipperStar
 * has zero runtime dependency on any other plugin, so there is no other caller to support.
 */
@Singleton
public class OrderQueue {

    public interface Listener {
        void onQueueChanged();
    }

    private final List<PPOFlipperOrder> orders = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void add(PPOFlipperOrder order) {
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

    public List<PPOFlipperOrder> getAll() {
        return orders;
    }

    public Optional<PPOFlipperOrder> nextQueued() {
        return orders.stream().filter(o -> o.getStatus() == PPOFlipperOrder.Status.QUEUED).findFirst();
    }

    public List<PPOFlipperOrder> getByStatus(PPOFlipperOrder.Status status) {
        return orders.stream().filter(o -> o.getStatus() == status).collect(Collectors.toList());
    }

    public long countByStatus(PPOFlipperOrder.Status status) {
        return orders.stream().filter(o -> o.getStatus() == status).count();
    }

    /** Called after any mutation on an order object already in the queue (status/fill changes). */
    public void notifyChanged() {
        listeners.forEach(Listener::onQueueChanged);
    }
}
