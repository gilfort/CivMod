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
 * A loop answers the shell with {@link JobStep}s (walk to a target, deliver, wait, blocked, skip, pause / hold, work,
 * done): it never changes the settler's activity, walking target or active job itself, and a job's status changes
 * through a step or, outside one (catch-up, a status between steps), through the job's {@link Workplace} only. The
 * shell applies each step after the loop returned ({@link SettlerWork#apply}).
 * <p>
 * One instance per settler, shared by all of its jobs of that category; per-job state (buffer, status, work place of
 * the day, scan) lives in the job's {@link Workplace}; a loop keeps its own loop state and uses the shell's shared
 * services (Workplace and buffer, effective level, EXP, catch-up delivery, counters, the hand, announcements) through
 * the {@link SettlerWork} it was created for.
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
     * Whether the settler, standing at the target of its current step, works the block there in a way players must
     * not disturb (vault {@code Systems/Jobs.md}: a Producing Job crafting at its station). Players cannot open the
     * block's GUI then.
     *
     * @param job the job
     * @return whether the step's target is in use; by default it is not (trees, stone, water have no player lock)
     */
    default boolean usesTarget(SettlerJob job) {
        return false;
    }

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
