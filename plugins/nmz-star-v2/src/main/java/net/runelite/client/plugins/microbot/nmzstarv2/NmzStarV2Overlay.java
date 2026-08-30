package net.runelite.client.plugins.microbot.nmzstarv2;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class NmzStarV2Overlay extends OverlayPanel {

    private final NmzStarV2Script script;

    @Inject
    NmzStarV2Overlay(NmzStarV2Plugin plugin, NmzStarV2Script script) {
        super(plugin);
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("NMZ Star V2")
            .color(Color.GREEN)
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("State")
            .right(script.getState().name())
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Target")
            .right(script.getCurrentTargetName())
            .build());

        return super.render(graphics);
    }
}
