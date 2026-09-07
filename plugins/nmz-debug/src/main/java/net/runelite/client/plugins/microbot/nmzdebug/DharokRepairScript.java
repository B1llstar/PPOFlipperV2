package net.runelite.client.plugins.microbot.nmzdebug;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.LoginManager;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Detects a fully-degraded ("broken") Dharok's armor piece equipped inside the NMZ arena, then
 * performs the whole interrupt-and-return trip: leave the instance, walk to Bob at Lumbridge,
 * repair everything, walk back to the NMZ entrance. Deliberately a synchronous, blocking sequence
 * called from {@link NmzDebugScript}'s own tick thread (not a second independently-scheduled
 * sub-script like {@link PrayerPotionScript}) - unlike prayer-potion topping, which genuinely wants
 * to run concurrently every 600ms alongside combat, this whole trip is inherently one long
 * sequential errand with nothing useful for the main script to do in the meantime.
 *
 * <p><b>Two real unknowns, called out rather than silently guessed at</b> (see this class's own
 * inline comments at each point): (1) Bob's exact Lumbridge coordinate is not present anywhere in
 * this codebase or the Microbot API and had to be sourced from general OSRS knowledge, not a
 * verified in-repo constant - confirm this live and adjust {@link #BOB_LOCATION} if it's off. (2)
 * Bob's "repair all" interface is a raw widget (not an {@code Rs2Dialogue} chat option, confirmed
 * via decompiling the Microbot jar - no such repair-shop utility exists there), and its exact
 * widget group/child ids are not known, so this uses {@link Rs2Widget#clickWidget(String)}'s
 * text-matching overload against the button's expected on-screen label instead of a numeric id,
 * the same idiom {@code NmzDebugScript.startNmzDream()} already uses for unfamiliar widgets. If
 * the real button text differs, that call is the one line to fix.
 */
final class DharokRepairScript {

    private DharokRepairScript() {
    }

    /**
     * The four fully-degraded ("broken") Dharok item ids, decompiled from the Microbot jar's
     * {@code net.runelite.api.gameval.ItemID} - a degraded Dharok piece is NOT tracked via any
     * charge/durability counter (confirmed: no such API exists anywhere in Rs2Equipment/
     * Rs2ItemModel/Rs2Inventory) but instead swaps to one of these explicit item ids once it hits
     * its final degrade tier and can no longer be worn until repaired. Matching directly on these
     * ids is simpler and more robust than a name-substring match, and doesn't depend on catching a
     * transient degrade chat message.
     */
    private static final int[] BROKEN_DHAROK_IDS = {
        ItemID.BARROWS_DHAROK_HEAD_BROKEN,
        ItemID.BARROWS_DHAROK_WEAPON_BROKEN,
        ItemID.BARROWS_DHAROK_BODY_BROKEN,
        ItemID.BARROWS_DHAROK_LEGS_BROKEN,
    };

    /**
     * NOT a verified in-repo constant - no Lumbridge/Bob coordinate exists anywhere in this
     * codebase (checked: nmz-debug, nmz-star-v2, and the whole vendor/microbot-hub tree). Sourced
     * from general knowledge of Bob's Brilliant Axes' real-world location, just south of Lumbridge
     * Castle. Confirm this live and correct it here if {@link Rs2Walker} lands somewhere Bob isn't
     * actually standing.
     */
    private static final WorldPoint BOB_LOCATION = new WorldPoint(3229, 3193, 0);

    /** Same NMZ entrance point {@link NmzDebugScript} already uses for its own initial walk-in. */
    private static final WorldPoint NMZ_ENTRANCE = new WorldPoint(2609, 3114, 0);

    /**
     * True if any equipped item is one of {@link #BROKEN_DHAROK_IDS} - the single check
     * {@link NmzDebugScript}'s tick loop should gate the whole repair trip on.
     */
    static boolean hasAnyBrokenDharokPiece() {
        boolean broken = Rs2Equipment.isWearing(BROKEN_DHAROK_IDS);
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.hasAnyBrokenDharokPiece: " + broken);
        return broken;
    }

    /**
     * The whole interrupt-and-return trip, run synchronously on the calling (script tick) thread.
     * Intended to be called once {@link #hasAnyBrokenDharokPiece()} is true, from a guard near the
     * very top of {@link NmzDebugScript}'s tick - see that call site's own comment for why this
     * fully replaces the normal inside/outside dispatch for as many ticks as the trip takes.
     */
    static void runRepairTrip() {
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.runRepairTrip: ENTER - broken Dharok piece detected, leaving NMZ to repair");

        leaveNmzInstance();
        walkToBobAndRepair();
        returnToNmz();

        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.runRepairTrip: EXIT");
    }

    /**
     * Leaves the NMZ instance via logout/login, per explicit instruction: clicking the in-arena
     * exit potion was offered as an alternative but its exact object/action text isn't confirmed
     * in this codebase, whereas logout->login is simple, deterministic, and already has a proven
     * pattern in this same plugin ({@code NmzDebugPlugin.reconnect}, used for real disconnects) -
     * reusing that exact mechanism here means this path is exercised by the same code that already
     * works, not a second, untested way of getting back in.
     */
    private static void leaveNmzInstance() {
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.leaveNmzInstance: logging out to exit the NMZ instance");
        Rs2Player.logout();
        sleepUntil(() -> !Microbot.isLoggedIn(), 15000);

        int membersWorld = LoginManager.getRandomWorld(true);
        boolean success = LoginManager.login(membersWorld);
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.leaveNmzInstance: LoginManager.login(" + membersWorld + ") -> " + success);
        sleepUntil(Microbot::isLoggedIn, 30000);
        sleep(2000, 3000);
    }

    /**
     * Walks to Bob, right-clicks Repair, and drives the resulting repair-all widget. See this
     * class's own javadoc for the two unverified assumptions here (Bob's coordinate, the repair
     * button's exact text).
     */
    private static void walkToBobAndRepair() {
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: walking to Bob at " + BOB_LOCATION);
        Rs2Walker.walkTo(BOB_LOCATION, 5);
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(BOB_LOCATION) < 10, 30000);

        Rs2NpcCache npcCache = Microbot.getRs2NpcCache();
        Rs2NpcModel bob = npcCache != null
            ? npcCache.query().withId(NpcID.BOB).nearestOnClientThread()
            : null;
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: Bob lookup -> "
            + (bob == null ? "NULL (not found nearby!)" : ("found at " + bob.getWorldLocation())));
        if (bob == null) {
            NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: BAIL, Bob not found - nothing to repair against, skipping to return leg");
            return;
        }

        boolean clicked = bob.click("Repair");
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: bob.click(\"Repair\") -> " + clicked);
        if (!clicked) {
            NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: BAIL, click failed - skipping to return leg");
            return;
        }

        // Bob's repair interface is a raw widget, not an Rs2Dialogue chat option (confirmed via
        // decompiling the Microbot jar - no repair-shop utility class exists there at all). No
        // known group/child widget id exists in this codebase for it, so this waits for and clicks
        // by the button's expected on-screen text, matching the idiom NmzDebugScript.startNmzDream
        // already uses for unfamiliar widgets ("Click here to continue", etc.) - if the real button
        // text differs from "Repair all", this is the one call to fix.
        boolean sawRepairWidget = sleepUntil(() -> Rs2Widget.hasWidget("Repair all"), 8000);
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: waited for 'Repair all' widget -> appeared=" + sawRepairWidget);
        if (!sawRepairWidget) {
            NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: BAIL, repair widget never appeared - returning to NMZ unrepaired");
            return;
        }

        boolean clickedRepairAll = Rs2Widget.clickWidget("Repair all");
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: clickWidget(\"Repair all\") -> " + clickedRepairAll);
        sleep(1200, 2000);

        boolean stillBroken = hasAnyBrokenDharokPiece();
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.walkToBobAndRepair: after repair-all click, stillBroken=" + stillBroken);
    }

    /** Pure webwalk back to the NMZ entrance - no minigame-teleport spell exists for NMZ in the Microbot API (confirmed: Rs2Magic has no such concept), so walking is the only viable return path. */
    private static void returnToNmz() {
        NmzDebugLog.log("[NMZDEBUG] DharokRepairScript.returnToNmz: walking back to NMZ entrance " + NMZ_ENTRANCE);
        Rs2Walker.walkTo(NMZ_ENTRANCE, 5);
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(NMZ_ENTRANCE) < 20, 60000);
    }
}
