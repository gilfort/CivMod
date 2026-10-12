package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Connects {@link SettlementManager} to the world: syncs JobBlock entities, notifies players and cleans up
 * JobBlocks that vanished while their chunk was unloaded.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlementWorld {

    /** Players within this distance are notified about automatic connections. */
    private static final double NOTIFY_DISTANCE = 32.0;

    /** Placement results of the current tick waiting for their placing player ({@code onPlace} came first). */
    private static final Map<GlobalPos, SettlementManager.Placement> PENDING_PLACEMENTS = new HashMap<>();
    /** Placing players of the current tick waiting for the placement result ({@code setPlacedBy} came first). */
    private static final Map<GlobalPos, UUID> PENDING_PLACERS = new HashMap<>();

    private SettlementWorld() {
    }

    /**
     * Registers a placed JobBlock in its dimension.
     *
     * @param level   the dimension
     * @param pos     the JobBlock position
     * @param jobType the offered job
     * @param jobLevel the JobBlock level
     * @return how the block was integrated
     */
    public static SettlementManager.Placement place(ServerLevel level, BlockPos pos, JobType jobType, int jobLevel) {
        SettlementManager.Placement placement = SettlementManager.place(SettlementData.get(level), pos, jobType, jobLevel,
                level.getGameTime(), level.getRandom(), listener(level));
        GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
        UUID placer = PENDING_PLACERS.remove(key);
        ServerPlayer player = placer == null ? null : level.getServer().getPlayerList().getPlayer(placer);
        if (player != null) {
            feedback(level, player, placement);
        } else {
            PENDING_PLACEMENTS.put(key, placement);
        }
        return placement;
    }

    /**
     * Handles a JobBlock placed by a player: applies the item level and gives feedback. Works in both orders of
     * {@code onPlace} and {@code setPlacedBy} (NeoForge defers {@code onPlace} for player placement).
     *
     * @param level    the dimension
     * @param pos      the JobBlock position
     * @param player   the placing player
     * @param jobLevel the level of the placed item
     */
    public static void placedByPlayer(ServerLevel level, BlockPos pos, ServerPlayer player, int jobLevel) {
        setLevel(level, pos, jobLevel);
        GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
        SettlementManager.Placement placement = PENDING_PLACEMENTS.remove(key);
        if (placement != null) {
            feedback(level, player, placement);
        } else {
            PENDING_PLACERS.put(key, player.getUUID());
        }
    }

    private static void feedback(ServerLevel level, ServerPlayer player, SettlementManager.Placement placement) {
        notifyPlacement(player, placement);
        if (placement.outcome() == SettlementManager.Outcome.FOUNDED) {
            recordFounding(level, player);
        }
    }

    /**
     * Applies a JobBlock level to its settlement entry.
     *
     * @param level    the dimension
     * @param pos      the JobBlock position
     * @param jobLevel the level
     */
    public static void setLevel(ServerLevel level, BlockPos pos, int jobLevel) {
        SettlementManager.setLevel(SettlementData.get(level), pos, jobLevel, listener(level));
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        PENDING_PLACEMENTS.clear();
        PENDING_PLACERS.clear();
    }

    /**
     * Unregisters a removed JobBlock.
     *
     * @param level the dimension
     * @param pos   the JobBlock position
     */
    public static void remove(ServerLevel level, BlockPos pos) {
        SettlementManager.remove(SettlementData.get(level), pos, listener(level));
    }

    /**
     * Sends the result of a player placement as action bar message.
     *
     * @param player    the placing player
     * @param placement the placement result
     */
    public static void notifyPlacement(ServerPlayer player, SettlementManager.Placement placement) {
        Component message = switch (placement.outcome()) {
            case FOUNDED -> Component.translatable("civmod.settlement.founded");
            case JOINED -> Component.translatable("civmod.settlement.joined", placement.settlement().displayName());
            case UNCONNECTED -> Component.translatable("civmod.settlement.unconnected", placement.distance(), placement.required())
                    .withStyle(net.minecraft.ChatFormatting.RED);
        };
        player.sendOverlayMessage(message);
    }

    /**
     * @param level the dimension
     * @return a listener applying settlement side effects to that dimension
     */
    public static SettlementManager.Listener listener(ServerLevel level) {
        return new SettlementManager.Listener() {
            @Override
            public void blockChanged(BlockPos pos, int settlementId, boolean connected) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof JobBlockEntity jobBlock) {
                    jobBlock.setSettlement(settlementId, connected);
                }
            }

            @Override
            public void dissolved(Settlement settlement) {
                for (UUID member : settlement.members()) {
                    if (level.getEntity(member) instanceof Settler settler) {
                        settler.becomeHomeless();
                    }
                }
            }

            @Override
            public void autoConnected(BlockPos pos) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 15, 0.4, 0.4, 0.4, 0.0);
                for (ServerPlayer player : level.players()) {
                    if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= NOTIFY_DISTANCE * NOTIFY_DISTANCE) {
                        player.sendOverlayMessage(Component.translatable("civmod.settlement.connected"));
                    }
                }
            }
        };
    }

    /**
     * Enforces the optional founding limit (one settlement per player per in-game day).
     *
     * @param event the block place event
     */
    @SubscribeEvent
    static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!CivModConfig.LIMIT_FOUNDING_PER_DAY.get() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player) || !(event.getPlacedBlock().getBlock() instanceof JobBlock)) {
            return;
        }
        // For player placement NeoForge defers onPlace until after this event, so the block is not registered yet.
        SettlementData data = SettlementData.get(level);
        if (SettlementManager.wouldFound(data, event.getPos())
                && data.lastFoundingDay(player.getUUID()).filter(day -> day == day(level)).isPresent()) {
            event.setCanceled(true);
            player.sendOverlayMessage(Component.translatable("civmod.settlement.founding_limit").withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    /**
     * Records that a player founded a settlement (for the founding limit).
     *
     * @param level  the dimension
     * @param player the player
     */
    public static void recordFounding(ServerLevel level, ServerPlayer player) {
        SettlementData.get(level).recordFounding(player.getUUID(), day(level));
    }

    private static long day(ServerLevel level) {
        return level.getGameTime() / 24000L;
    }

    /**
     * Removes recorded JobBlocks that no longer exist when their chunk loads (e.g. removed by external tools).
     *
     * @param event the chunk load event
     */
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        List<BlockPos> missing = new ArrayList<>();
        for (BlockPos pos : SettlementData.get(level).blocksInChunk(chunk.getPos())) {
            if (!(chunk.getBlockState(pos).getBlock() instanceof JobBlock)) {
                missing.add(pos);
            }
        }
        if (!missing.isEmpty()) {
            // Level interactions must not happen during chunk loading; defer to the next tick.
            level.getServer().execute(() -> missing.forEach(pos -> {
                CivMod.LOGGER.info("Removing missing JobBlock at {} from its settlement", pos);
                remove(level, pos);
            }));
        }
    }

    /**
     * @param level the dimension
     * @param pos   a JobBlock position
     * @return the connection status of that block
     */
    public static SettlementManager.Status status(ServerLevel level, BlockPos pos) {
        return SettlementManager.status(SettlementData.get(level), pos);
    }

    /**
     * @param level the dimension
     * @param pos   a position
     * @return the settlement owning a JobBlock at that position
     */
    public static Optional<Settlement> settlementAt(ServerLevel level, BlockPos pos) {
        return SettlementData.get(level).settlementAt(pos);
    }
}
