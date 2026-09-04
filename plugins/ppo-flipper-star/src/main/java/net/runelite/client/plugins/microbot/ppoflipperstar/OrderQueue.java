package net.runelite.client.plugins.microbot.ppoflipperstar;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p><b>Persistence:</b> only {@link net.runelite.client.plugins.microbot.ppoflipperstar.PPOFlipperOrder.Status#QUEUED}
 * and {@code SUBMITTED} orders are persisted (hand-rolled JSON via Gson through
 * {@link ConfigManager}'s plain {@code String} overloads - same safe pattern as
 * {@code PortfolioManager}/{@code BuyLimitLedger}, see either one's javadoc for why the generic
 * {@code setConfiguration(group, key, Object)} overload isn't used). A closer look at why only
 * those two statuses: a {@code QUEUED} order added but not yet submitted to the GE would
 * otherwise be silently lost on a client crash/restart with no trace anywhere - this is the real
 * gap this persistence closes. A {@code SUBMITTED} order is already recovered independently by
 * {@link PPOFlipperStarScript#reconcileSubmittedOrders} (which checks live GE offer state, not
 * this queue) on the next Execute - persisting it here too is a convenience so the panel shows
 * something sensible immediately on startup rather than an empty list until Execute reconciles,
 * not a second source of truth competing with that reconciliation. Terminal orders (DONE/
 * SKIPPED/FAILED) are NOT persisted: they carry no risk of being "lost work" (the actual record
 * of what happened lives in {@code PortfolioManager}/Firestore's {@code tradeHistory}), and the
 * panel already leaves them in the list until a user manually removes them, so persisting them
 * forever would just accumulate an ever-growing local JSON blob for purely cosmetic history
 * Firestore already keeps properly.
 */
@Slf4j
@Singleton
public class OrderQueue {

    private static final String CONFIG_GROUP = "ppoflipperstar";
    private static final String QUEUE_KEY = "orderQueue";
    private static final Type QUEUE_TYPE = new TypeToken<List<PPOFlipperOrder>>() {}.getType();

    public interface Listener {
        void onQueueChanged();
    }

    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    private final List<PPOFlipperOrder> orders;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    @Inject
    public OrderQueue(ConfigManager configManager) {
        this.configManager = configManager;
        this.orders = new CopyOnWriteArrayList<>(loadPersistedOrders());
    }

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

    /**
     * The next QUEUED order to submit: SELL orders first (as a group, in their own FIFO order),
     * then BUY/COLLECT orders (also FIFO among themselves) - not plain insertion-order FIFO across
     * everything. Mirrors the same SELL-before-BUY priority {@code PPOFlipperStarScript
     * .autonomouslySubmit} already applies when new suggestions are first added to this queue (see
     * its javadoc): a SELL represents capital/inventory already committed that could be freed up,
     * a queued BUY is still just a speculative opportunity, so letting BUYs already ahead of it in
     * the queue delay it further has the same downside that fix addressed, just one step later in
     * the pipeline. Only reorders selection among QUEUED orders - never touches anything already
     * SUBMITTED (an in-flight GE offer), so this has no interaction with orders already placed.
     */
    public Optional<PPOFlipperOrder> nextQueued() {
        List<PPOFlipperOrder> queued = orders.stream()
            .filter(o -> o.getStatus() == PPOFlipperOrder.Status.QUEUED)
            .collect(Collectors.toList());
        return queued.stream()
            .filter(o -> o.getAction() == GrandExchangeAction.SELL)
            .findFirst()
            .or(() -> queued.stream().findFirst());
    }

    public List<PPOFlipperOrder> getByStatus(PPOFlipperOrder.Status status) {
        return orders.stream().filter(o -> o.getStatus() == status).collect(Collectors.toList());
    }

    public long countByStatus(PPOFlipperOrder.Status status) {
        return orders.stream().filter(o -> o.getStatus() == status).count();
    }

    /**
     * Called after any mutation on an order object already in the queue (status/fill changes),
     * and by {@link #add}/{@link #remove}/{@link #clear} above. Persists the current
     * QUEUED/SUBMITTED snapshot on every call in addition to notifying listeners - simplest
     * correct option given how infrequently orders actually change (a handful of times per
     * minute at most, driven by user actions and the script's own tick loop, not a hot path)
     * rather than trying to track which specific mutation needs persisting.
     */
    public void notifyChanged() {
        persistOrders();
        listeners.forEach(Listener::onQueueChanged);
    }

    private void persistOrders() {
        List<PPOFlipperOrder> toPersist = orders.stream()
            .filter(o -> o.getStatus() == PPOFlipperOrder.Status.QUEUED || o.getStatus() == PPOFlipperOrder.Status.SUBMITTED)
            .collect(Collectors.toList());
        configManager.setConfiguration(CONFIG_GROUP, QUEUE_KEY, gson.toJson(toPersist, QUEUE_TYPE));
    }

    /**
     * Restores whatever QUEUED/SUBMITTED orders survived the last session, and advances
     * {@link PPOFlipperOrder}'s id counter past the highest restored id - Gson deserialization
     * bypasses the constructor entirely (it sets fields directly via reflection), so without
     * this a freshly-created order after restart could collide with a restored order's id, since
     * the in-process {@code NEXT_ID} counter has no way to know what ids were used in a previous
     * JVM run otherwise.
     */
    private List<PPOFlipperOrder> loadPersistedOrders() {
        String json = configManager.getConfiguration(CONFIG_GROUP, QUEUE_KEY);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }

        List<PPOFlipperOrder> restored;
        try {
            restored = gson.fromJson(json, QUEUE_TYPE);
        } catch (JsonSyntaxException e) {
            log.warn("PPOFlipperStar: order queue config was not valid JSON, starting with an empty queue - {}", e.getMessage());
            return new ArrayList<>();
        }
        if (restored == null) {
            return new ArrayList<>();
        }

        long maxRestoredId = restored.stream().mapToLong(PPOFlipperOrder::getId).max().orElse(0L);
        PPOFlipperOrder.ensureNextIdAtLeast(maxRestoredId + 1);

        log.info("PPOFlipperStar: restored {} order(s) from a previous session.", restored.size());
        return restored;
    }
}
