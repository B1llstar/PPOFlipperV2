package net.runelite.client.plugins.microbot.ppoflipperstar.sync;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves and caches the stable per-account identifier used as the Firestore document key for
 * everything under {@code accounts/{accountHash}/...} - {@link net.runelite.api.Client#getAccountHash()}
 * (declared on {@code com.jagex.oldscape.pub.OAuthApi}, inherited by {@code Client}), not a
 * locally-generated UUID (doesn't follow the actual RuneScape account across reinstalls/
 * machines) and not the display name (changeable). See PROPOSAL.md's Firestore-persistence
 * addendum for why this specific identifier was chosen.
 *
 * <p>Reading {@code getAccountHash()} is only safe on the client thread, and only meaningful
 * once actually logged in (matches real Hub plugin usage, e.g.
 * {@code MahoganyHomesPlugin#loadFromConfig}, which reads {@code client.getAccountHash()} from an
 * event-subscriber callback rather than an arbitrary thread). This class resolves it lazily -
 * either reactively via {@link #onGameStateChanged} or, if nothing has logged in yet when a
 * caller needs it, by hopping onto the client thread itself and blocking briefly - and caches
 * the result so later calls (including sync pushes that happen off the client thread) are just a
 * volatile field read.
 *
 * <p>Never crashes or hangs the plugin if called before login: {@link #getAccountHash()} returns
 * empty in that case rather than blocking indefinitely, and callers (the Firestore sync layer)
 * are expected to simply skip cloud sync for that call - local ConfigManager storage works with
 * no account hash needed at all, which is the whole point of it remaining a cache/fallback that
 * also works pre-login/offline.
 */
@Slf4j
@Singleton
public class AccountIdentity {

    private static final long RESOLVE_TIMEOUT_MILLIS = 2000;

    // 0 is not a value getAccountHash() can return for a real logged-in session, so it doubles
    // as "not resolved yet" without needing a separate boolean/Optional field to keep in sync.
    private static final long UNRESOLVED = 0L;

    private final AtomicLong cachedAccountHash = new AtomicLong(UNRESOLVED);

    /**
     * Reactively caches the account hash as soon as the client reaches LOGGED_IN, so later calls
     * from any thread (e.g. a background sync push) never need to hop onto the client thread at
     * all. Safe to call every state change - re-resolves each login in case of an account switch
     * within the same client process (logout then a different account logging in).
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN) {
            // Clears the cached hash so a different account logging in next doesn't briefly race
            // against a stale value from the previous session - e.g. a sync push firing between
            // this logout and the next login's onGameStateChanged callback landing. Without this,
            // onLogout() was never actually invoked from anywhere, so a same-process account
            // switch (log out, log into a different account) kept attributing trades to the
            // first account's Firestore documents - a real cross-account data-integrity bug given
            // this plugin is explicitly designed to key everything by account.
            onLogout();
            return;
        }
        if (event.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        Microbot.getClientThread().invoke(() -> {
            if (Microbot.getClient().getGameState() == GameState.LOGGED_IN) {
                long hash = Microbot.getClient().getAccountHash();
                cachedAccountHash.set(hash);
                log.debug("PPOFlipperStar: resolved account hash on login.");
            }
        });
    }

    /**
     * Non-blocking read of whatever account hash is currently cached. Empty if nobody has
     * reached LOGGED_IN yet since this object was created (or since the last logout - see
     * {@link #onLogout()}).
     */
    public Optional<Long> getAccountHash() {
        long hash = cachedAccountHash.get();
        return hash == UNRESOLVED ? Optional.empty() : Optional.of(hash);
    }

    /**
     * Blocking variant for call sites that need the hash right now (e.g. startup reconciliation)
     * and are willing to wait briefly for the client thread to confirm login state, rather than
     * relying solely on {@link #onGameStateChanged} having already fired. Never blocks longer
     * than {@link #RESOLVE_TIMEOUT_MILLIS} and never throws - falls back to empty on any failure
     * (not logged in, client thread unavailable, interrupted wait) so a caller can always safely
     * fall back to local-only operation.
     */
    public Optional<Long> resolveBlocking() {
        Optional<Long> cached = getAccountHash();
        if (cached.isPresent()) {
            return cached;
        }

        try {
            Long resolved = Microbot.getClientThread().invoke(() -> {
                if (Microbot.getClient().getGameState() == GameState.LOGGED_IN) {
                    return Microbot.getClient().getAccountHash();
                }
                return null;
            });
            if (resolved != null) {
                cachedAccountHash.set(resolved);
                return Optional.of(resolved);
            }
        } catch (Exception e) {
            log.warn("PPOFlipperStar: failed to resolve account hash - {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** Clears the cached hash on logout so a subsequent different-account login doesn't reuse a stale value before {@link #onGameStateChanged} fires again. */
    public void onLogout() {
        cachedAccountHash.set(UNRESOLVED);
    }
}
