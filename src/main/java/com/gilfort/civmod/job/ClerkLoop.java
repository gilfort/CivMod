package com.gilfort.civmod.job;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.ClerkSpots;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Clerk, the first Logistics Job (vault {@code Jobs/Clerk.md}): during its shift (from its early
 * waking time, 05:30–06:00, to 18:30) the Clerk stands on the stand spot of the day (picked each morning and at 12:00), or next to its JobBlock without spots,
 * facing the way the player set it. It does not stroll; fatigue breaks start only while no customer is served.
 * Customers are served at its counter ({@link ClerkCounter}); the Clerk itself only holds its position.
 * <p>
 * Catch-up: the shift alone decides whether the linked storage is reachable; the Clerk's own time produces nothing,
 * its stock rules are checked once per simulated hour ({@link StockRules}).
 */
final class ClerkLoop implements CategoryLoop {

    /** How close the Clerk stands to the centre of its stand spot. */
    private static final double SPOT_REACH = 0.6;

    private final SettlerWork shell;
    private final Settler settler;
    private final Fatigue fatigue = new Fatigue();

    /** Today's stand spot of the active Clerk job (null: next to the JobBlock). */
    private SettlementBlock.@Nullable StandSpot spot;

    /**
     * @param shell the Settler Shell driving this loop
     */
    ClerkLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    @Override
    public JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        if (!ClerkStorage.inShift(settler.getUUID(), day, dayTime)) {
            return JobStep.skip();
        }
        Optional<SettlementBlock.StandSpot> today = ClerkSpots.today(SettlementData.get(level), site.pos(), day, dayTime, level.getRandom());
        spot = today.orElse(null);
        BlockPos target = today.map(found -> found.pos().above()).orElse(site.pos());
        return JobStep.walk(target, spot != null ? SPOT_REACH : SettlerWork.REACH, fatigue.onBreak() ? JobStatus.PAUSING : JobStatus.SERVING);
    }

    /** A break holds the Clerk where it is (walking on to its spot); breaks start only between customers. */
    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        if (fatigue.onBreak()) {
            boolean still = fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom());
            return still ? new JobStep.Hold(JobStatus.PAUSING) : JobStep.skip(JobStatus.SERVING);
        }
        if (!ClerkCounter.busy(level, job.jobBlock()) && fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom())) {
            return new JobStep.Hold(JobStatus.PAUSING);
        }
        return JobStep.skip();
    }

    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        if (spot != null) {
            Vec3 eye = settler.getEyePosition();
            settler.getLookControl().setLookAt(eye.add(Vec3.atLowerCornerOf(spot.facing().getUnitVec3i())));
            settler.setYBodyRot(spot.facing().toYRot());
        }
        return fatigue.onBreak() ? JobStep.STAND : JobStep.WORK;
    }

    /**
     * @return whether the Clerk is on a fatigue break (the counter starts no new service then)
     */
    boolean onBreak() {
        return fatigue.onBreak();
    }

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        return true;
    }

    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        // Standing at the counter uses the work time; hand-outs to customers give the EXP. Called once per simulated
        // hour of the shift: the stock rules are checked then.
        StockRules.check(level, job.jobBlock(), shell.catchUpDayTime());
        return new SimulatedWork(0, 0.0);
    }

    @Override
    public void forget(BlockPos jobBlock) {
        if (settler.level() instanceof ServerLevel level) {
            ClerkCounter.forget(level, jobBlock);
        }
        spot = null;
    }
}
