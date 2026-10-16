package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Mission Job Category (vault {@code Systems/Mission Jobs.md}). A settler whose Mission Job gets its
 * turn asks its board for a mission ({@link MissionPicks}); a reserved mission is taken off the offer and the settler
 * leaves at the start of the workday, is away (invisible, no AI, no hunger) until the mission's return time and comes
 * back in the evening with loot in the job's buffer and EXP – also during catch-up.
 * <p>
 * Death on a mission is not implemented yet: its chance counts as failure.
 */
final class MissionLoop implements CategoryLoop {

    /** Settlers leave only before this day time (10:00): departure is at the start of work. */
    static final long DEPARTURE_END = 4000L;
    /** Players within this distance hear about a return. */
    private static final double RETURN_RANGE = 32.0;

    private final SettlerWork shell;
    private final Settler settler;
    private @Nullable MissionTrip trip;

    /**
     * @param shell the Settler Shell driving this loop
     */
    MissionLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    /**
     * @return whether the settler is away on a mission
     */
    boolean isAway() {
        return trip != null && trip.away();
    }

    /**
     * @return whether the settler holds a reserved mission or is away on one
     */
    boolean hasTrip() {
        return trip != null;
    }

    /**
     * @return the reserved or current mission
     */
    Optional<MissionTrip> trip() {
        return Optional.ofNullable(trip);
    }

    // ---------------------------------------------------------------- persistence

    @Override
    public void restore(SettlerWork.BuffersSnapshot snapshot) {
        trip = snapshot.trip().orElse(null);
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public boolean plan(ServerLevel level, SettlerJob job, SettlementBlock block, long day, long dayTime) {
        BlockPos board = job.jobBlock();
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
     * @param board   the Mission Board
     * @param mission the mission
     */
    void reserve(BlockPos board, Identifier mission) {
        trip = new MissionTrip(board, mission, -1L);
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
        trip = new MissionTrip(reserved.board(), reserved.mission(), MissionRules.returnTime(day, mission.get().days()));
        settler.setInvisible(true);
        shell.setStatus(job.jobBlock(), JobStatus.ON_MISSION);
        return true;
    }

    @Override
    public void outranked(ServerLevel level, SettlerJob job, long day, long dayTime) {
        // A higher priority has work at the start of the day: the reserved mission goes back to the board.
        if (trip != null && !trip.away() && dayTime < DEPARTURE_END) {
            MissionOffers.giveBack(SettlementData.get(level), trip.board(), trip.mission());
            trip = null;
            shell.setStatus(job.jobBlock(), JobStatus.IDLE);
        }
    }

    /**
     * Ends the mission once its return time has passed: the settler is back at the board, rolls the outcome, puts the
     * loot into the job's buffer (catch-up: straight into the drop-off) and gains EXP. A settler whose Mission Job is its
     * top priority right away asks for its next mission.
     *
     * @param level   the level
     * @param clock   the world time
     * @param catchUp whether this runs during catch-up (quiet, direct delivery)
     * @return whether the settler returned
     */
    boolean returnIfDue(ServerLevel level, long clock, boolean catchUp) {
        MissionTrip ended = trip;
        if (ended == null || !ended.away() || clock < ended.returnTime()) {
            return false;
        }
        trip = null;
        settler.setInvisible(false);
        BlockPos board = ended.board();
        if (level.isLoaded(board)) {
            Vec3 spot = SettlerWork.standingSpot(level, board);
            settler.teleportTo(spot.x, spot.y, spot.z);
        }
        Optional<SettlerJob> job = missionJob(settler, board);
        Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(board).flatMap(s -> s.block(board));
        Optional<Mission> mission = Missions.get(level.registryAccess(), ended.mission());
        if (job.isEmpty() || block.isEmpty()) {
            return true;
        }
        shell.setStatus(board, JobStatus.IDLE);
        if (mission.isPresent()) {
            finish(level, job.get(), block.get(), mission.get(), catchUp);
        }
        if (settler.getJobs().get(0).equals(job.get())) {
            MissionPicks.request(level, board, settler);
        }
        return true;
    }

    private void finish(ServerLevel level, SettlerJob job, SettlementBlock block, Mission mission, boolean catchUp) {
        OutcomeRules rules = OutcomeRules.get();
        int cap = SettlementRules.skillCap(block.level());
        int skill = settler.getSkills().effectiveLevel(job.jobType(), cap);
        MissionRules.Outcome outcome = MissionRules.roll(MissionRules.chances(rules, mission.recommendedLevel(), skill), level.getRandom());
        if (outcome == MissionRules.Outcome.DEATH) {
            // Death on a mission comes with its own story; until then it counts as failure.
            outcome = MissionRules.Outcome.FAILED;
        }
        if (outcome == MissionRules.Outcome.SUCCESS) {
            List<ItemStack> loot = loot(level, mission, MissionRules.rolls(mission, skill));
            if (catchUp) {
                shell.deliverDirect(level, block, loot);
            } else {
                JobBuffer buffer = shell.jobBuffer(block.pos());
                loot.forEach(buffer::add);
            }
        }
        shell.announce(level, Component.translatable("civmod.settler.mission.returned." + outcome.name().toLowerCase(Locale.ROOT),
                settler.getName(), mission.title()), RETURN_RANGE);
        shell.gainExp(level, job, MissionRules.exp(outcome, rules, job.jobType().baseExp(), block.level(), mission.recommendedLevel()), cap);
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
        return Optional.of(trip.away() ? Component.translatable("civmod.settler.status.on_mission", title)
                : Component.translatable("civmod.settler.status.mission_reserved", title));
    }

    @Override
    public boolean holdsDecision(ServerLevel level, SettlerJob job) {
        return false;
    }

    @Override
    public void tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        // Missions have no steps in the settlement.
    }

    @Override
    public double reach(SettlerJob job) {
        return SettlerWork.REACH;
    }

    // ---------------------------------------------------------------- catch-up

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, SettlementBlock block, long day, long dayTime) {
        if (trip == null || !trip.away() || trip.returnTime() > shell.catchUpTo()) {
            return true;
        }
        // The return is caught up: the loot is delivered to the drop-off.
        return level.isLoaded(block.pos()) && level.isLoaded(block.dropOff().orElse(block.pos()));
    }

    @Override
    public void startCatchUp() {
        // Nothing per run: the mission end is a world time.
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
        if (current.away()) {
            settler.setInvisible(false);
        } else if (settler.level() instanceof ServerLevel level) {
            MissionOffers.giveBack(SettlementData.get(level), jobBlock, current.mission());
        }
    }
}
