package net.runelite.client.plugins.microbot.ppoflipperstar;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-endpoint HTTP timeout that stays short on a healthy network and only lengthens once the
 * network has actually shown itself to be slow - never lengthened unconditionally.
 *
 * <p><b>Real incident this exists for:</b> every wiki price lookup and every Firestore
 * {@code decision/request} write was timing out continuously for hours on a degraded network
 * connection (confirmed live: the wiki's own API consistently took ~6s to respond, right up
 * against this plugin's existing flat 5-10s timeouts) - DecisionEngine had no fresh prices and no
 * decision responses to act on, so the bot sat idle with nothing to trade. Raising every timeout
 * flatly would fix that, but at the cost of a real outage (the network is actually down, not just
 * slow) taking that much longer to notice and fall back from on every single call, all the time -
 * not an acceptable tradeoff on a normally-fast connection.
 *
 * <p>Instead: starts at {@code baseTimeout}. Each call to {@link #onTimeout()} increments a
 * consecutive-failure counter; once that counter reaches {@link #ESCALATE_AFTER}, the timeout used
 * for subsequent requests jumps to {@code baseTimeout * ESCALATION_MULTIPLIER} (capped at
 * {@code maxTimeout}) - i.e. only after the network has already demonstrated it's currently slow,
 * not preemptively. {@link #onSuccess()} immediately resets back to {@code baseTimeout} - the
 * escalation is a temporary accommodation for an ongoing slow stretch, not a permanent regression
 * once the network recovers. A single request's own outcome (timeout vs success) is the only
 * signal this needs; there is no separate background health-check.
 *
 * <p>One instance per logical endpoint (wiki price lookup, wiki mapping fetch, Firestore write,
 * etc.) - a slow Firestore write says nothing about whether the wiki is also slow right now, so
 * each gets to escalate/recover independently rather than one global flag flipping every timeout
 * plugin-wide off one endpoint's trouble.
 */
public class AdaptiveTimeout {

    private static final int ESCALATE_AFTER = 3;
    private static final int ESCALATION_MULTIPLIER = 3;

    private final Duration baseTimeout;
    private final Duration escalatedTimeout;
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);

    public AdaptiveTimeout(Duration baseTimeout, Duration maxTimeout) {
        this.baseTimeout = baseTimeout;
        Duration scaled = baseTimeout.multipliedBy(ESCALATION_MULTIPLIER);
        this.escalatedTimeout = scaled.compareTo(maxTimeout) > 0 ? maxTimeout : scaled;
    }

    /** The timeout to use for the next request - {@link #baseTimeout} unless the network has recently shown itself slow on this endpoint. */
    public Duration current() {
        return consecutiveTimeouts.get() >= ESCALATE_AFTER ? escalatedTimeout : baseTimeout;
    }

    /** Call when a request against this endpoint times out. */
    public void onTimeout() {
        consecutiveTimeouts.incrementAndGet();
    }

    /** Call when a request against this endpoint completes (any HTTP status - this tracks reachability/latency, not response correctness). Resets escalation immediately. */
    public void onSuccess() {
        consecutiveTimeouts.set(0);
    }
}
