package net.runelite.client.plugins.microbot.nmzstarv2;

import net.runelite.api.gameval.NpcID;

/**
 * Nightmare Zone NPC ids, pulled from {@code net.runelite.api.gameval.NpcID}
 * in the Microbot client jar (verified against microbot-2.6.21.jar). NMZ
 * rotates a random subset of these bosses into the instance each dream, so
 * combat logic should query by "any of these ids nearby", not a single id.
 *
 * Names mirror the constant names in NpcID, not the in-game boss names.
 * The _HARD variants are the harder mode toggled by the reward shop; the
 * _NORMAL variants are the base fight. Both are listed since either can be
 * present depending on the player's account settings.
 */
public final class NmzNpcIds {

    private NmzNpcIds() {
    }

    // Non-combat / instance-control NPCs.
    public static final int NZONE_HOST = NpcID.NZONE_HOST; // "Dominic Onion" - dream host, not a combat target

    // Boss rotation, normal difficulty.
    public static final int[] BOSSES_NORMAL = {
            NpcID.NZONE_CONTACT_SCARAB_BOSS_NORMAL,
            NpcID.NZONE_BLOODDIAMOND_VAMPIREWARRIOR_NORMAL,
            NpcID.NZONE_ICEDIAMOND_ICEWARRIOR_NORMAL,
            NpcID.NZONE_FD_DAMIS_NORMAL_NORMAL,
            NpcID.NZONE_FD_DAMIS_TOUGHER_NORMAL,
            NpcID.NZONE_FIREDIAMOND_FIREWARRIOR_NORMAL,
            NpcID.NZONE_ELVARG_NORMAL,
            NpcID.NZONE_DREAM_INADEQUACY_NORMAL,
            NpcID.NZONE_DREAM_EVERLASTING_NORMAL,
            NpcID.NZONE_DREAM_UNTOUCHABLE_NORMAL,
            NpcID.NZONE_FAIRY_TANGLEFOOT_NORMAL,
            NpcID.NZONE_CHRONOZON_NORMAL,
            NpcID.NZONE_ARENA_BOUNCER_NORMAL,
            NpcID.NZONE_FRIS_TROLL_KING_TRUE_NORMAL,
            NpcID.NZONE_GRANDTREE_BLACKDEMON_NORMAL,
            NpcID.NZONE_GRIM_GLOD_NORMAL,
            NpcID.NZONE_HAUNTEDMINE_BOSS_GHOST_NORMAL,
            NpcID.NZONE_BLACK_KNIGHT_TITAN_NORMAL,
            NpcID.NZONE_HORROR_DAGGANOTH_AIR_NORMAL,
            NpcID.NZONE_HORROR_DAGGANOTH_WATER_NORMAL,
            NpcID.NZONE_HORROR_DAGGANOTH_FIRE_NORMAL,
            NpcID.NZONE_HORROR_DAGGANOTH_EARTH_NORMAL,
            NpcID.NZONE_HORROR_DAGGANOTH_RANGED_NORMAL,
            NpcID.NZONE_HORROR_DAGGANOTH_MELEE_NORMAL,
            NpcID.NZONE_CHICKENQUEST_EVIL_CHICKEN_NORMAL,
            NpcID.NZONE_HUNDRED_CULINAROMANCER_FINAL_NORMAL,
            NpcID.NZONE_HUNDRED_MINION1_NORMAL,
            NpcID.NZONE_HUNDRED_MINION2_NORMAL,
            NpcID.NZONE_HUNDRED_MINION3_NORMAL,
            NpcID.NZONE_HUNDRED_MINION4_NORMAL,
            NpcID.NZONE_HUNDRED_MINION5_AIR_NORMAL,
            NpcID.NZONE_HUNDRED_MINION5_MELEE_NORMAL,
            NpcID.NZONE_HUNDRED_MINION5_WATER_NORMAL,
            NpcID.NZONE_HUNDRED_MINION5_FIRE_NORMAL,
            NpcID.NZONE_HUNDRED_MINION5_RANGED_NORMAL,
            NpcID.NZONE_HUNDRED_MINION5_EARTH_NORMAL,
            NpcID.NZONE_NEZIKCHENED_NORMAL,
            NpcID.NZONE_TREE_SPIRIT_NORMAL,
            NpcID.NZONE_QUEST_LUNAR_MIRROR_OF_PLAYER_NORMAL,
            NpcID.NZONE_MM_DEMON_NORMAL,
            NpcID.NZONE_MDAUGHTER_BEARMAN_FIGHTER_NORMAL,
            NpcID.NZONE_MYARM_GIANT_ROC_NORMAL,
            NpcID.NZONE_SLAGILITH_NORMAL,
            NpcID.NZONE_ROVING_MOSSGIANT_NORMAL,
            NpcID.NZONE_SKELETON_HELLHOUND_NORMAL,
            NpcID.NZONE_AGRITH_NAAR_NORMAL,
            NpcID.NZONE_SUROK_KING_NORMAL,
            NpcID.NZONE_KHAZARD_WARLORD_NORMAL,
            NpcID.NZONE_TROLL_CHAMPION_NORMAL,
            NpcID.NZONE_TROLLROMANCE_ARRG_NORMAL,
            NpcID.NZONE_COUNT_DRAYNOR_NORMAL,
            NpcID.NZONE_SHAPESHIFTERGLOB_NORMAL,
            NpcID.NZONE_SHAPESHIFTERSPIDER_NORMAL,
            NpcID.NZONE_SHAPESHIFTERBEAR_NORMAL,
            NpcID.NZONE_SHAPESHIFTERWOLF_NORMAL,
            NpcID.NZONE_ZQ_MAINZOMBIE1_NORMAL,
            NpcID.NZONE_ZQ_MAINZOMBIE2_NORMAL,
            NpcID.NZONE_ZQ_MAINZOMBIE3_NORMAL,
            NpcID.NZONE_COW_NORMAL,
    };

