package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.item.ScrollOfAssignmentItem;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.GuardPosts;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.WorkPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Guard (vault {@code Jobs/Guard.md}): it holds a post around the clock. The Guard stands on the guard
 * point of the half day (picked at 06:00 and at 18:30, see {@link GuardPosts}) facing the way the player set it, or next to
 * its Guard Post without points. It never sleeps ({@link GuardDuty#dayTimes}); fatigue breaks sit it down on the spot. It
 * claims a bed of its own, which counts for the bed need, and never goes to it. This step has no combat.
 * <p>
 * Duty EXP is the Job's base EXP times the level of the Guard Post for every in-game hour: live from the clock at each
 * decision, in catch-up for the whole missed time, nights included.
 */
final class GuardLoop implements CategoryLoop {

    /** How close the Guard stands to the centre of its guard point. */
    private static final double POINT_REACH = 0.6;
    /** From this many blocks away the Guard walks the last steps onto its point straight, without a path. */
    private static final double APPROACH_BLOCKS = 2.0;
    /** The speed factor of that last walk, as for every walk of a settler. */
    private static final double APPROACH_SPEED = 0.6;
    /** A path search from the Guard is only conclusive within this many blocks (the follow range of a navigation). */
    private static final double PATH_CHECK_BLOCKS = 16.0;
    /** Blocks beyond the reach of its target at which the Guard still counts as standing at its post. */
    private static final double AT_POST_MARGIN_BLOCKS = 0.5;
    /** How often a Guard without a bed of its own looks for one (1 minute). */
    private static final long BED_CHECK_TICKS = 1200L;
    /** Time without a decision after which duty is not paid for the gap (the Guard was away or unloaded). */
    private static final long MAX_UNPAID_GAP_TICKS = 2 * DayClock.HOUR_TICKS;
    private static final long NOT_PAID = Long.MIN_VALUE;

    /** What the loop keeps for one Guard job, forgotten together ({@link #forget}). */
    private static final class Duty {

        /** Today's guard point, null: next to the Guard Post. */
        private @Nullable WorkPoint point;
        /** Where the Guard walks to now. */
        private @Nullable BlockPos target;
        /** How close the Guard has to come to the target. */
        private double reach = SettlerWork.REACH;
        /** The clock value up to which live duty is paid. */
        private long paidUntil = NOT_PAID;
        /** The clock value up to which duty is paid in the running catch-up. */
        private long catchUpPaidUntil = NOT_PAID;
    }

    private final SettlerWork shell;
    private final Settler settler;
    private final Fatigue fatigue = new Fatigue();
    private final Map<BlockPos, Duty> duties = new HashMap<>();
    private long nextBedCheck;
    /** The clock value at which the running catch-up started (the window's progress moves on while it runs). */
    private long catchUpStart = NOT_PAID;

    /**
     * @param shell the Settler Shell driving this loop
     */
    GuardLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    @Override
    public JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        claimBed(level);
        Duty duty = duties.computeIfAbsent(job.jobBlock(), pos -> new Duty());
        payLiveDuty(level, job, duty);
        Optional<WorkPoint> point = pointToday(level, site, day, dayTime);
        duty.point = point.orElse(null);
        duty.target = point.map(found -> found.pos().above()).orElse(site.pos());
        duty.reach = point.isPresent() ? POINT_REACH : SettlerWork.REACH;
        if (point.isPresent()) {
            approach(duty.target);
        }
        return JobStep.walk(duty.target, duty.reach, fatigue.onBreak() ? JobStatus.PAUSING : JobStatus.GUARDING);
    }

    /** A break holds the Guard where it is; it starts one only while it stands at its post. */
    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        Duty duty = duties.get(job.jobBlock());
        if (duty != null) {
            payLiveDuty(level, job, duty);
        }
        if (fatigue.onBreak()) {
            boolean still = fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom());
            return still ? new JobStep.Pause(JobStatus.PAUSING) : JobStep.skip(JobStatus.GUARDING);
        }
        if (duty != null && atPost(duty) && fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom())) {
            return new JobStep.Pause(JobStatus.PAUSING);
        }
        return JobStep.skip();
    }

    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        Duty duty = duties.get(job.jobBlock());
        if (duty != null && duty.point != null) {
            Vec3 eye = settler.getEyePosition();
            settler.getLookControl().setLookAt(eye.add(Vec3.atLowerCornerOf(duty.point.facing().getUnitVec3i())));
            settler.setYBodyRot(duty.point.facing().toYRot());
        }
        return fatigue.onBreak() ? JobStep.STAND : JobStep.WORK;
    }

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        return true;
    }

    /** Pays the duty from the last paid time up to the end of the simulated hour; the night between two hours is part of it. */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks, SimulatedSpan span) {
        Duty duty = duties.computeIfAbsent(job.jobBlock(), pos -> new Duty());
        long from = duty.catchUpPaidUntil == NOT_PAID ? catchUpStart : duty.catchUpPaidUntil;
        pay(level, job, span.workTo() - from);
        duty.catchUpPaidUntil = Math.max(from, span.workTo());
        return new SimulatedWork(0, 0.0);
    }

    @Override
    public void startCatchUp() {
        catchUpStart = shell.catchUpFrom();
        duties.values().forEach(duty -> duty.catchUpPaidUntil = NOT_PAID);
    }

    /** Pays the missed time after the last simulated hour (the night at the end of the window, or all of a short window). */
    @Override
    public void catchUpFinished(ServerLevel level) {
        for (SettlerJob job : settler.getJobs()) {
            if (loopOf(job) && JobBlockSite.blockAt(level, job.jobBlock()).filter(block -> block.connected()).isPresent()) {
                Duty duty = duties.computeIfAbsent(job.jobBlock(), pos -> new Duty());
                long from = duty.catchUpPaidUntil == NOT_PAID ? catchUpStart : duty.catchUpPaidUntil;
                pay(level, job, shell.catchUpTo() - from);
                duty.catchUpPaidUntil = NOT_PAID;
                // Live duty counts from the end of the missed time.
                duty.paidUntil = NOT_PAID;
            }
        }
    }

    private boolean loopOf(SettlerJob job) {
        return shell.loop(job) == this;
    }

    @Override
    public void forget(BlockPos jobBlock) {
        duties.remove(jobBlock);
    }

    // ---------------------------------------------------------------- guard point

    /** Today's guard point; one that cannot be used is recorded as out of reach for the day, and the next one is picked. */
    private Optional<WorkPoint> pointToday(ServerLevel level, JobBlockSite site, long day, long dayTime) {
        SettlementData data = SettlementData.get(level);
        for (int tries = 0; tries <= GuardPosts.MAX_POINTS; tries++) {
            Optional<WorkPoint> point = GuardPosts.pointOf(data, site.pos(), settler.getUUID(), day, dayTime, level.getRandom());
            if (point.isEmpty() || usable(level, data, site.pos(), point.get().pos(), day)) {
                return point;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the Guard can stand at the point. A block that is no place to stand is out of reach at once; a path search
     * runs once a day per point, from within {@value #PATH_CHECK_BLOCKS} blocks (further away it says nothing).
     */
    private boolean usable(ServerLevel level, SettlementData data, BlockPos post, BlockPos point, long day) {
        if (!ScrollOfAssignmentItem.isStandable(level, point)) {
            GuardPosts.recordReach(data, post, point, day, true);
            return false;
        }
        if (GuardPosts.reachChecked(data, post, point, day)
                || settler.position().distanceTo(Vec3.atBottomCenterOf(point)) > PATH_CHECK_BLOCKS) {
            return true;
        }
        // Vanilla searches no path while the Guard is in the air: that says nothing, the next decision asks again.
        Path path = settler.getNavigation().createPath(point.above(), 0);
        if (path == null) {
            return true;
        }
        GuardPosts.recordReach(data, post, point, day, !path.canReach());
        return path.canReach();
    }

    /**
     * A walk of the navigation ends up to a block short of its goal. The last steps onto the point are walked straight, so the
     * Guard stands on it and, being at its target, faces the way the point was set.
     */
    private void approach(BlockPos target) {
        Vec3 center = Vec3.atBottomCenterOf(target);
        double gap = Math.hypot(settler.getX() - center.x, settler.getZ() - center.z);
        if (gap > POINT_REACH && gap <= APPROACH_BLOCKS && Math.abs(settler.getY() - center.y) < 1.0) {
            settler.getMoveControl().setWantedPosition(center.x, center.y, center.z, APPROACH_SPEED);
        }
    }

    private boolean atPost(Duty duty) {
        double within = duty.reach + AT_POST_MARGIN_BLOCKS;
        return duty.target != null && settler.distanceToSqr(Vec3.atBottomCenterOf(duty.target)) <= within * within;
    }

    // ---------------------------------------------------------------- bed

    /** A Guard needs a bed of its own for the bed need, though it never lies in it. */
    private void claimBed(ServerLevel level) {
        if (level.getGameTime() < nextBedCheck) {
            return;
        }
        nextBedCheck = level.getGameTime() + BED_CHECK_TICKS;
        SettlementData data = SettlementData.get(level);
        data.settlement(settler.getSettlementId())
                .ifPresent(settlement -> SettlerBeds.ensureBed(level, data, settlement, settler.getUUID(), settler.blockPosition()));
    }

    // ---------------------------------------------------------------- duty EXP

    /** Pays the whole hours on duty since the last payment; a long gap is not paid. */
    private void payLiveDuty(ServerLevel level, SettlerJob job, Duty duty) {
        if (shell.quiet()) {
            return;
        }
        long clock = level.getOverworldClockTime();
        if (duty.paidUntil == NOT_PAID || clock < duty.paidUntil || clock - duty.paidUntil > MAX_UNPAID_GAP_TICKS) {
            duty.paidUntil = clock;
            return;
        }
        long hours = (clock - duty.paidUntil) / DayClock.HOUR_TICKS;
        if (hours > 0) {
            duty.paidUntil += hours * DayClock.HOUR_TICKS;
            pay(level, job, hours * DayClock.HOUR_TICKS);
        }
    }

    private void pay(ServerLevel level, SettlerJob job, long ticks) {
        int jobBlockLevel = shell.jobBlockLevel(job);
        int exp = GuardDuty.dutyExp(ticks, job.jobType().baseExp(), jobBlockLevel);
        if (exp > 0) {
            shell.gainExp(level, job, exp, SettlementRules.skillCap(jobBlockLevel));
        }
    }
}
