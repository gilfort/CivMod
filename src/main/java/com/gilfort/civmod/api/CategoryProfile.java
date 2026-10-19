package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * The specifics a {@link Job} fills into the work loop of its {@link JobCategory} (vault
 * {@code Systems/Job Categories.md}). Each category with a public loop has its own profile interface ({@link MissionProfile}
 * so far); a Job's profile must belong to the Job's category.
 * <p>
 * Profiles are interfaces with {@code default} methods, so new hooks do not break existing implementations.
 */
@ApiStatus.Experimental
public interface CategoryProfile {

    /**
     * @return the Job Category whose loop this profile fills in
     */
    JobCategory category();
}
