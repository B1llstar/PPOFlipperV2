package net.runelite.client.plugins.microbot.gestarv2;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class GeStarV2Overlay extends OverlayPanel {

    private final GeStarV2Script script;
    private final GeStarOrderQueue queue;

    @Inject
    GeStarV2Overlay(GeStarV2Plugin plugin, GeStarV2Script script, GeStarOrderQueue queue) {
        super(plugin);
        this.script = script;
        this.queue = queue;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("GE Star V2")
            .color(Color.GREEN)
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("State")
            .right(script.getState().name())
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Queued orders")
            .right(String.valueOf(queue.countByStatus(GeStarOrder.Status.QUEUED)))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Active offers")
            .right(String.valueOf(script.getActiveOfferCount()))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("GP spent (session)")
            .right(String.valueOf(script.getGpSpentThisSession()))
            .build());

        return super.render(graphics);
    }
}
