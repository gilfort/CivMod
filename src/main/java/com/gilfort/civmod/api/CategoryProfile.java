package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * The specifics a {@link Job} fills into the work loop of its {@link JobCategory} (vault
 * {@code Systems/Job Categories.md}). Each category with a public loop has its own profile interface
 * ({@link ResourceCollectionProfile}, {@link ProducingProfile}, {@link MissionProfile}); a Job's profile must belong to
 * the Job's category.
 * <p>
 * Profiles are interfaces with {@code default} methods, so new hooks do not break existing implementations.
 */
@ApiStatus.Experimental
public interface CategoryProfile {

    /**
     * @return the Job Category whose loop this profile fills in
     */
    JobCategory category();

    /**
     * Where the biome of the Job's biome bonuses is read (vault {@code Systems/JobBlock.md}): by default the work place
     * of the day for Resource Collection and Producing Jobs, none for Mission Jobs ({@link BiomePosition#of}).
     *
     * @return the biome position
     */
    default BiomePosition biomePosition() {
        return BiomePosition.of(category());
    }
}
