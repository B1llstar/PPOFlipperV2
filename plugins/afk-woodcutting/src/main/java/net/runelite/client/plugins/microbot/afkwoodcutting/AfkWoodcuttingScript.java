package net.runelite.client.plugins.microbot.afkwoodcutting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.concurrent.TimeUnit;

@Slf4j
public class AfkWoodcuttingScript extends Script {

    public boolean run(AfkWoodcuttingConfig config) {
        Microbot.enableAutoRunOn = true;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }

                if (Rs2Player.isAnimating() || Rs2Player.isMoving()) {
                    return;
                }

                if (Rs2Inventory.isFull()) {
                    if (config.dropLogs()) {
                        Rs2Inventory.dropAll(item -> item.getName().toLowerCase().contains("logs"));
                    } else {
                        Rs2Bank.walkToBank();
                        Rs2Bank.depositAll();
                        Rs2Bank.closeBank();
                    }
                    return;
                }

                if (Rs2GameObject.interact(config.treeName(), "Chop down")) {
                    Rs2Player.waitForXpDrop(Skill.WOODCUTTING);
                }
            } catch (Exception ex) {
                log.error("AfkWoodcuttingScript error", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
