package net.runelite.client.plugins.microbot.ppoflipperstar;

import javax.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared holder for the model's latest actionable proposed actions (PROPOSAL.md §3.6/§3.7's
 * shadow mode), between {@link PPOFlipperStarScript}'s DECIDE phase (writer, its own
 * scheduled-executor thread) and {@link PPOFlipperStarPanel}'s "Model suggestions" section
 * (reader/mutator, EDT). Mirrors {@link OrderQueue}'s shape/threading model deliberately -
 * same {@link CopyOnWriteArrayList}-backed, listener-notified pattern - since this is the same
 * kind of "one background thread writes, EDT reads and mutates on click" shared state.
 *
 * <p>HOLD suggestions and any suggestion below the configured confidence threshold are not
 * stored here at all (see {@code PPOFlipperStarScript.applySuggestions}) - only actionable
 * proposals a human might actually want to confirm ever show up in this list.
 *
 * <p><b>Confirming or dismissing a suggestion removes it from this list</b> - a stale suggestion
 * from an earlier tick is replaced wholesale by {@link #replaceAll} every time a new
 * decision/response lands, so nothing here is ever acted on twice or left dangling once its
 * tick has passed.
 */
@Singleton
public class DecisionSuggestions {

    public interface Listener {
        void onSuggestionsChanged();
    }

    private final List<PPOFlipperDecision> suggestions = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Most recent tickId this holder has ever displayed, so the panel can show "waiting on tick N" state if useful. -1 if nothing has ever been received. */
    private volatile long lastTickId = -1;

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Wholesale-replaces the current suggestion list with a new tick's actionable proposals -
     * called once per successfully-answered decision tick. A suggestion from the previous tick
     * that a human hadn't gotten to yet is simply dropped (the model's view of the world has
     * moved on by the next tick anyway - acting on a stale proposal would be operating on
     * outdated state, same reasoning as the plugin ignoring a stale decision/response tickId).
     */
    public void replaceAll(long tickId, List<PPOFlipperDecision> newSuggestions) {
        lastTickId = tickId;
        suggestions.clear();
        suggestions.addAll(newSuggestions);
        notifyChanged();
    }

    public List<PPOFlipperDecision> getAll() {
        return suggestions;
    }

    public Optional<PPOFlipperDecision> get(long id) {
        return suggestions.stream().filter(s -> s.getId() == id).findFirst();
    }

    /** Removes one suggestion (confirmed or dismissed) without waiting for the next tick to clear it. */
    public void remove(long id) {
        suggestions.removeIf(s -> s.getId() == id);
        notifyChanged();
    }

    public long getLastTickId() {
        return lastTickId;
    }

    private void notifyChanged() {
        listeners.forEach(Listener::onSuggestionsChanged);
    }
}
