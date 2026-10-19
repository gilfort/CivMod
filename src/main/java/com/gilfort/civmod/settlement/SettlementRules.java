package com.gilfort.civmod.settlement;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.UpgradeCosts;

import net.minecraft.core.BlockPos;

/**
 * Numeric rules of settlements and JobBlocks (vault {@code Systems/Settlement.md}, {@code Systems/JobBlock.md}).
 */
public final class SettlementRules {

    /** Lowest JobBlock level. */
    public static final int MIN_LEVEL = 1;
    /** Highest JobBlock level. */
    public static final int MAX_LEVEL = 5;
    /** Join radius per JobBlock level in blocks. */
    public static final int JOIN_RADIUS_PER_LEVEL = 10;
    /** Exclusion radius per JobBlock level in blocks. */
    public static final int EXCLUSION_RADIUS_PER_LEVEL = 30;
    /** Maximum skill level per JobBlock level. */
    public static final int SKILL_CAP_PER_LEVEL = 20;
    /** The most settlers a JobBlock holds in one Job (with its Specializations), whatever its datapack file says. */
    public static final int MAX_SETTLERS = 10;

    /** Served Clerks a Warehouse links per level (index 0 = level 1). */
    private static final int[] SERVED_CLERKS = {2, 4, 6, 8, 12};

    /** Containers a Clerk JobBlock links as storage per level (index 0 = level 1). */
    private static final int[] CLERK_STORAGE = {2, 4, 8, 12, 16};
    /** Stand spots per Clerk JobBlock. */
    public static final int MAX_STAND_SPOTS = 8;

    private SettlementRules() {
    }

    /**
     * @param level a JobBlock level
     * @return the level clamped to the valid range
     */
    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /**
     * A new or waiting JobBlock connects if it lies within the join radius of an already connected JobBlock; its own
     * level only counts once it is connected (so the outline shown to players is exactly the border).
     *
     * @param level the level of the connected JobBlock
     * @return the join radius around that JobBlock
     */
    public static int joinRadius(int level) {
        return JOIN_RADIUS_PER_LEVEL * level;
    }

    /**
     * @param level the level of a settlement's JobBlock
     * @return the exclusion radius around that block
     */
    public static int exclusionRadius(int level) {
        return EXCLUSION_RADIUS_PER_LEVEL * level;
    }

    /**
     * @param level the JobBlock level
     * @return the maximum skill level an NPC can reach at this JobBlock
     */
    public static int skillCap(int level) {
        return SKILL_CAP_PER_LEVEL * level;
    }

    /**
     * @param baseJob a Base Job a JobBlock offers
     * @param level   the JobBlock level
     * @return how many settlers the JobBlock holds in that Base Job and its Specializations: the {@code settlers} list
     *         of {@code civmod_upgrades/<job>.json} ({@link UpgradeCosts#settlers}), 1 without one
     */
    public static int capacity(Job baseJob, int level) {
        return UpgradeCosts.settlers(baseJob, level);
    }

    /**
     * @param level the Warehouse level
     * @return how many Served Clerks a Warehouse uses at that level: 2 / 4 / 6 / 8 / 12
     */
    public static int servedClerks(int level) {
        return SERVED_CLERKS[clampLevel(level) - MIN_LEVEL];
    }

    /**
     * @param level the Clerk JobBlock level
     * @return how many linked containers a Clerk JobBlock uses at that level: 2 / 4 / 8 / 12 / 16
     */
    public static int clerkStorage(int level) {
        return CLERK_STORAGE[clampLevel(level) - MIN_LEVEL];
    }

    /**
     * Horizontal (cylinder) distance, ignoring the height difference.
     *
     * @param a first position
     * @param b second position
     * @return the horizontal distance in blocks
     */
    public static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
