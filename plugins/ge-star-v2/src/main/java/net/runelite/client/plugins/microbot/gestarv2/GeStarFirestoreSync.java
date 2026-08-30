package net.runelite.client.plugins.microbot.gestarv2;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors orders between the web UI's Firestore "orders" collection and the local
 * {@link GeStarOrderQueue}: pulls new QUEUED documents in as local orders, and pushes local
 * status/fill changes back to whichever document each order came from. Runs on its own
 * schedule independent of the game-tick script loop, since HTTP calls have no business
 * blocking gameplay.
 *
 * The service-account JSON never gets bundled into the plugin jar - it's read from an
 * external file path set in config, exactly like any other local secret. See
 * GoogleServiceAccountAuth for the auth flow and README.md's "Web sync" section for setup.
 */
@Slf4j
public class GeStarFirestoreSync {

    private static final int POLL_INTERVAL_SECONDS = 5;

    private final GeStarOrderQueue queue;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pollTask;
    private GeStarFirestoreClient client;

    // Tracks which Firestore doc IDs have already been pulled in, so a QUEUED document isn't
    // re-added every poll while its local order is still QUEUED/SUBMITTED (it only leaves
    // Firestore's QUEUED-status query result once we push a later status back to it, which
    // can lag a poll cycle behind).
    private final Set<String> seenDocIds = new HashSet<>();

    // Last (status, quantityFilled) pushed per doc ID, so an unchanged order isn't
    // re-PATCHed every poll cycle - only pushed when something actually moved.
    private final Map<String, String> lastPushedState = new HashMap<>();

    @Inject
    public GeStarFirestoreSync(GeStarOrderQueue queue) {
        this.queue = queue;
    }

    public synchronized void start(GeStarV2Config config) {
        stop();

        String pathText = config.firestoreServiceAccountPath();
        if (pathText == null || pathText.trim().isEmpty()) {
            log.warn("GE Star V2: web sync enabled but no service account path configured, not starting.");
            return;
        }

        Path path = Paths.get(pathText.trim());
        if (!Files.isRegularFile(path)) {
            log.error("GE Star V2: service account file not found at {}, not starting web sync.", path);
            return;
        }

        try {
            GoogleServiceAccountAuth auth = new GoogleServiceAccountAuth(path);
            client = new GeStarFirestoreClient(auth);
        } catch (Exception e) {
            log.error("GE Star V2: failed to initialize Firestore auth - {}", e.getMessage(), e);
            return;
        }

        seenDocIds.clear();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GeStarV2-FirestoreSync");
            t.setDaemon(true);
            return t;
        });

        pollTask = executor.scheduleWithFixedDelay(this::pollOnce, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("GE Star V2: web sync started, polling every {}s.", POLL_INTERVAL_SECONDS);
    }

    public synchronized void stop() {
        if (pollTask != null) {
            pollTask.cancel(true);
            pollTask = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        client = null;
    }

    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    private void pollOnce() {
        if (client == null) return;
        try {
            pullNewOrders();
            pushLocalUpdates();
        } catch (Exception e) {
            log.warn("GE Star V2: Firestore sync error - {}", e.getMessage());
        }
    }

    private void pullNewOrders() throws Exception {
        for (GeStarFirestoreClient.RemoteOrder remote : client.listQueuedOrders()) {
            if (!seenDocIds.add(remote.docId)) continue;

            GeStarOrder order = new GeStarOrder(remote.action, remote.itemName, remote.quantity, remote.price);
            order.setFirestoreDocId(remote.docId);
            queue.add(order);
            log.info("GE Star V2: pulled order from web UI - {}", order);
        }
    }

    private void pushLocalUpdates() {
        for (GeStarOrder order : queue.getAll()) {
            String docId = order.getFirestoreDocId();
            if (docId == null) continue;

            String state = order.getStatus() + ":" + order.getQuantityFilled();
            if (state.equals(lastPushedState.get(docId))) continue;

            try {
                client.updateOrderStatus(order);
                lastPushedState.put(docId, state);
            } catch (Exception e) {
                log.warn("GE Star V2: failed to push status for order {} - {}", docId, e.getMessage());
            }
        }
    }
}
