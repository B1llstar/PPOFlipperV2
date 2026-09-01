package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/** Simple in-game HUD overlay: gold on hand and how many GE offers are currently active. */
public class PPOFlipperStarOverlay extends OverlayPanel {

    private final PPOFlipperStarScript script;
    private final GoldManager goldManager;

    @Inject
    PPOFlipperStarOverlay(PPOFlipperStarPlugin plugin, PPOFlipperStarScript script, GoldManager goldManager) {
        super(plugin);
        this.script = script;
        this.goldManager = goldManager;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("PPOFlipperStar")
            .color(Color.GREEN)
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("State")
            .right(script.getState().name())
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Gold (inv+bank)")
            .right(String.format("%,d", goldManager.getTotalGold()))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Active offers")
            .right(String.valueOf(script.getActiveOfferCount()))
            .build());

        return super.render(graphics);
    }
}
