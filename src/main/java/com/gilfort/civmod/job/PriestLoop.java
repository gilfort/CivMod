package com.gilfort.civmod.job;

import java.util.Optional;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerSpawner;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Bonuses;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.Shrine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The Priest's work loop (vault {@code Jobs/Priest.md}): the Priest only works while the Center Block glows
 * ({@link Shrine#glowing}); otherwise the shell moves on to its next job. A glowing shrine sends it to the block, where it
 * prays for {@value Shrine#PRAYER_TICKS} ticks and rolls for a new Settler ({@link #roll}); a success keeps the shrine
 * glowing for another hour and the Priest prays again, a failure ends the day's glow.
 * <p>
 * Every roll starts the settlement's work speed blessing ({@link Bonuses}) for three hours from its clock value.
 * <p>
 * Catch-up does not walk: {@link #catchUpDay} simulates the prayer chain of a missed day.
 */
public final class PriestLoop implements CategoryLoop {

    /** Prayers of one chain simulated at most in catch-up (a safety net; the chance is at most 15 %). */
    private static final int MAX_CHAIN = 64;
    private static final int PARTICLE_INTERVAL = 20;
    private static final long NOT_PRAYING = -1L;

    private final SettlerWork shell;
    /** Game time at which the current prayer began, {@link #NOT_PRAYING} if none. */
    private long prayingSince = NOT_PRAYING;

    PriestLoop(SettlerWork shell) {
        this.shell = shell;
    }

    @Override
    public JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        if (!glowing(level, site.settlement())) {
            return JobStep.skip();
        }
        return JobStep.walk(site.block().pos(), Shrine.PRAYER_REACH, JobStatus.PRAYING);
    }

    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        return prayingSince == NOT_PRAYING ? JobStep.skip() : new JobStep.Hold(JobStatus.PRAYING);
    }

    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        shell.settler().getLookControl().setLookAt(Vec3.atCenterOf(target));
        long now = level.getGameTime();
        if (prayingSince == NOT_PRAYING) {
            // A prayer begun while the shrine glows counts, whenever it ends.
            prayingSince = now;
        }
        if (now - prayingSince < Shrine.PRAYER_TICKS) {
            if (now % PARTICLE_INTERVAL == 0) {
                level.sendParticles(ParticleTypes.ENCHANT, target.getX() + 0.5, target.getY() + 1.2, target.getZ() + 0.5, 6, 0.4, 0.3, 0.4, 0.5);
            }
            return JobStep.STAY;
        }
        prayingSince = NOT_PRAYING;
        long clock = level.getOverworldClockTime();
        JobBlockSite.at(level, job.jobBlock()).ifPresent(site -> roll(level, job, site, DayClock.day(clock), clock, level.getRandom()));
        return new JobStep.Done(false, false);
    }

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        return true;
    }

    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        // The Priest prays once a day in catchUpDay and does not use the work time.
        return new SimulatedWork(0, workTicks);
    }

    @Override
    public void forget(BlockPos jobBlock) {
        prayingSince = NOT_PRAYING;
    }

    private static boolean glowing(ServerLevel level, Settlement settlement) {
        long clock = level.getOverworldClockTime();
        return Shrine.glowing(settlement.id(), DayClock.day(clock), DayClock.dayTime(clock), clock, settlement.shrine());
    }

    /**
     * One roll of a prayer: gives the Priest its EXP, rolls for a Settler (which appears if the settlement has a free bed
     * and room) and updates the shrine: a success glows for another hour, a failure ends the day's glow.
     *
     * @param level  the level
     * @param job    the Priest's job
     * @param site   the Center Block's site
     * @param day    the day of the prayer
     * @param clock  the clock value of the prayer
     * @param random the random source of the roll
     * @return whether the roll succeeded
     */
    private boolean roll(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long clock, RandomSource random) {
        Settlement settlement = site.settlement();
        int priestLevel = shell.effectiveLevel(job);
        shell.gainExp(level, job, job.jobType().baseExp() * site.level(), SettlementRules.skillCap(site.level()));
        boolean success = Shrine.roll(random, priestLevel);
        SettlementData data = SettlementData.get(level);
        if (success) {
            SettlerSpawner.recruit(level, data, settlement, site.block().pos());
            data.setShrine(settlement, new Shrine.State(settlement.shrine().endedDay(), clock + Shrine.GLOW_TICKS));
        } else {
            data.setShrine(settlement, new Shrine.State(day, 0L));
        }
        // Every prayer blesses the settlement; the last roll of the chain sets the end (a start never shortens it).
        data.setBonuses(settlement, settlement.bonuses().start(Bonuses.Kind.WORK_SPEED, clock, clock + Bonuses.BLESSING_TICKS));
        return success;
    }

    /**
     * Simulates the prayer chain of one missed day (vault {@code Jobs/Priest.md}, catch-up): rolls, chain, beds and EXP as
     * live, without walking; Settlers appear at the Center Block when the settlement loads. A day whose glow a prayer
     * already ended, or that is not over yet, is skipped.
     *
     * @param level   the level
     * @param settler a settler catching up
     * @param day     the missed day
     * @param until   the clock value up to which the settler catches up
     * @param random  the random source of the rolls
     */
    public static void catchUpDay(ServerLevel level, Settler settler, long day, long until, RandomSource random) {
        Optional<SettlerJob> priest = settler.getJobs().stream().filter(job -> job.jobType() == ModJobs.PRIEST.get()).findFirst();
        Optional<JobBlockSite> site = priest.flatMap(job -> JobBlockSite.at(level, job.jobBlock())).filter(found -> found.block().connected());
        if (site.isEmpty() || site.get().settlement().shrine().endedDay() == day) {
            return;
        }
        int settlement = site.get().settlement().id();
        long clock = day * DayClock.DAY_TICKS + Shrine.glowStart(settlement, day);
        if (clock + Shrine.GLOW_TICKS > until) {
            return;
        }
        PriestLoop loop = settler.getWork().priests();
        for (int i = 0; i < MAX_CHAIN; i++) {
            if (!loop.roll(level, priest.get(), site.get(), day, clock, random)) {
                return;
            }
            clock += Shrine.GLOW_TICKS;
        }
        SettlementData data = SettlementData.get(level);
        data.setShrine(site.get().settlement(), new Shrine.State(day, 0L));
    }
}
