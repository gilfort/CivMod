package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Automatic job assignment (vault {@code Systems/Scroll of Assignment.md}): every Job a JobBlock offers has a toggle
 * (default off). When it is switched on and every morning at 06:00 (catch-up too) jobless settlers of the settlement
 * take its free places: the highest skill in that Job first, ties nearest to the JobBlock, until no place or candidate
 * is left. Nothing else triggers it, so a place the player just freed stays free for the scroll until the next morning.
 * <p>
 * Candidates are loaded settlers without any JobBlock job (Laborer only); assignment goes through the job picker's
 * path ({@link SettlementWorld#assign}). One pass over the settlement's settlers per toggle or per morning.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AutoAssignment {

    /** Players within this distance of the JobBlock see "<Name> is now a <Job>". */
    private static final double NOTIFY_DISTANCE = 32.0;

    /** The settler day each dimension was last seen on (not saved: the first tick after a start only records it). */
    private static final Map<ResourceKey<Level>, Long> LAST_DAY = new HashMap<>();

    private AutoAssignment() {
    }

    /**
     * Switches automatic assignment of one Job of a JobBlock; switching it on fills that Job's free places at once.
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock position
     * @param job      an offered Job
     * @param on       the new state
     * @return whether the state changed (false for an unknown block or a Job the block does not offer)
     */
    public static boolean setAuto(ServerLevel level, BlockPos jobBlock, Job job, boolean on) {
        SettlementData data = SettlementData.get(level);
        Optional<JobBlockSite> site = JobBlockSite.at(data, jobBlock);
        if (site.isEmpty() || JobPlaces.line(site.get().block(), job).isEmpty() || !site.get().block().setAutoAssign(job, on)) {
            return false;
        }
        data.changed();
        if (on) {
            Settlement settlement = site.get().settlement();
            fill(level, settlement, candidates(level, settlement), (block, line) -> block.pos().equals(jobBlock) && line == job);
        }
        return true;
    }

    /**
     * The morning check of one settlement: every switched-on Job takes jobless settlers into its free places.
     *
     * @param level      the dimension
     * @param settlement the settlement
     */
    public static void morning(ServerLevel level, Settlement settlement) {
        fill(level, settlement, candidates(level, settlement), (block, job) -> true);
    }

    /**
     * The morning check during catch-up, at 06:00 of a simulated day: candidates are the catching-up settlers whose
     * missed time contains that morning (they missed the live check).
     *
     * @param level    the dimension
     * @param settlers the settlers of one settlement catching up together
     * @param day      the simulated day
     */
    public static void catchUpMorning(ServerLevel level, List<Settler> settlers, long day) {
        if (settlers.isEmpty()) {
            return;
        }
        long morning = day * DayClock.DAY_TICKS;
        int settlementId = settlers.get(0).getSettlementId();
        List<Settler> candidates = new ArrayList<>(settlers.stream()
                .filter(settler -> jobless(settler, settlementId) && settler.getWork().hasPendingCatchUp()
                        && settler.getWork().catchUpFrom() <= morning && morning < settler.getWork().catchUpTo())
                .toList());
        if (!candidates.isEmpty()) {
            SettlementData.get(level).settlement(settlementId)
                    .ifPresent(settlement -> fill(level, settlement, candidates, (block, job) -> true));
        }
    }

    private static List<Settler> candidates(ServerLevel level, Settlement settlement) {
        List<Settler> candidates = new ArrayList<>();
        for (java.util.UUID member : settlement.members()) {
            if (level.getEntity(member) instanceof Settler settler && jobless(settler, settlement.id())) {
                candidates.add(settler);
            }
        }
        return candidates;
    }

    private static boolean jobless(Settler settler, int settlementId) {
        return settler.isAlive() && !settler.isRemoved() && settler.getSettlementId() == settlementId && settler.getJobs().isEmpty();
    }

    /**
     * Fills the free places of the switched-on Jobs that {@code which} accepts, in block and picker order; an assigned
     * candidate leaves the list.
     */
    private static void fill(ServerLevel level, Settlement settlement, List<Settler> candidates, BiPredicate<SettlementBlock, Job> which) {
        for (SettlementBlock block : List.copyOf(settlement.blocks())) {
            if (!block.connected()) {
                continue;
            }
            for (JobPlaces.Line line : JobPlaces.lines(block)) {
                if (!line.auto() || !which.test(block, line.job())) {
                    continue;
                }
                Vec3 center = Vec3.atCenterOf(block.pos());
                Comparator<Settler> best = Comparator.<Settler>comparingInt(settler -> settler.getSkills().get(line.job()).level()).reversed()
                        .thenComparingDouble(settler -> settler.distanceToSqr(center));
                while (!candidates.isEmpty() && JobPlaces.line(block, line.job()).filter(current -> !current.full()).isPresent()) {
                    Settler settler = candidates.stream().min(best).orElseThrow();
                    if (SettlementWorld.assign(level, block.pos(), settler, line.job()) != JobAssignments.AssignResult.OK) {
                        break;
                    }
                    candidates.remove(settler);
                    settler.getWork().assigned(new SettlerJob(line.job(), block.pos()));
                    notifyPlayers(level, block.pos(), Component.translatable("civmod.auto_assign.assigned", settler.getName(),
                            line.job().displayName()));
                }
            }
        }
    }

    private static void notifyPlayers(ServerLevel level, BlockPos pos, Component message) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) <= NOTIFY_DISTANCE * NOTIFY_DISTANCE) {
                player.sendOverlayMessage(message);
            }
        }
    }

    /** Runs the morning check of every settlement of a dimension once its settler day advances (06:00). */
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            long day = DayClock.day(level);
            Long last = LAST_DAY.put(level.dimension(), day);
            if (last != null && day > last) {
                for (Settlement settlement : List.copyOf(SettlementData.get(level).settlements())) {
                    morning(level, settlement);
                }
            }
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        LAST_DAY.clear();
    }
}
