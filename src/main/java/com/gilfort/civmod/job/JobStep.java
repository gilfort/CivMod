package com.gilfort.civmod.job;

import java.util.Optional;

import com.gilfort.civmod.api.BlockingReason;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * What a job wants next, as a {@link CategoryLoop} answers the Settler Shell (vault {@code Systems/Architecture.md}).
 * The loop only says what it wants; the shell alone changes the settler's activity, walking target, active job and the
 * job's status ({@link SettlerWork#apply}). A step is applied after the loop returned, so nothing the loop does after
 * deciding on a step runs in the middle of a new decision.
 * <p>
 * Answers of {@link CategoryLoop#plan}: {@link Walk}, {@link #DELIVER}, {@link Wait}, {@link Blocked}, {@link Skip}.
 * Answers of {@link CategoryLoop#holdsDecision}: {@link Pause}, {@link Hold}, {@link Wait} (all hold the decision) or
 * {@link Skip} (decide as usual). Answers of {@link CategoryLoop#tickAtTarget}: {@link #WORK}, {@link #STAY},
 * {@link #STAND}, {@link Done}, {@link Wait}. At the target the settler's walk goes on unless the step stands.
 */
sealed interface JobStep {

    /** Deliver the job's buffer to its drop-off (a Deliveryman: unload into its store). */
    JobStep DELIVER = new Deliver();
    /** At the target: worked this tick (counts for hunger) standing still, the step goes on. */
    JobStep WORK = new AtTarget(true, true);
    /** At the target: did not work this tick (a break, waiting for a Clerk), the walk goes on, so does the step. */
    JobStep STAY = new AtTarget(false, false);
    /** At the target: did not work this tick but stands still (a Clerk's break at its counter), the step goes on. */
    JobStep STAND = new AtTarget(false, true);
    /** The step is over: the shell decides again. */
    JobStep DONE = new Done(false, false);
    /** The step is over after a task done standing still (a Deliveryman took its load): the shell decides again. */
    JobStep DONE_STANDING = new Done(false, true);
    /** Worked this tick standing still and the step is over: the shell decides again. */
    JobStep WORKED_AND_DONE = new Done(true, true);

    /**
     * @return the job does not work now and leaves its status as it is; the next priority works
     */
    static JobStep skip() {
        return new Skip(Optional.empty(), Optional.empty());
    }

    /**
     * @param status the job's status
     * @return the job does not work now; the next priority works
     */
    static JobStep skip(JobStatus status) {
        return new Skip(Optional.of(status), Optional.empty());
    }

    /**
     * @param status  the job's status
     * @param subject what the status is about, if it names something
     * @return the job does not work now; the next priority works
     */
    static JobStep skip(JobStatus status, Optional<Component> subject) {
        return new Skip(Optional.of(status), subject);
    }

    /**
     * @param status why the job is blocked
     * @return the job is blocked for a while; the next priority works
     */
    static JobStep blocked(JobStatus status) {
        return new Blocked(status, Optional.empty());
    }

    /**
     * @param reason the profile's reason
     * @return the job is blocked for a while with status {@link JobStatus#BLOCKED}; the next priority works
     */
    static JobStep blocked(BlockingReason reason) {
        return new Blocked(JobStatus.BLOCKED, Optional.of(reason));
    }

    /**
     * @param target the target
     * @param reach  how close the settler has to come
     * @param status the job's status
     * @return walk to the target (or go on walking there) and work at it
     */
    static JobStep walk(BlockPos target, double reach, JobStatus status) {
        return new Walk(target.immutable(), reach, status, Optional.empty());
    }

    /**
     * @param target  the target
     * @param reach   how close the settler has to come
     * @param status  the job's status
     * @param subject what the status is about
     * @return walk to the target (or go on walking there) and work at it
     */
    static JobStep walk(BlockPos target, double reach, JobStatus status, Component subject) {
        return new Walk(target.immutable(), reach, status, Optional.of(subject));
    }

    /**
     * Walk to a target, or go on walking there, and work at it ({@link CategoryLoop#tickAtTarget} once reached).
     *
     * @param target  the target
     * @param reach   how close the settler has to come
     * @param status  the job's status
     * @param subject what the status is about
     */
    record Walk(BlockPos target, double reach, JobStatus status, Optional<Component> subject) implements JobStep {
    }

    /** Deliver the job's buffer through the shell's delivery. */
    record Deliver() implements JobStep {
    }

    /**
     * Busy with the job without a target: the settler stands where it is (waiting for a departure, away on a
     * mission).
     *
     * @param status the job's status
     */
    record Wait(JobStatus status) implements JobStep {
    }

    /**
     * The job cannot work for a while ({@link Workplace#BLOCKED_RETRY} ticks); the next priority works.
     *
     * @param status the status explaining why ({@link JobStatus#BLOCKED} with a reason)
     * @param reason the profile's reason, empty for a status of CivMod's own
     */
    record Blocked(JobStatus status, Optional<BlockingReason> reason) implements JobStep {
    }

    /**
     * The job does not work now; the next priority works. As an answer to {@link CategoryLoop#holdsDecision}: the
     * decision goes on as usual.
     *
     * @param status  the job's new status, empty to leave it
     * @param subject what the new status is about, if it names something
     */
    record Skip(Optional<JobStatus> status, Optional<Component> subject) implements JobStep {
    }

    /**
     * Holds the decision and stands still (a work break).
     *
     * @param status the job's status
     */
    record Pause(JobStatus status) implements JobStep {
    }

    /**
     * Holds the decision and keeps the current step as it is (walking goes on).
     *
     * @param status the job's status
     */
    record Hold(JobStatus status) implements JobStep {
    }

    /**
     * At the target, the step goes on.
     *
     * @param worked whether the settler worked this tick (counts for hunger)
     * @param stands whether the settler stands still (its walk stops), else the walk goes on
     */
    record AtTarget(boolean worked, boolean stands) implements JobStep {
    }

    /**
     * The step is over: the shell decides again after the loop returned.
     *
     * @param worked whether the settler worked this tick (counts for hunger)
     * @param stands whether the settler's walk stops before the shell decides again
     */
    record Done(boolean worked, boolean stands) implements JobStep {
    }
}
