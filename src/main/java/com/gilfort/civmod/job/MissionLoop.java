package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * Work loop of the Mission Job Category (vault {@code Systems/Mission Jobs.md}). A settler whose Mission Job gets its
 * turn asks its board for a mission ({@link MissionPicks}); a reserved mission is taken off the offer and the settler
 * leaves at the start of the workday: it walks out of the settlement (like a foraging settler) and vanishes at the
 * border or after {@value #LEAVE_WALK_TICKS} ticks, is away (invisible, no AI, no hunger) until the mission's return
 * time and reappears where it vanished in the evening, with loot in the job's buffer and EXP – also during catch-up.
 * A settler that dies on the mission does not reappear: it is removed with everything it carried.
 */
final class MissionLoop implements CategoryLoop {

    /** Settlers leave only before this day time (10:00): departure is at the start of work. */
    static final long DEPARTURE_END = 4000L;
    /** Players within this distance hear about a return. */
    private static final double RETURN_RANGE = 32.0;
    /** Ticks a departing settler walks out before it vanishes. */
    static final int LEAVE_WALK_TICKS = 600;

    private final SettlerWork shell;
    private final Settler settler;
    private @Nullable MissionTrip trip;
    /** Where a departing settler walks to (not saved: recomputed after loading). */
    private @Nullable BlockPos leaveTarget;
    private long leaveStart;
    /**
     * The return time of the last mission, {@code -1} if none (not saved: a world loaded between the return and the
     * settler's own end of work lets it work for the rest of that window).
     */
    private long returnedAt = -1L;
    /** Outcome used instead of the roll (tests only, not saved). */
    private MissionRules.@Nullable Outcome forcedOutcome;

    /**
     * @param shell the Settler Shell driving this loop
     */
    MissionLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    /**
     * @return whether the settler is away on a mission (vanished outside the settlement)
     */
    boolean isAway() {
        return trip != null && trip.gone();
    }

    /**
     * @return whether the settler has left for a mission (walking out or away)
     */
    boolean departed() {
        return trip != null && trip.departed();
    }

    /**
     * @return whether the settler holds a reserved mission or is away on one
     */
    boolean hasTrip() {
        return trip != null;
    }

    /**
     * @param day the day
     * @return the day time the settler came back from a mission on that day; empty if it did not
     */
    OptionalLong returnedOn(long day) {
        return returnedAt >= 0 && DayClock.day(returnedAt) == day ? OptionalLong.of(DayClock.dayTime(returnedAt)) : OptionalLong.empty();
    }

    /**
     * @return the reserved or current mission
     */
    Optional<MissionTrip> trip() {
        return Optional.ofNullable(trip);
    }

    /**
     * @param outcome the outcome of every following mission instead of the roll
     */
    void forceOutcome(MissionRules.Outcome outcome) {
        forcedOutcome = outcome;
    }

    // ---------------------------------------------------------------- persistence

    @Override
    public void restore(SettlerWork.BuffersSnapshot snapshot) {
        trip = snapshot.trip().orElse(null);
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public boolean plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        BlockPos board = job.jobBlock();
        if (departed()) {
            return leave(level, job);
        }
        if (!shell.buffer(board).isEmpty()) {
            return shell.startDelivery(level, job);
        }
        if (trip != null && !trip.board().equals(board)) {
            forget(trip.board());
        }
        if (trip == null) {
            MissionPicks.request(level, board, settler);
            return false;
        }
        shell.idleWith(job);
        if (dayTime < DEPARTURE_END) {
            return depart(level, job, day);
        }
        shell.setStatus(board, JobStatus.WAITING_DEPARTURE);
        return true;
    }

    /**
     * A mission was picked for this settler: it is off the offer and reserved until the settler leaves.
     *
     * @param board    the Mission Board
     * @param mission  the mission
     * @param assigned whether the player assigned the mission to this settler
     */
    void reserve(BlockPos board, Identifier mission, boolean assigned) {
        trip = new MissionTrip(board, mission, -1L, Optional.empty(), assigned);
        shell.setStatus(board, JobStatus.WAITING_DEPARTURE);
    }

    /**
     * No mission fits: the Mission Job waits a while and the settler works its next priority. Loot still to deliver
     * keeps the job going (blocking it would stop the delivery).
     *
     * @param level the level
     * @param board the Mission Board
     */
    void noMission(ServerLevel level, BlockPos board) {
        if (shell.buffer(board).isEmpty()) {
            shell.block(level, board, JobStatus.NO_MISSION);
        }
    }

    private boolean depart(ServerLevel level, SettlerJob job, long day) {
        MissionTrip reserved = trip;
        Optional<Mission> mission = reserved == null ? Optional.empty() : Missions.get(level.registryAccess(), reserved.mission());
        if (reserved == null || mission.isEmpty()) {
            // The mission was removed from the datapacks since it was reserved.
            trip = null;
            shell.setStatus(job.jobBlock(), JobStatus.IDLE);
            return false;
        }
        trip = reserved.departing(MissionRules.returnTime(day, mission.get().days()));
        leaveTarget = null;
        return leave(level, job);
    }

    /** Walks out of the settlement (the shell's anti-stuck teleport keeps it going). */
    private boolean leave(ServerLevel level, SettlerJob job) {
        if (leaveTarget == null) {
            leaveTarget = shell.outsideTarget(level);
            leaveStart = level.getGameTime();
        }
        shell.setActiveJob(job);
        if (!shell.walkingTo(leaveTarget)) {
            shell.moveToJob(level, leaveTarget);
        }
        shell.setStatus(job.jobBlock(), JobStatus.ON_MISSION);
        return true;
    }

    /**
     * Goes on walking out after the walk was interrupted (e.g. the settler was saved while leaving).
     *
     * @param level the level
     */
    void resumeLeaving(ServerLevel level) {
        MissionTrip current = trip;
        if (current == null) {
            return;
        }
        Optional<SettlerJob> job = missionJob(settler, current.board());
        if (job.isEmpty()) {
            forget(current.board());
            return;
        }
        leave(level, job.get());
    }

    /** Vanishes outside the settlement: from now on the settler is away. */
    private void vanish(SettlerJob job) {
        MissionTrip current = trip;
        if (current == null) {
            return;
        }
        trip = current.withExit(settler.blockPosition());
        leaveTarget = null;
        settler.setInvisible(true);
        shell.idleWith(job);
    }

    @Override
    public void outranked(ServerLevel level, SettlerJob job, long day, long dayTime) {
        // A higher priority has work at the start of the day: the reserved mission goes back to the board (still
        // assigned to this settler if the player assigned it).
        if (trip != null && !trip.departed() && dayTime < DEPARTURE_END) {
            MissionOffers.giveBack(SettlementData.get(level), trip.board(), trip.mission(),
                    trip.assigned() ? Optional.of(settler.getUUID()) : Optional.empty(), trip.assigned());
            trip = null;
            shell.setStatus(job.jobBlock(), JobStatus.IDLE);
        }
    }

    /**
     * Ends the mission once its return time has passed: the settler reappears where it vanished, rolls the outcome,
     * puts the loot into the job's buffer (catch-up: straight into the drop-off) and gains EXP; it then walks back to
     * deliver. A settler whose Mission Job is its top priority right away asks for its next mission.
     *
     * @param level   the level
     * @param clock   the world time
     * @param catchUp whether this runs during catch-up (quiet, direct delivery)
     * @return whether the settler returned
     */
    boolean returnIfDue(ServerLevel level, long clock, boolean catchUp) {
        MissionTrip ended = trip;
        if (ended == null || !ended.departed() || clock < ended.returnTime()) {
            return false;
        }
        trip = null;
        leaveTarget = null;
        returnedAt = ended.returnTime();
        settler.setInvisible(false);
        BlockPos board = ended.board();
        Optional<SettlerJob> job = missionJob(settler, board);
        Optional<JobBlockSite> site = JobBlockSite.at(level, board);
        Optional<Mission> mission = Missions.get(level.registryAccess(), ended.mission());
        if (job.isEmpty() || site.isEmpty()) {
            return true;
        }
        shell.setStatus(board, JobStatus.IDLE);
        if (mission.isPresent() && finish(level, job.get(), site.get(), mission.get(), catchUp) == MissionRules.Outcome.DEATH) {
            die(level, board, mission.get());
            return true;
        }
        if (settler.getJobs().get(0).equals(job.get())) {
            MissionPicks.request(level, board, settler);
        }
        return true;
    }

    /** Rolls the outcome; a survivor gets its loot, EXP and a return message. */
    private MissionRules.Outcome finish(ServerLevel level, SettlerJob job, JobBlockSite site, Mission mission, boolean catchUp) {
        OutcomeRules rules = OutcomeRules.get();
        int cap = site.skillCap();
        int skill = settler.getSkills().effectiveLevel(job.jobType(), cap);
        MissionRules.Outcome outcome = forcedOutcome != null ? forcedOutcome
                : MissionRules.roll(MissionRules.chances(rules, mission.recommendedLevel(), skill, job.jobType()), level.getRandom());
        if (outcome == MissionRules.Outcome.DEATH) {
            return outcome;
        }
        if (outcome == MissionRules.Outcome.SUCCESS) {
            List<ItemStack> loot = loot(level, mission, MissionRules.rolls(mission, skill));
            if (catchUp) {
                shell.deliverDirect(level, site, loot);
            } else {
                JobBuffer buffer = shell.workplace(site.pos()).buffer();
                loot.forEach(buffer::add);
            }
        }
        shell.announce(level, Component.translatable("civmod.settler.mission.returned." + outcome.name().toLowerCase(Locale.ROOT),
                settler.getName(), mission.title()), RETURN_RANGE);
        shell.gainExp(level, job, MissionRules.exp(outcome, rules, job.jobType().baseExp(), site.level(), mission.recommendedLevel()), cap);
        return outcome;
    }

    /**
     * The settler did not return: players in its settlement or near it hear about it (also during catch-up, a death is
     * news), then it is removed for good with everything it carried.
     */
    private void die(ServerLevel level, BlockPos board, Mission mission) {
        Component message = Component.translatable("civmod.settler.mission.died", settler.getName(), mission.title());
        Optional<Settlement> settlement = JobBlockSite.at(level, board).map(JobBlockSite::settlement);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(settler) <= RETURN_RANGE * RETURN_RANGE
                    || settlement.filter(s -> JobAssignments.insideBorder(s, player.blockPosition())).isPresent()) {
                player.sendSystemMessage(message);
            }
        }
        settler.perish(level);
    }

    /** Rolls the loot: each roll uses one of the mission's loot tables at random. */
    private List<ItemStack> loot(ServerLevel level, Mission mission, int rolls) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, settler.position())
                .withOptionalParameter(LootContextParams.THIS_ENTITY, settler)
                .create(LootContextParamSets.CHEST);
        List<ItemStack> loot = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            ResourceKey<LootTable> table = mission.lootTables().get(level.getRandom().nextInt(mission.lootTables().size()));
            loot.addAll(level.getServer().reloadableRegistries().getLootTable(table).getRandomItems(params));
        }
        return loot;
    }

    /**
     * @param settler a settler
     * @param board   a Mission Board
     * @return the settler's Mission Job at that board
     */
    static Optional<SettlerJob> missionJob(Settler settler, BlockPos board) {
        return settler.getJobs().stream()
                .filter(job -> job.jobBlock().equals(board) && job.jobType().category() == JobCategory.MISSION).findFirst();
    }

    /**
     * @param level the level
     * @return the mission status for the GUI: away on or waiting for a mission, empty without one
     */
    Optional<Component> status(ServerLevel level) {
        if (trip == null) {
            return Optional.empty();
        }
        Component title = Missions.get(level.registryAccess(), trip.mission()).map(Mission::title)
                .orElseGet(() -> Component.literal(trip.mission().toString()));
        return Optional.of(trip.departed() ? Component.translatable("civmod.settler.status.on_mission", title)
                : Component.translatable("civmod.settler.status.mission_reserved", title));
    }

    /**
     * Walking out holds every decision (also the evening) until the settler vanishes.
     */
    @Override
    public boolean holdsDecision(ServerLevel level, SettlerJob job) {
        if (!departed() || isAway()) {
            return false;
        }
        if (leaveTarget == null || level.getGameTime() - leaveStart >= LEAVE_WALK_TICKS) {
            vanish(job);
        }
        return true;
    }

    @Override
    public void tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        if (departed() && !isAway()) {
            vanish(job);
        }
    }

    @Override
    public double reach(SettlerJob job) {
        return SettlerWork.REACH;
    }

    // ---------------------------------------------------------------- catch-up

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        if (trip == null || !trip.departed() || trip.returnTime() > shell.catchUpTo()) {
            return true;
        }
        // The return is caught up: the loot is delivered to the drop-off.
        return level.isLoaded(site.pos()) && level.isLoaded(site.dropOff());
    }

    /**
     * Missions are not started during catch-up; the time goes to the next job.
     */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        return new SimulatedWork(0, workTicks);
    }

    @Override
    public void forget(BlockPos jobBlock) {
        MissionTrip current = trip;
        if (current == null || !current.board().equals(jobBlock)) {
            return;
        }
        trip = null;
        leaveTarget = null;
        if (current.departed()) {
            settler.setInvisible(false);
        } else if (settler.level() instanceof ServerLevel level) {
            // The settler left the board: an assigned mission stays locked for the others.
            MissionOffers.giveBack(SettlementData.get(level), jobBlock, current.mission(), Optional.empty(), current.assigned());
        }
    }
}
