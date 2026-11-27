package com.gilfort.civmod.job;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Sets the status of a settler's job and sends it delivering, for the tests of other packages, which cannot reach the package-private
 * methods. The class exists only in the GameTest source set.
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

    /**
     * Sends the settler to the drop-off of one of its jobs: it works that job now, in the activity of a delivery.
     *
     * @param level    the level
     * @param settler  the settler
     * @param jobBlock the JobBlock of one of its jobs
     */
    public static void startDelivery(ServerLevel level, Settler settler, BlockPos jobBlock) {
        SettlerJob job = settler.getWork().jobs().stream().filter(candidate -> candidate.jobBlock().equals(jobBlock)).findFirst().orElseThrow();
        settler.getWork().apply(level, job, JobStep.DELIVER);
    }
}
