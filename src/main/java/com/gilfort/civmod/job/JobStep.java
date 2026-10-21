package com.gilfort.civmod.job;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

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
 * {@link Done}, {@link Wait}.
 */
sealed interface JobStep {

    /** Deliver the job's buffer to its drop-off (a Deliveryman: unload into its store). */
    JobStep DELIVER = new Deliver();
    /** At the target: worked this tick (counts for hunger), the step goes on. */
    JobStep WORK = new AtTarget(true);
    /** At the target: did not work this tick (a break, waiting for a Clerk), the step goes on. */
    JobStep STAY = new AtTarget(false);
    /** The step is over: the shell decides again. */
    JobStep DONE = new Done(false);
    /** Worked this tick and the step is over: the shell decides again. */
    JobStep WORKED_AND_DONE = new Done(true);

    /**
     * @return the job does not work now and leaves its status as it is; the next priority works
     */
    static JobStep skip() {
        return new Skip(Optional.empty());
    }

    /**
     * @param status the job's status
     * @return the job does not work now; the next priority works
     */
    static JobStep skip(JobStatus status) {
        return new Skip(Optional.of(status));
    }

    /**
     * @param status why the job is blocked
     * @return the job is blocked for a while; the next priority works
     */
    static JobStep blocked(JobStatus status) {
        return new Blocked(status, null);
    }

    /**
     * @param reason the profile's reason
     * @return the job is blocked for a while with status {@link JobStatus#BLOCKED}; the next priority works
     */
    static JobStep blocked(BlockingReason reason) {
        return new Blocked(JobStatus.BLOCKED, reason);
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
     * @param reason the profile's reason, null for a status of CivMod's own
     */
    record Blocked(JobStatus status, @Nullable BlockingReason reason) implements JobStep {
    }

    /**
     * The job does not work now; the next priority works. As an answer to {@link CategoryLoop#holdsDecision}: the
     * decision goes on as usual.
     *
     * @param status the job's new status, empty to leave it
     */
    record Skip(Optional<JobStatus> status) implements JobStep {
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
     */
    record AtTarget(boolean worked) implements JobStep {
    }

    /**
     * The step is over: the shell decides again after the loop returned.
     *
     * @param worked whether the settler worked this tick (counts for hunger)
     */
    record Done(boolean worked) implements JobStep {
    }

    /**
     * Temporary while the loops move to job steps: the loop already steered the shell itself; nothing is applied.
     *
     * @param busy whether the job keeps the settler busy (or holds the decision)
     */
    record Steered(boolean busy) implements JobStep {
    }
}
