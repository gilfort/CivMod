package com.gilfort.civmod.settlement;

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
     * Two JobBlocks are linked if their horizontal distance is within the join radius of the higher-level one,
     * so linking is symmetric.
     *
     * @param levelA level of the first block
     * @param levelB level of the second block
     * @return the maximum linking distance in blocks
     */
    public static int joinRadius(int levelA, int levelB) {
        return JOIN_RADIUS_PER_LEVEL * Math.max(levelA, levelB);
    }

    /**
     * @param level the JobBlock level
     * @return the join radius of a single JobBlock (used for the outline display)
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
