package com.gilfort.civmod.job;

import java.util.ArrayList;
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
import com.gilfort.civmod.entertainment.Stage;
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
 * A stationary act sends the settler to the Job's place, next to its JobBlock (a Bard stands on its Stage block), for the
 * whole window. When the settler has reached its place, the act plays once a day: one scan for listeners (awake settlers of
 * the settlement and players within the act's radius of its center: the JobBlock, or a Stage's Sound center), the effects in
 * order, and the settler's EXP (the Job's base EXP times the JobBlock level).
 * <p>
 * A round act (the Bard's day round) walks to settlers at work: it picks one ({@link #planRound}), walks there, and if a settler
 * is within the act's radius of the performer when it arrives, it plays a stop of {@value ServiceRound#STOP_TICKS} ticks, with
 * one scan for the listeners at its start. A stop that finds nobody is not used up; the Job's {@link JobBlockSite#level()} is
 * the number of stops a day ({@link ServiceRound}). When no settler works, or the stops are used up, the performer waits on its
 * place or works its next job. Outside every window the loop skips, so the shell moves on to the settler's next job.
 * <p>
 * A Bard shows its performance ({@link BardShow}, the instrument in its hand while its status is {@link JobStatus#PLAYING}).
 * Catch-up plays no act; the hours of a stationary window are spent standing.
 */
final class ServiceLoop implements CategoryLoop {

    /** How close the Bard comes to the middle of its Stage block, in blocks. */
    private static final double STAGE_REACH = 0.6;
    /** How close a round performer comes to the settler it heads for, in blocks. */
    private static final double TARGET_REACH = 3.0;
    /** How close the performer stays to where a stop began, in blocks. */
    private static final double SPOT_REACH = 1.5;

    private final SettlerWork shell;
    private final Settler settler;
    /** What a job did today, by JobBlock: one entry per job, cleared by {@link #forget}. */
    private final Map<BlockPos, Today> days = new HashMap<>();

    /**
     * @param day   the day the acts were played on
     * @param acts  the ids of the stationary acts played that day
     * @param round the state of the day's round
     */
    private record Today(long day, Set<String> acts, ServiceRound round) {
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
        List<ServiceAct> acts = acts(job);
        Optional<ServiceAct> stationary = ServiceActs.openAt(acts, ServiceAct.Kind.STATIONARY, dayTime);
        if (stationary.isPresent()) {
            Today today = today(job, day);
            // A round in progress ends with its window.
            today.round().reset();
            boolean playing = plays(job) && today.acts().contains(stationary.get().id());
            return place(level, site, playing ? JobStatus.PLAYING : JobStatus.SERVING);
        }
        if (ServiceActs.openAt(acts, ServiceAct.Kind.ROUND, dayTime).isPresent()) {
            return planRound(level, job, site, today(job, day).round());
        }
        return JobStep.skip(JobStatus.IDLE);
    }

    /** The Job's place of the day: a Bard stands on top of its Stage (if there is room), any other settler next to its JobBlock. */
    private static JobStep place(ServerLevel level, JobBlockSite site, JobStatus status) {
        BlockPos top = site.pos().above();
        if (Stage.isStage(site.block()) && StandingSpots.canStand(level, top)) {
            return JobStep.walk(top, STAGE_REACH, status);
        }
        return JobStep.walk(site.pos(), SettlerWork.REACH, status);
    }

    private JobStep planRound(ServerLevel level, SettlerJob job, JobBlockSite site, ServiceRound round) {
        long now = level.getGameTime();
        if (round.playing(now)) {
            return JobStep.walk(round.spot().orElseThrow(), SPOT_REACH, statusOfStop(job));
        }
        if (round.spot().isPresent()) {
            round.reset();
        }
        if (!round.due(site.level())) {
            return JobStep.skip(JobStatus.IDLE);
        }
        if (round.waiting(now)) {
            return place(level, site, JobStatus.SERVING);
        }
        Optional<Settler> target = target(level, site, round);
        if (target.isEmpty()) {
            round.noOneWorks(now);
            return place(level, site, JobStatus.SERVING);
        }
        round.head(target.get().getUUID());
        return JobStep.walk(target.get().blockPosition(), TARGET_REACH, JobStatus.SERVING);
    }

    /** The settler the round heads for: the one it already heads for while that one still works, else a random one at work. */
    private Optional<Settler> target(ServerLevel level, JobBlockSite site, ServiceRound round) {
        Optional<Settler> current = round.target().map(level::getEntity).filter(Settler.class::isInstance).map(Settler.class::cast).filter(this::works);
        if (current.isPresent()) {
            return current;
        }
        List<Settler> working = new ArrayList<>();
        for (UUID member : site.settlement().members()) {
            if (level.getEntity(member) instanceof Settler other && works(other)) {
                working.add(other);
            }
        }
        return working.isEmpty() ? Optional.empty() : Optional.of(working.get(level.getRandom().nextInt(working.size())));
    }

    /** A settler at work that the round can visit: not the performer itself, not another Bard, awake and not away. */
    private boolean works(Settler other) {
        return other != settler && !Stage.playsBard(other) && other.getWork().atWork() && !other.isAway() && !other.isSleeping();
    }

    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        Today today = days.get(job.jobBlock());
        long clock = level.getOverworldClockTime();
        if (today != null && today.day() == DayClock.day(clock) && today.round().playing(level.getGameTime())
                && ServiceActs.openAt(acts(job), ServiceAct.Kind.ROUND, DayClock.dayTime(clock)).isPresent()) {
            return new JobStep.Hold(statusOfStop(job));
        }
        return JobStep.skip();
    }

    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        long clock = level.getOverworldClockTime();
        long dayTime = DayClock.dayTime(clock);
        List<ServiceAct> acts = acts(job);
        Optional<ServiceAct> stationary = ServiceActs.openAt(acts, ServiceAct.Kind.STATIONARY, dayTime);
        Optional<ServiceAct> round = ServiceActs.openAt(acts, ServiceAct.Kind.ROUND, dayTime);
        if (stationary.isEmpty() && round.isEmpty()) {
            Today today = days.get(job.jobBlock());
            if (today != null) {
                today.round().reset();
            }
            return new JobStep.Done(false, false);
        }
        Today today = today(job, DayClock.day(clock));
        return stationary.isPresent() ? tickStationary(level, job, target, stationary.get(), today, clock)
                : tickRound(level, job, round.get(), today.round(), clock);
    }

    private JobStep tickStationary(ServerLevel level, SettlerJob job, BlockPos target, ServiceAct act, Today today, long clock) {
        if (today.round().busy()) {
            // The window of the round closed while the performer was away from its place: decide again.
            today.round().reset();
            return JobStep.DONE;
        }
        settler.getLookControl().setLookAt(Vec3.atCenterOf(target));
        if (today.acts().add(act.id())) {
            playStationary(level, job, act, clock);
            // A Bard turns to "playing" at once: the instrument, the bubble.
            return plays(job) ? JobStep.WORKED_AND_DONE : JobStep.WORK;
        }
        if (plays(job)) {
            BardShow.show(level, settler, level.getGameTime());
        }
        return JobStep.WORK;
    }

    private JobStep tickRound(ServerLevel level, SettlerJob job, ServiceAct act, ServiceRound round, long clock) {
        long now = level.getGameTime();
        if (round.playing(now)) {
            if (plays(job)) {
                BardShow.show(level, settler, now);
            }
            return JobStep.WORK;
        }
        if (round.spot().isPresent()) {
            // The stop is over: the next one, or the place.
            round.reset();
            return JobStep.WORKED_AND_DONE;
        }
        if (round.target().isEmpty()) {
            // On its place, waiting.
            return JobStep.WORK;
        }
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (site.isEmpty()) {
            return JobStep.DONE;
        }
        // Arrived at the settler it headed for: one scan for the listeners around the performer.
        Vec3 center = settler.position();
        List<Mob> listeners = listeningSettlers(level, site.get(), act, center);
        if (listeners.isEmpty()) {
            round.arrivedAlone(now);
            return JobStep.DONE;
        }
        perform(level, job, site.get(), act, center, listeners, clock);
        round.start(now, settler.blockPosition());
        return JobStep.WORKED_AND_DONE;
    }

    private static boolean plays(SettlerJob job) {
        return Stage.isBard(job.jobType());
    }

    private static JobStatus statusOfStop(SettlerJob job) {
        return plays(job) ? JobStatus.PLAYING : JobStatus.SERVING;
    }

    private Today today(SettlerJob job, long day) {
        Today today = days.get(job.jobBlock());
        if (today == null || today.day() != day) {
            today = new Today(day, new HashSet<>(), new ServiceRound());
            days.put(job.jobBlock(), today);
        }
        return today;
    }

    private static List<ServiceAct> acts(SettlerJob job) {
        Job type = job.jobType();
        return type.profile(ServiceProfile.class).map(profile -> ServiceActs.of(type, profile)).orElse(List.of());
    }

    /** Plays a stationary act: one scan for the listeners around its center, then the effects and the EXP. */
    private void playStationary(ServerLevel level, SettlerJob job, ServiceAct act, long clock) {
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (site.isEmpty()) {
            return;
        }
        Vec3 center = Stage.isStage(site.get().block()) ? Stage.soundCenter(site.get().block()) : Vec3.atBottomCenterOf(site.get().pos());
        perform(level, job, site.get(), act, center, listeningSettlers(level, site.get(), act, center), clock);
    }

    /** The effects of an act in order for the listeners found, then the settler's EXP. */
    private void perform(ServerLevel level, SettlerJob job, JobBlockSite site, ServiceAct act, Vec3 center, List<Mob> settlers, long clock) {
        List<ServerPlayer> players = act.effects().stream().anyMatch(effect -> effect.targets().players()) ? listeningPlayers(level, act, center) : List.of();
        int skill = shell.effectiveLevel(job);
        for (ServiceEffect effect : act.effects()) {
            ServiceEffect.Targets targets = effect.targets();
            effect.apply(new ServiceContext(level, act, center, settler, skill, clock, targets.settlers() ? settlers : List.of(),
                    targets.players() ? players : List.of()));
        }
        // The EXP of one act: the Job's base EXP times the JobBlock level.
        shell.gainExp(level, job, job.jobType().baseExp() * site.level(), SettlementRules.skillCap(site.level()));
    }

    private List<Mob> listeningSettlers(ServerLevel level, JobBlockSite site, ServiceAct act, Vec3 center) {
        Set<UUID> members = site.settlement().members();
        AABB area = AABB.ofSize(center, 2.0 * act.radius(), 2.0 * act.radius(), 2.0 * act.radius());
        return List.copyOf(level.getEntitiesOfClass(Settler.class, area,
                other -> other != settler && members.contains(other.getUUID()) && !other.isAway() && !other.isSleeping()
                        && act.reaches(center, other.position())));
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
        // Standing through a window uses the work time; the acts and the round are not played in catch-up.
        return new SimulatedWork(0, ServiceActs.openAt(acts(job), ServiceAct.Kind.STATIONARY, shell.catchUpDayTime()).isPresent() ? 0.0 : workTicks);
    }

    @Override
    public void forget(BlockPos jobBlock) {
        days.remove(jobBlock);
    }
}
