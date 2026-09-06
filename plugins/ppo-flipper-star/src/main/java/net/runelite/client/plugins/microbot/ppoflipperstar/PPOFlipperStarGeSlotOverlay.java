package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;

/**
 * Draws a small countdown directly on top of each of the Grand Exchange interface's 8 offer
 * slots, showing how much longer that slot's live offer has before it becomes eligible to be
 * aborted by the stale-offer check ({@code staleOfferTimeoutMinutes} - see
 * {@code PPOFlipperStarScript#isStale}/{@code #checkForFinishedOffers}'s own javadoc for the full
 * eligibility rules, including that a genuine partial fill is never touched regardless of age).
 *
 * <p>Real ask: the sidebar panel and {@link PPOFlipperStarOverlay}'s fixed HUD already show
 * active-offer state, but neither answers "how much longer until THIS specific slot's offer might
 * get pulled" at a glance while actually looking at the GE interface itself - this renders right
 * next to each slot instead, so that answer doesn't require checking a separate panel.
 *
 * <p>Only ever a display - never a scheduling/decision input itself, unlike the countdown the
 * script's own {@code isStale}/eviction logic already computes for real enforcement (this overlay
 * reads the exact same {@code submittedAtMillis}/{@code staleOfferTimeoutMinutes} inputs and
 * mirrors that same math purely for the label, with zero side effects of its own).
 */
@Slf4j
public class PPOFlipperStarGeSlotOverlay extends Overlay {

    private static final Font TIMER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private long lastDiagnosticLogAtMillis = 0;

    private final PPOFlipperStarScript script;
    private final PPOFlipperStarConfig config;

    @Inject
    PPOFlipperStarGeSlotOverlay(PPOFlipperStarPlugin plugin, PPOFlipperStarScript script, PPOFlipperStarConfig config) {
        super(plugin);
        this.script = script;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        boolean shouldLog = System.currentTimeMillis() - lastDiagnosticLogAtMillis > 5000;
        if (shouldLog) lastDiagnosticLogAtMillis = System.currentTimeMillis();

        int timeoutMinutes = config.staleOfferTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            if (shouldLog) log.info("PPOFlipperStar: GeSlotOverlay - staleOfferTimeoutMinutes<=0, not rendering.");
            return null;
        }

        Map<GrandExchangeSlots, PPOFlipperOrder> activeOrders = script.getActiveOrders();
        if (activeOrders.isEmpty()) {
            if (shouldLog) log.info("PPOFlipperStar: GeSlotOverlay - activeOrders is empty, nothing to draw.");
            return null;
        }

        long timeoutMillis = timeoutMinutes * 60_000L;
        long now = System.currentTimeMillis();
        int drawn = 0;

        for (Map.Entry<GrandExchangeSlots, PPOFlipperOrder> entry : activeOrders.entrySet()) {
            PPOFlipperOrder order = entry.getValue();
            long submittedAt = order.getSubmittedAtMillis();
            // Never shown for a genuine partial fill - the stale-offer check itself never touches
            // one regardless of age (see its own javadoc: pulling a partial fill would strand the
            // already-filled portion's exit strategy), so a countdown implying it could be pulled
            // would be actively misleading here.
            if (submittedAt <= 0 || order.getQuantityFilled() > 0) {
                if (shouldLog) log.info("PPOFlipperStar: GeSlotOverlay - slot {} skipped (submittedAt={}, filled={})",
                    entry.getKey(), submittedAt, order.getQuantityFilled());
                continue;
            }

            Widget slotWidget = Rs2Widget.getWidget(465, 7 + entry.getKey().ordinal());
            if (slotWidget == null || slotWidget.isHidden()) {
                if (shouldLog) log.info("PPOFlipperStar: GeSlotOverlay - slot {} widget(465,{}) is {}",
                    entry.getKey(), 7 + entry.getKey().ordinal(), slotWidget == null ? "NULL" : "hidden");
                continue;
            }

            long remainingMillis = timeoutMillis - (now - submittedAt);
            String label = remainingMillis > 0
                ? formatDuration(remainingMillis)
                : "STALE";
            Color color = remainingMillis > 0
                ? (remainingMillis < 30_000 ? Color.ORANGE : Color.WHITE)
                : Color.RED;

            Rectangle bounds = slotWidget.getBounds();
            Point textLocation = new Point(bounds.x + bounds.width / 2 - 20, bounds.y - 4);
            graphics.setFont(TIMER_FONT);
            OverlayUtil.renderTextLocation(graphics, textLocation, label, color);
            drawn++;
            if (shouldLog) log.info("PPOFlipperStar: GeSlotOverlay - drew '{}' for slot {} at widget bounds {}",
                label, entry.getKey(), bounds);
        }

        if (shouldLog) log.info("PPOFlipperStar: GeSlotOverlay - render() called, {} of {} active order(s) drawn.",
            drawn, activeOrders.size());

        return null;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
