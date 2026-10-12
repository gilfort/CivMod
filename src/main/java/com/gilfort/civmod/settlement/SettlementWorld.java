package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.block.JobBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Connects {@link SettlementManager} to the world: syncs JobBlock entities, notifies players and cleans up
 * JobBlocks that vanished while their chunk was unloaded.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlementWorld {

    /** Players within this distance are notified about automatic connections. */
    private static final double NOTIFY_DISTANCE = 32.0;

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
        return SettlementManager.place(SettlementData.get(level), pos, jobType, jobLevel, level.getGameTime(), listener(level));
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

    private static SettlementManager.Listener listener(ServerLevel level) {
        return new SettlementManager.Listener() {
            @Override
            public void blockChanged(BlockPos pos, int settlementId, boolean connected) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof JobBlockEntity jobBlock) {
                    jobBlock.setSettlement(settlementId, connected);
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
