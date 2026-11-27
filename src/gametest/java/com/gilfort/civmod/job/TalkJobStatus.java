package com.gilfort.civmod.job;

import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.BlockPos;

/**
 * Sets the status of a settler's job for the tests of other packages, which cannot reach the package-private setter. The class
 * exists only in the GameTest source set.
 */
public final class TalkJobStatus {

    private TalkJobStatus() {
    }

    /**
     * @param settler  the settler
     * @param jobBlock the JobBlock of one of its jobs
     * @param status   the status the job has from now on
     */
    public static void set(Settler settler, BlockPos jobBlock, JobStatus status) {
        settler.getWork().setStatus(jobBlock, status);
    }
}
