package com.gilfort.civmod.settlement;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.job.JobCategory;

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
    /** Settlers a Mission Board holds per level (index 0 = level 1). */
    private static final int[] MISSION_BOARD_CAPACITY = {1, 2, 4, 6, 10};
    /** The most settlers a Mission Board holds (at the maximum level). */
    public static final int MAX_MISSION_BOARD_CAPACITY = MISSION_BOARD_CAPACITY[MISSION_BOARD_CAPACITY.length - 1];

    /** Deliveryman places on a Clerk JobBlock per level (index 0 = level 1). */
    private static final int[] DELIVERYMAN_PLACES = {1, 2, 4, 6, 8};

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
     * @param jobType the job a JobBlock offers
     * @param level   the JobBlock level
     * @return how many settlers the JobBlock holds in its own Job (with its Specializations): the Mission Board
     *         1 / 2 / 4 / 6 / 10 by level, every other one 1; further Jobs of the block have own places
     *         ({@link JobPlaces})
     */
    public static int capacity(Job jobType, int level) {
        return jobType.category() == JobCategory.MISSION ? MISSION_BOARD_CAPACITY[clampLevel(level) - MIN_LEVEL] : 1;
    }

    /**
     * @param level the Clerk JobBlock level
     * @return how many Deliverymen a Clerk JobBlock holds at that level: 1 / 2 / 4 / 6 / 8
     */
    public static int deliverymanPlaces(int level) {
        return DELIVERYMAN_PLACES[clampLevel(level) - MIN_LEVEL];
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
