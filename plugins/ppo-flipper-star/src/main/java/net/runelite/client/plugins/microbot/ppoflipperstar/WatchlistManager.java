package net.runelite.client.plugins.microbot.ppoflipperstar;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreSync;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of item ids the (future) autonomous PPO policy is allowed to act on. Manual actions
 * via the panel or right-click menu always work regardless of watchlist membership - this is
 * purely the scoping boundary for autonomous behavior once a later milestone wires that up; in
 * this milestone nothing reads it for decision-making yet, only the right-click "Watch"/
 * "Unwatch" toggle and the panel display it.
 *
 * <p>Persisted the same hand-rolled-JSON-via-Gson way as {@link portfolio.BuyLimitLedger} and
 * {@link portfolio.PortfolioManager} - see {@code BuyLimitLedger}'s javadoc for why
 * {@code ConfigManager}'s generic {@code Object} overload isn't used.
 */
@Slf4j
@Singleton
public class WatchlistManager {

    private static final String CONFIG_GROUP = "ppoflipperstar";
    private static final String WATCHLIST_KEY = "watchlistItemIds";
    private static final Type WATCHLIST_TYPE = new TypeToken<Set<Integer>>() {}.getType();

    private final ConfigManager configManager;
    private final PPOFlipperStarFirestoreSync firestoreSync;
    private final Gson gson = new Gson();

    private final Set<Integer> watchedItemIds;

    @Inject
    public WatchlistManager(ConfigManager configManager, PPOFlipperStarFirestoreSync firestoreSync) {
        this.configManager = configManager;
        this.firestoreSync = firestoreSync;
        this.watchedItemIds = load();
    }

    /**
     * Reconciles the local watchlist against a Firestore pull, Firestore winning per this
     * project's "Firestore is the source of truth" decision - the local set becomes exactly the
     * union of what was already local and what Firestore returned (a plain union, not a replace:
     * an item added locally moments before this pull ran, whose push hasn't landed yet, should
     * not be dropped just because Firestore doesn't know about it yet).
     */
    public synchronized void reconcileFromFirestore(List<Integer> remoteItemIds) {
        if (remoteItemIds == null || remoteItemIds.isEmpty()) return;
        boolean changed = false;
        for (int itemId : remoteItemIds) {
            changed |= watchedItemIds.add(itemId);
        }
        if (changed) {
            persist();
        }
        log.info("PPOFlipperStar: reconciled watchlist with {} item(s) from Firestore.", remoteItemIds.size());
    }

    private Set<Integer> load() {
        String json = configManager.getConfiguration(CONFIG_GROUP, WATCHLIST_KEY);
        if (json == null || json.isEmpty()) {
            return new LinkedHashSet<>();
        }
        try {
            Set<Integer> loaded = gson.fromJson(json, WATCHLIST_TYPE);
            return loaded != null ? new LinkedHashSet<>(loaded) : new LinkedHashSet<>();
        } catch (JsonSyntaxException e) {
            log.warn("PPOFlipperStar: watchlist config was not valid JSON, resetting it - {}", e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    private void persist() {
        configManager.setConfiguration(CONFIG_GROUP, WATCHLIST_KEY, gson.toJson(watchedItemIds, WATCHLIST_TYPE));
    }

    public synchronized void add(int itemId) {
        if (watchedItemIds.add(itemId)) {
            persist();
            if (firestoreSync.isEnabled()) {
                firestoreSync.pushWatchlistAddAsync(itemId);
            }
        }
    }

    public synchronized void remove(int itemId) {
        if (watchedItemIds.remove(itemId)) {
            persist();
            if (firestoreSync.isEnabled()) {
                firestoreSync.pushWatchlistRemoveAsync(itemId);
            }
        }
    }

    public synchronized boolean contains(int itemId) {
        return watchedItemIds.contains(itemId);
    }

    public synchronized Set<Integer> getAll() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(watchedItemIds));
    }
}
