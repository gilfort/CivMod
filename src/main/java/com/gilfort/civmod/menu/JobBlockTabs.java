package com.gilfort.civmod.menu;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.registry.ModJobs;

/**
 * The tab set of a JobBlock window, chosen by the Job Category of the JobBlock's Job (vault
 * {@code Systems/Job Categories.md}), so an addon Job gets the same window as a built-in one. Every set starts with the
 * Storage and Info tabs. Only the Clerk keeps tabs keyed to its Job.
 */
public enum JobBlockTabs {

    /** Storage and Info only (categories without a tab of their own). */
    STANDARD(2, false),
    /** Resource Collection: plus the Found tab ({@link FoundTab}). */
    FOUND(3, false),
    /** Producing: plus the Patterns tab ({@link PatternTab}), in a tall panel with the player inventory. */
    PATTERNS(3, true),
    /** Mission: plus the Missions tab of the Mission Board. */
    MISSIONS(3, false),
    /** Clerk: plus the Stock, Requests and Deliveries tabs ({@link ClerkTab}), in a tall panel. */
    CLERK(5, true);

    private final int count;
    private final boolean tall;

    JobBlockTabs(int count, boolean tall) {
        this.count = count;
        this.tall = tall;
    }

    /**
     * @param jobType the Job of a JobBlock
     * @return the tab set of its window
     */
    public static JobBlockTabs of(Job jobType) {
        if (jobType == ModJobs.CLERK.get()) {
            return CLERK;
        }
        return switch (jobType.category()) {
            case RESOURCE_COLLECTION -> FOUND;
            case PRODUCING -> PATTERNS;
            case MISSION -> MISSIONS;
            case GUARD, LOGISTICS, SERVICE -> STANDARD;
        };
    }

    /**
     * @return the number of tabs
     */
    public int count() {
        return count;
    }

    /**
     * @return whether the window uses the tall panel
     */
    public boolean tall() {
        return tall;
    }
}
