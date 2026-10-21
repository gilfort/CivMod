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
 * One instance per settler, shared by all of its jobs of that category; per-job state (buffer, status, work place of
 * the day, scan) lives in the job's {@link Workplace}; a loop keeps its own loop state and uses the shell's
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
     * @return what the job wants next ({@link JobStep}); the shell applies it
     */
    JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime);

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
     * Called for every job of this loop when the settler's work day is over (live after work, catch-up at the end of
     * the work hours).
     *
     * @param level the level
     * @param job   the job
     */
    default void dayEnded(ServerLevel level, SettlerJob job) {
    }

    /**
     * Called at every decision while the settler is in a step of this loop, before anything else is decided.
     *
     * @param level the level
     * @param job   the active job
     * @return a step that holds the decision ({@link JobStep.Pause}, {@link JobStep.Hold}, {@link JobStep.Wait}, e.g. a
     *         work break), or {@link JobStep.Skip} to decide as usual
     */
    JobStep holdsDecision(ServerLevel level, SettlerJob job);

    /**
     * Called every tick while the settler has reached the target of a step of this loop.
     *
     * @param level  the level
     * @param job    the active job
     * @param target the target of the step
     * @return {@link JobStep#WORK} / {@link JobStep#STAY} to go on, {@link JobStep.Done} when the step is over
     */
    JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target);

    /**
     * @param job the active job
     * @return the reach for the target of the current step; NaN (default): the reach of the last {@link JobStep.Walk}
     */
    default double reach(SettlerJob job) {
        return Double.NaN;
    }

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

    /** Resets per-run state before a catch-up run; nothing by default. */
    default void startCatchUp() {
    }

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
     * Forgets the loop's own state of a removed job (the shell drops the job's {@link Workplace}).
     *
     * @param jobBlock the JobBlock position
     */
    default void forget(BlockPos jobBlock) {
    }

    /**
     * Restores the saved state of this loop.
     *
     * @param snapshot the saved work state of the settler
     */
    default void restore(SettlerWork.BuffersSnapshot snapshot) {
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
