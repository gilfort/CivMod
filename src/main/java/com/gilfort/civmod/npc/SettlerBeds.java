package com.gilfort.civmod.npc;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;

/**
 * Beds of settlers (vault {@code Systems/NPC Daily Schedule.md}): automatic claim of the nearest free bed via the
 * vanilla POI manager, explicit assignment, and straw bed spots for settlers without bed.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlerBeds {

    /** Search radius for straw bed spots around the JobBlock. */
    public static final int STRAW_RADIUS = 4;
    /** Radius of the POI query; the settlement border check filters the result. */
    private static final int POI_RADIUS = 64;

    /** Straw bed spots in use this night: spot → settler. */
    private static final Map<GlobalPos, UUID> STRAW_SPOTS = new HashMap<>();

    private SettlerBeds() {
    }

    /** Result of assigning a bed with the scroll. */
    public enum AssignResult {
        /** Assigned. */
        ASSIGNED,
        /** Assigned; taken from another settler. */
        TRANSFERRED,
        /** The settler's own bed was released. */
        RELEASED,
        /** Not a bed. */
        NOT_A_BED,
        /** Outside the settlement border. */
        OUTSIDE,
        /** The respawn bed of a player. */
        SPAWN_BED,
        /** The settler is homeless. */
        HOMELESS
    }

    /**
     * @param level a level
     * @param pos   a position (head or foot of a bed)
     * @return the head position if the block is a bed
     */
    public static Optional<BlockPos> head(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AbstractBedBlock) || !state.hasProperty(BedBlock.PART)) {
            return Optional.empty();
        }
        return Optional.of(state.getValue(BedBlock.PART) == BedPart.HEAD ? pos.immutable() : pos.relative(state.getValue(BedBlock.FACING)));
    }

    /**
     * @param level the level
     * @return head positions of beds that are the respawn point of an online player
     */
    public static Set<BlockPos> spawnBeds(ServerLevel level) {
        return level.getServer().getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getRespawnConfig)
                .filter(config -> config != null && config.respawnData().globalPos().dimension().equals(level.dimension()))
                .map(config -> config.respawnData().pos())
                .map(pos -> head(level, pos).orElse(pos))
                .collect(Collectors.toSet());
    }

    /**
     * Returns the settler's bed if it still exists and is no player's respawn bed; otherwise releases it and claims the
     * nearest free bed inside the settlement border.
     *
     * @param level      the level
     * @param data       the dimension's settlement data
     * @param settlement the settler's settlement
     * @param settler    the settler UUID
     * @param from       the position to search from
     * @return the bed head position, empty if no bed is available
     */
    public static Optional<BlockPos> ensureBed(ServerLevel level, SettlementData data, Settlement settlement, UUID settler, BlockPos from) {
        Optional<BlockPos> current = settlement.bedOf(settler);
        Set<BlockPos> spawnBeds = spawnBeds(level);
        if (current.isPresent()) {
            if (head(level, current.get()).filter(current.get()::equals).isPresent() && !spawnBeds.contains(current.get())) {
                return current;
            }
            data.releaseBed(settlement, current.get());
            releasePoi(level, current.get());
        }
        Optional<BlockPos> claimed = freeBeds(level, settlement, from).stream().findFirst();
        claimed.ifPresent(bed -> claim(level, data, settlement, settler, bed));
        return claimed;
    }

    /**
     * Free beds of a settlement, closest first: inside the border, not owned by a Settler, not reserved for a Villager
     * conversion, no player's respawn bed. A bed a Villager holds counts as free (Settlers before Villagers).
     *
     * @param level      the level
     * @param settlement the settlement
     * @param from       the position to search from
     * @return the head positions of the free beds
     */
    static List<BlockPos> freeBeds(ServerLevel level, Settlement settlement, BlockPos from) {
        Set<BlockPos> owned = settlement.beds().keySet();
        Collection<BlockPos> reserved = settlement.bedReservations().values();
        Set<BlockPos> spawnBeds = spawnBeds(level);
        return level.getPoiManager().findAllClosestFirstWithType(
                type -> type.is(PoiTypes.HOME),
                pos -> !owned.contains(pos) && !reserved.contains(pos) && !spawnBeds.contains(pos) && JobAssignments.insideBorder(settlement, pos),
                from, POI_RADIUS, PoiManager.Occupancy.ANY)
                .map(Pair::getSecond)
                .toList();
    }

    /**
     * Gives a bed to a settler and takes its POI ticket so Villagers do not claim it; a Villager holding the bed loses it.
     *
     * @param level      the level
     * @param data       the dimension's settlement data
     * @param settlement the settler's settlement
     * @param settler    the settler UUID
     * @param bed        the bed head position
     */
    static void claim(ServerLevel level, SettlementData data, Settlement settlement, UUID settler, BlockPos bed) {
        settlement.bedOf(settler).ifPresent(previous -> releasePoi(level, previous));
        data.assignBed(settlement, bed, settler);
        PoiManager poi = level.getPoiManager();
        if (take(poi, bed).isEmpty() && poi.existsAtPosition(PoiTypes.HOME, bed)) {
            evictVillagers(level, bed);
            poi.release(bed);
            take(poi, bed);
        }
    }

    private static Optional<BlockPos> take(PoiManager poi, BlockPos bed) {
        return poi.take(type -> type.is(PoiTypes.HOME), (type, pos) -> pos.equals(bed), bed, 1);
    }

    /** Villagers whose home is the bed forget it (their POI ticket is released by the caller). */
    private static void evictVillagers(ServerLevel level, BlockPos bed) {
        GlobalPos home = GlobalPos.of(level.dimension(), bed);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, new AABB(bed).inflate(POI_RADIUS))) {
            if (villager.getBrain().getMemory(MemoryModuleType.HOME).filter(home::equals).isPresent()) {
                villager.getBrain().eraseMemory(MemoryModuleType.HOME);
            }
        }
    }

    /**
     * Frees the POI ticket of a bed; a destroyed bed has no POI any more (releasing it would throw).
     *
     * @param level the level
     * @param head  the bed head position
     */
    public static void releasePoi(ServerLevel level, BlockPos head) {
        if (level.getPoiManager().existsAtPosition(PoiTypes.HOME, head)) {
            level.getPoiManager().release(head);
        }
    }

    /**
     * Assigns (or releases) a bed explicitly with the scroll.
     *
     * @param level      the level
     * @param data       the dimension's settlement data
     * @param settlement the settler's settlement (empty if homeless)
     * @param settler    the settler UUID
     * @param clicked    the clicked position
     * @return the result; for {@link AssignResult#TRANSFERRED} see {@link #previousOwner}
     */
    public static AssignResult assign(ServerLevel level, SettlementData data, Optional<Settlement> settlement, UUID settler, BlockPos clicked) {
        if (settlement.isEmpty()) {
            return AssignResult.HOMELESS;
        }
        Optional<BlockPos> head = head(level, clicked);
        if (head.isEmpty()) {
            return AssignResult.NOT_A_BED;
        }
        Settlement owner = settlement.get();
        if (!JobAssignments.insideBorder(owner, head.get())) {
            return AssignResult.OUTSIDE;
        }
        if (spawnBeds(level).contains(head.get())) {
            return AssignResult.SPAWN_BED;
        }
        Optional<UUID> current = Optional.ofNullable(owner.beds().get(head.get()));
        if (current.filter(settler::equals).isPresent()) {
            data.releaseBed(owner, head.get());
            releasePoi(level, head.get());
            return AssignResult.RELEASED;
        }
        claim(level, data, owner, settler, head.get());
        return current.isPresent() ? AssignResult.TRANSFERRED : AssignResult.ASSIGNED;
    }

    /**
     * A straw bed spot.
     *
     * @param pos    the block the settler lies on (head end)
     * @param facing the direction from foot to head
     */
    public record StrawSpot(BlockPos pos, Direction facing) {
    }

    /**
     * Reserves a free straw bed spot within {@value #STRAW_RADIUS} blocks of a JobBlock (solid ground, the spot and
     * the block behind it free, air above).
     *
     * @param level    the level
     * @param jobBlock the JobBlock
     * @param settler  the settler UUID
     * @return the spot, empty if none is free
     */
    public static Optional<StrawSpot> reserveStrawSpot(ServerLevel level, BlockPos jobBlock, UUID settler) {
        for (Map.Entry<GlobalPos, UUID> entry : STRAW_SPOTS.entrySet()) {
            if (entry.getValue().equals(settler) && entry.getKey().dimension().equals(level.dimension())
                    && entry.getKey().pos().distManhattan(jobBlock) <= 2 * STRAW_RADIUS + 2) {
                return Optional.of(new StrawSpot(entry.getKey().pos(), facingTo(entry.getKey().pos(), jobBlock)));
            }
        }
        Collection<BlockPos> taken = STRAW_SPOTS.keySet().stream().filter(key -> key.dimension().equals(level.dimension()))
                .map(GlobalPos::pos).toList();
        Optional<StrawSpot> spot = findStrawSpot(level, jobBlock, taken);
        spot.ifPresent(found -> {
            STRAW_SPOTS.entrySet().removeIf(entry -> entry.getValue().equals(settler));
            STRAW_SPOTS.put(GlobalPos.of(level.dimension(), found.pos()), settler);
        });
        return spot;
    }

    /**
     * Frees the straw spot of a settler (on waking).
     *
     * @param settler the settler UUID
     */
    public static void releaseStrawSpot(UUID settler) {
        STRAW_SPOTS.entrySet().removeIf(entry -> entry.getValue().equals(settler));
    }

    /**
     * Finds a free straw bed spot; spots are tried ring by ring around the JobBlock so settlers spread around it.
     *
     * @param level    the level
     * @param jobBlock the JobBlock
     * @param taken    spots already used (and their neighbours are avoided)
     * @return the spot
     */
    public static Optional<StrawSpot> findStrawSpot(ServerLevel level, BlockPos jobBlock, Collection<BlockPos> taken) {
        for (int ring = 1; ring <= STRAW_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos pos = jobBlock.offset(dx, dy, dz);
                        Direction facing = facingTo(pos, jobBlock);
                        BlockPos foot = pos.relative(facing.getOpposite());
                        boolean nearTaken = taken.stream().anyMatch(other -> other.distManhattan(pos) <= 1 || other.distManhattan(foot) <= 1);
                        if (!nearTaken && isFree(level, pos) && isFree(level, foot)) {
                            return Optional.of(new StrawSpot(pos, facing));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Direction facingTo(BlockPos pos, BlockPos jobBlock) {
        int dx = jobBlock.getX() - pos.getX();
        int dz = jobBlock.getZ() - pos.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockPos ground = pos.below();
        return level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    /**
     * Lets settlers keep sleeping on a straw bed or the ground (vanilla would wake them without a bed block).
     *
     * @param event the event
     */
    @SubscribeEvent
    static void onCanContinueSleeping(CanContinueSleepingEvent event) {
        if (event.getEntity() instanceof Settler settler && settler.isSleepingWithoutBed()) {
            event.setContinueSleeping(true);
        }
    }

    /**
     * Detects a skipped night: the clock jumped forward (much more than the time between two decisions) from the
     * evening or night into the working hours of a later day. Other clock changes (e.g. {@code /time set} during the
     * day) are not a skipped night.
     *
     * @param previousTicks clock ticks at the previous decision
     * @param currentTicks  clock ticks now
     * @param interval      expected ticks between decisions
     * @param workEnd       day time at which work ends
     * @return whether the night was skipped
     */
    public static boolean nightSkipped(long previousTicks, long currentTicks, long interval, long workEnd) {
        if (previousTicks < 0 || currentTicks - previousTicks <= interval + 1000) {
            return false;
        }
        boolean wasEvening = DayClock.dayTime(previousTicks) >= workEnd;
        boolean laterDay = DayClock.day(currentTicks) > DayClock.day(previousTicks);
        return (wasEvening || laterDay) && DayClock.dayTime(currentTicks) < workEnd;
    }
}
