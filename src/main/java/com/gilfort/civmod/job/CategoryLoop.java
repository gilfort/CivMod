package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The work loop of one {@link JobCategory}, driven by the Settler Shell ({@link SettlerWork}; vault
 * {@code Systems/Job Categories.md}). The shell runs schedule, walking, food, sleep, job priorities, status, EXP and
 * the catch-up driver, picks the active Job by priority and hands control to the loop of that Job's category.
 * <p>
 * One instance per settler, shared by all of its jobs of that category; a loop keeps its own state and uses the shell's
 * shared parts (walking, buffers and delivery, statuses, EXP) through the {@link SettlerWork} it was created for.
 */
interface CategoryLoop {

    /**
     * Plans the next step of a job; called at every decision for the jobs in priority order until one can work.
     *
     * @param level   the level
     * @param job     the job
     * @param site    the job's JobBlock site
     * @param day     the day
     * @param dayTime the time of day
     * @return whether the job can work (the settler is busy with it)
     */
    boolean plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime);

    /**
     * Called at a decision for a job of this loop that was not planned because a higher priority job works.
     *
     * @param level   the level
     * @param job     the job
     * @param day     the day
     * @param dayTime the time of day
     */
    default void outranked(ServerLevel level, SettlerJob job, long day, long dayTime) {
    }

    /**
     * Called at every decision while the settler is in a step of this loop, before anything else is decided.
     *
     * @param level the level
     * @param job   the active job
     * @return true to keep the current step and skip the decision (e.g. a work break)
     */
    boolean holdsDecision(ServerLevel level, SettlerJob job);

    /**
     * Called every tick while the settler has reached the target of a step of this loop.
     *
     * @param level  the level
     * @param job    the active job
     * @param target the target of the step
     */
    void tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target);

    /**
     * @param job the active job
     * @return the reach for the target of the current step
     */
    double reach(SettlerJob job);

    /**
     * Whether everything a job needs for catch-up is loaded and prepared (starts preparing it otherwise).
     *
     * @param level   the level
     * @param job     the job
     * @param site    the job's JobBlock site (already loaded together with its drop-off)
     * @param day     the day
     * @param dayTime the time of day
     * @return whether the job is ready
     */
    boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime);

    /** Resets per-run state before a catch-up run. */
    void startCatchUp();

    /**
     * Simulates work of a job during catch-up. A job that cannot go on is ended with {@link SettlerWork#endCatchUpJob}.
     *
     * @param level     the level
     * @param job       the job
     * @param day       the day
     * @param workTicks the work ticks available
     * @return the rolls made and the work ticks left over for the next job
     */
    SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks);

    /**
     * Forgets everything about a removed job.
     *
     * @param jobBlock the JobBlock position
     */
    void forget(BlockPos jobBlock);

    /**
     * @return the work places of the day this loop saves with the settler
     */
    default List<SettlerWork.WorkPlaceEntry> workPlaces() {
        return List.of();
    }

    /**
     * @return the scans this loop saves with the settler
     */
    default List<SavedScan> savedScans() {
        return List.of();
    }

    /**
     * Restores the saved state of this loop.
     *
     * @param snapshot the saved work state of the settler
     */
    default void restore(SettlerWork.BuffersSnapshot snapshot) {
    }

    /**
     * @param job a job
     * @return the found resources of that job for the GUI
     */
    default List<JobProfile.Found> found(SettlerJob job) {
        return List.of();
    }

    /**
     * Result of simulated work.
     *
     * @param rolls     production rolls made (counted against the catch-up budget)
     * @param remaining work ticks left over for the next job
     */
    record SimulatedWork(int rolls, double remaining) {
    }
}
