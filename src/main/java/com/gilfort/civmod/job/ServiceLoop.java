package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ServiceAct;
import com.gilfort.civmod.api.ServiceContext;
import com.gilfort.civmod.api.ServiceEffect;
import com.gilfort.civmod.api.ServiceProfile;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The work loop of the Service category (vault {@code Systems/Job Categories.md}): it plays the {@link ServiceAct}s of every
 * Job with a {@link ServiceProfile}. The Priest, the Tavern Host and the Administrator keep their own handling
 * ({@link SettlerWork#loop}).
 * <p>
 * A stationary act sends the settler to the Job's place, next to its JobBlock, for the whole window; outside every window
 * the loop skips, so the shell moves on to the settler's next job. When the settler has reached its place, the act plays
 * once a day: one scan for listeners (settlers of the settlement and players within the act's radius), the effects in
 * order, and the settler's EXP (the Job's base EXP times the JobBlock level). A round act is run by the Bard's round and
 * does nothing here.
 * <p>
 * Catch-up plays no act; the hours of a stationary window are spent standing.
 */
final class ServiceLoop implements CategoryLoop {

    private final SettlerWork shell;
    private final Settler settler;
    /** The acts played today, by JobBlock: one entry per job, cleared by {@link #forget}. */
    private final Map<BlockPos, Played> played = new HashMap<>();

    /**
     * @param day  the day the acts were played on
     * @param acts the ids of the acts played that day
     */
    private record Played(long day, Set<String> acts) {
    }

    /**
     * @param shell the Settler Shell driving this loop
     */
    ServiceLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    @Override
    public JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        if (act(job, dayTime).isEmpty()) {
            return JobStep.skip();
        }
        return JobStep.walk(site.pos(), SettlerWork.REACH, JobStatus.SERVING);
    }

    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        return JobStep.skip();
    }

    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        long clock = level.getOverworldClockTime();
        Optional<ServiceAct> act = act(job, DayClock.dayTime(clock));
        if (act.isEmpty()) {
            return new JobStep.Done(false, false);
        }
        settler.getLookControl().setLookAt(Vec3.atCenterOf(target));
        Played today = played.get(job.jobBlock());
        long day = DayClock.day(clock);
        if (today == null || today.day() != day) {
            today = new Played(day, new HashSet<>());
            played.put(job.jobBlock(), today);
        }
        if (today.acts().add(act.get().id())) {
            play(level, job, act.get(), target, clock);
        }
        return JobStep.WORK;
    }

    private static Optional<ServiceAct> act(SettlerJob job, long dayTime) {
        Job type = job.jobType();
        return type.profile(ServiceProfile.class).flatMap(profile -> ServiceActs.stationaryAt(ServiceActs.of(type, profile), dayTime));
    }

    /** Plays an act: one scan for the listeners, then the effects in order and the EXP. */
    private void play(ServerLevel level, SettlerJob job, ServiceAct act, BlockPos place, long clock) {
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (site.isEmpty()) {
            return;
        }
        Vec3 center = Vec3.atBottomCenterOf(place);
        List<Mob> settlers = listeningSettlers(level, site.get(), act, center);
        List<ServerPlayer> players = listeningPlayers(level, act, center);
        int skill = shell.effectiveLevel(job);
        for (ServiceEffect effect : act.effects()) {
            ServiceEffect.Targets targets = effect.targets();
            effect.apply(new ServiceContext(level, act, center, settler, skill, clock, targets.settlers() ? settlers : List.of(),
                    targets.players() ? players : List.of()));
        }
        shell.gainExp(level, job, job.jobType().baseExp() * site.get().level(), SettlementRules.skillCap(site.get().level()));
    }

    private List<Mob> listeningSettlers(ServerLevel level, JobBlockSite site, ServiceAct act, Vec3 center) {
        Set<UUID> members = site.settlement().members();
        AABB area = AABB.ofSize(center, 2.0 * act.radius(), 2.0 * act.radius(), 2.0 * act.radius());
        return List.copyOf(level.getEntitiesOfClass(Settler.class, area,
                other -> other != settler && members.contains(other.getUUID()) && !other.isAway() && act.reaches(center, other.position())));
    }

    private static List<ServerPlayer> listeningPlayers(ServerLevel level, ServiceAct act, Vec3 center) {
        return level.getPlayers(player -> !player.isSpectator() && act.reaches(center, player.position()));
    }

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        return true;
    }

    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        // Standing through a window uses the work time; the acts themselves are not played in catch-up.
        return new SimulatedWork(0, act(job, shell.catchUpDayTime()).isPresent() ? 0.0 : workTicks);
    }

    @Override
    public void forget(BlockPos jobBlock) {
        played.remove(jobBlock);
    }
}
