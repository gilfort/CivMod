package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Who takes which mission (vault {@code Systems/Mission Jobs.md}): settlers of one Mission Board that want a mission
 * within one decision interval ({@value SettlerWork#DECISION_INTERVAL} ticks, decisions are staggered) pick together,
 * the lowest Mission Job skill first, equal skills in random order ({@link MissionRules#order}); each takes the mission
 * of the pick rule ({@link MissionRules#pick}: missions the player assigned to it first) off the offer. Nothing is kept
 * while no settler asks.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
final class MissionPicks {

    private record Key(ResourceKey<Level> dimension, BlockPos board) {
    }

    /** Settlers waiting for a pick on one board. */
    private static final class Batch {
        private final ServerLevel level;
        private final long due;
        private final Map<UUID, Settler> settlers = new LinkedHashMap<>();

        Batch(ServerLevel level) {
            this.level = level;
            this.due = level.getGameTime() + SettlerWork.DECISION_INTERVAL;
        }
    }

    private static final Map<Key, Batch> BATCHES = new HashMap<>();

    private MissionPicks() {
    }

    /**
     * Asks for a mission; the pick happens with the other settlers of the board asking in the same decision interval.
     * Asking again before the pick changes nothing.
     *
     * @param level   the level
     * @param board   the Mission Board
     * @param settler the settler
     */
    static void request(ServerLevel level, BlockPos board, Settler settler) {
        BATCHES.computeIfAbsent(new Key(level.dimension(), board.immutable()), key -> new Batch(level)).settlers.putIfAbsent(settler.getUUID(), settler);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (BATCHES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Key, Batch>> iterator = BATCHES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Batch> entry = iterator.next();
            Batch batch = entry.getValue();
            if (batch.level.getGameTime() >= batch.due) {
                iterator.remove();
                resolve(batch.level, entry.getKey().board(), List.copyOf(batch.settlers.values()));
            }
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        BATCHES.clear();
    }

    /** A settler asking for a mission, with its Mission Job and effective skill. */
    private record Candidate(Settler settler, SettlerJob job, int skill) {
    }

    private static void resolve(ServerLevel level, BlockPos board, List<Settler> settlers) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> block = data.settlementAt(board).flatMap(s -> s.block(board))
                .filter(entry -> entry.jobType().category() == JobCategory.MISSION);
        if (block.isEmpty()) {
            return;
        }
        Map<Identifier, Mission> pool = Missions.all(level.registryAccess());
        MissionOffers.refresh(data, board, Math.floorDiv(level.getOverworldClockTime(), 24000L), pool, level.getRandom());
        int cap = SettlementRules.skillCap(block.get().level());
        List<Candidate> candidates = settlers.stream()
                .filter(settler -> !settler.isRemoved() && !settler.getWork().missions().hasTrip())
                .flatMap(settler -> MissionLoop.missionJob(settler, board)
                        .map(job -> new Candidate(settler, job, settler.getSkills().effectiveLevel(job.jobType(), cap))).stream())
                .toList();
        for (Candidate candidate : MissionRules.order(candidates, Candidate::skill, level.getRandom())) {
            List<MissionOffer.Entry> offer = block.get().missionOffer().entries();
            OptionalInt pick = MissionRules.pick(offer, pool, candidate.job().jobType(), candidate.skill(),
                    candidate.settler().getUUID(), level.getRandom());
            MissionLoop loop = candidate.settler().getWork().missions();
            Optional<MissionOffer.Entry> entry = pick.isPresent() ? Optional.of(offer.get(pick.getAsInt())) : Optional.empty();
            if (entry.isPresent() && MissionOffers.take(data, board, pick.getAsInt(), entry.get().mission())) {
                // A locked entry the settler may take is one the player assigned to it.
                loop.reserve(board, entry.get().mission(), entry.get().locked());
            } else {
                loop.noMission(level, board);
            }
        }
    }
}
