package net.runelite.client.plugins.microbot.afkwoodcutting;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;

public class AfkWoodcuttingOverlay extends OverlayPanel {

    @Inject
    AfkWoodcuttingOverlay(AfkWoodcuttingPlugin plugin) {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(
            net.runelite.client.ui.overlay.components.LineComponent.builder()
                .left("Afk Woodcutting")
                .right("running")
                .build()
        );
        return super.render(graphics);
    }
}
