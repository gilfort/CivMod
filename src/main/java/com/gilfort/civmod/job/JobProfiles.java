package com.gilfort.civmod.job;

import java.util.Optional;

import com.gilfort.civmod.settlement.JobType;

/**
 * The profiles of the implemented jobs.
 */
public final class JobProfiles {

    private static final JobProfile LUMBERJACK = new LumberjackProfile();
    private static final JobProfile MINER = new MinerProfile();

    private JobProfiles() {
    }

    /**
     * @param jobType a job type
     * @return its profile, empty while the job is not implemented yet
     */
    public static Optional<JobProfile> of(JobType jobType) {
        return switch (jobType) {
            case LUMBERJACK -> Optional.of(LUMBERJACK);
            case MINER -> Optional.of(MINER);
            case FISHERMAN -> Optional.empty();
        };
    }
}
