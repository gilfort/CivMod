package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * Where the biome of a Job's biome bonuses is read (vault {@code Systems/JobBlock.md}, decisions on biome bonuses): a
 * {@link CategoryProfile} names it with {@link CategoryProfile#biomePosition()}, by default the one of its
 * {@link JobCategory} ({@link #of}).
 */
@ApiStatus.Experimental
public enum BiomePosition {

    /**
     * The work place of the day: a Resource Collection Job's work place, a Producing Job's station of the day. Read
     * once per day when the work place is chosen (again when it is chosen anew during the day).
     */
    WORK_PLACE,
    /** The JobBlock's position (Logistics). Read once per day. */
    JOB_BLOCK,
    /** No biome bonus (Mission Jobs, who work away from the settlement). */
    NONE;

    /**
     * @param category a Job Category
     * @return its biome position: Resource Collection and Producing the work place of the day, Mission none, every
     *         other category the JobBlock
     */
    public static BiomePosition of(JobCategory category) {
        return switch (category) {
            case RESOURCE_COLLECTION, PRODUCING -> WORK_PLACE;
            case MISSION -> NONE;
            case LOGISTICS, GUARD, SERVICE -> JOB_BLOCK;
        };
    }
}