    // Boss rotation, hard mode (reward-shop "Nightmare Zone difficulty" toggle).
    public static final int[] BOSSES_HARD = {
            NpcID.NZONE_CONTACT_SCARAB_BOSS_HARD,
            NpcID.NZONE_BLOODDIAMOND_VAMPIREWARRIOR_HARD,
            NpcID.NZONE_ICEDIAMOND_ICEWARRIOR_HARD,
            NpcID.NZONE_FD_DAMIS_NORMAL_HARD,
            NpcID.NZONE_FD_DAMIS_TOUGHER_HARD,
            NpcID.NZONE_FIREDIAMOND_FIREWARRIOR_HARD,
            NpcID.NZONE_ELVARG_HARD,
            NpcID.NZONE_DREAM_INADEQUACY_HARD,
            NpcID.NZONE_DREAM_EVERLASTING_HARD,
            NpcID.NZONE_DREAM_UNTOUCHABLE_HARD,
            NpcID.NZONE_FAIRY_TANGLEFOOT_HARD,
            NpcID.NZONE_CHRONOZON_HARD,
            NpcID.NZONE_ARENA_BOUNCER_HARD,
            NpcID.NZONE_FRIS_TROLL_KING_TRUE_HARD,
            NpcID.NZONE_GRANDTREE_BLACKDEMON_HARD,
            NpcID.NZONE_GRIM_GLOD_HARD,
            NpcID.NZONE_HAUNTEDMINE_BOSS_GHOST_HARD,
            NpcID.NZONE_BLACK_KNIGHT_TITAN_HARD,
            NpcID.NZONE_HORROR_DAGGANOTH_AIR_HARD,
            NpcID.NZONE_HORROR_DAGGANOTH_WATER_HARD,
            NpcID.NZONE_HORROR_DAGGANOTH_FIRE_HARD,
            NpcID.NZONE_HORROR_DAGGANOTH_EARTH_HARD,
            NpcID.NZONE_HORROR_DAGGANOTH_RANGED_HARD,
            NpcID.NZONE_HORROR_DAGGANOTH_MELEE_HARD,
            NpcID.NZONE_CHICKENQUEST_EVIL_CHICKEN_HARD,
            NpcID.NZONE_HUNDRED_CULINAROMANCER_FINAL_HARD,
            NpcID.NZONE_HUNDRED_MINION1_HARD,
            NpcID.NZONE_HUNDRED_MINION2_HARD,
            NpcID.NZONE_HUNDRED_MINION3_HARD,
            NpcID.NZONE_HUNDRED_MINION4_HARD,
            NpcID.NZONE_HUNDRED_MINION5_AIR_HARD,
            NpcID.NZONE_HUNDRED_MINION5_MELEE_HARD,
            NpcID.NZONE_HUNDRED_MINION5_WATER_HARD,
            NpcID.NZONE_HUNDRED_MINION5_FIRE_HARD,
            NpcID.NZONE_HUNDRED_MINION5_RANGED_HARD,
            NpcID.NZONE_HUNDRED_MINION5_EARTH_HARD,
            NpcID.NZONE_NEZIKCHENED_HARD,
            NpcID.NZONE_TREE_SPIRIT_HARD,
            NpcID.NZONE_QUEST_LUNAR_MIRROR_OF_PLAYER_HARD,
            NpcID.NZONE_MM_DEMON_HARD,
            NpcID.NZONE_MDAUGHTER_BEARMAN_FIGHTER_HARD,
            NpcID.NZONE_MYARM_GIANT_ROC_HARD,
            NpcID.NZONE_SLAGILITH_HARD,
            NpcID.NZONE_ROVING_MOSSGIANT_HARD,
            NpcID.NZONE_SKELETON_HELLHOUND_HARD,
            NpcID.NZONE_AGRITH_NAAR_HARD,
            NpcID.NZONE_SUROK_KING_HARD,
            NpcID.NZONE_KHAZARD_WARLORD_HARD,
            NpcID.NZONE_TROLL_CHAMPION_HARD,
            NpcID.NZONE_TROLLROMANCE_ARRG_HARD,
            NpcID.NZONE_COUNT_DRAYNOR_HARD,
            NpcID.NZONE_SHAPESHIFTERGLOB_HARD,
            NpcID.NZONE_SHAPESHIFTERSPIDER_HARD,
            NpcID.NZONE_SHAPESHIFTERBEAR_HARD,
            NpcID.NZONE_SHAPESHIFTERWOLF_HARD,
            NpcID.NZONE_ZQ_MAINZOMBIE1_HARD,
            NpcID.NZONE_ZQ_MAINZOMBIE2_HARD,
            NpcID.NZONE_ZQ_MAINZOMBIE3_HARD,
            NpcID.NZONE_COW_HARD,
    };

    /** Every boss id that can spawn in the instance, either difficulty. Use this for the "attack nearest" query. */
    public static int[] allBossIds() {
        int[] combined = new int[BOSSES_NORMAL.length + BOSSES_HARD.length];
        System.arraycopy(BOSSES_NORMAL, 0, combined, 0, BOSSES_NORMAL.length);
        System.arraycopy(BOSSES_HARD, 0, combined, BOSSES_NORMAL.length, BOSSES_HARD.length);
        return combined;
    }
}
