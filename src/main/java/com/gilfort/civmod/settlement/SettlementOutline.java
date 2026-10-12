package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the settlement outline display: sends the connected JobBlocks of nearby settlements to players
 * holding a JobBlock item (or with the debug outline enabled). The client draws the combined join-radius outline.
 * <p>
 * Checked every {@value #CHECK_INTERVAL} ticks per player; data is only resent when settlements changed, the
 * player moved to another chunk or the display mode changed.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlementOutline {

    /** Ticks between checks per player. */
    private static final int CHECK_INTERVAL = 10;
    /** Settlements with a JobBlock within this horizontal distance are sent. */
    private static final int RANGE = 64 + SettlementRules.joinRadius(SettlementRules.MAX_LEVEL);

    private static final Map<UUID, Key> LAST_SENT = new HashMap<>();
    private static final Set<UUID> ALWAYS_SHOW = new HashSet<>();

    private record Key(String dimension, int revision, long chunk, boolean alwaysShow) {
    }

    private SettlementOutline() {
    }

    /**
     * A connected JobBlock for the outline.
     *
     * @param pos   the position
     * @param level the JobBlock level
     */
    public record OutlineBlock(BlockPos pos, int level) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, OutlineBlock> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OutlineBlock::pos,
                ByteBufCodecs.VAR_INT, OutlineBlock::level,
                OutlineBlock::new);
    }

    /**
     * The connected JobBlocks of one settlement.
     *
     * @param color  the settlement colour (RGB)
     * @param blocks the connected JobBlocks
     */
    public record OutlineSettlement(int color, List<OutlineBlock> blocks) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, OutlineSettlement> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, OutlineSettlement::color,
                OutlineBlock.STREAM_CODEC.apply(ByteBufCodecs.list()), OutlineSettlement::blocks,
                OutlineSettlement::new);
    }

    /**
     * Server → client: the connected JobBlocks of nearby settlements.
     *
     * @param settlements the settlements
     * @param alwaysShow  whether to show the outline without holding a JobBlock (debug)
     */
    public record Sync(List<OutlineSettlement> settlements, boolean alwaysShow) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("settlement_outline"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                OutlineSettlement.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::settlements,
                ByteBufCodecs.BOOL, Sync::alwaysShow,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Toggles the permanent debug outline of a player.
     *
     * @param player the player
     * @return whether the outline is now always shown
     */
    public static boolean toggleAlwaysShow(ServerPlayer player) {
        boolean enabled = !ALWAYS_SHOW.remove(player.getUUID());
        if (enabled) {
            ALWAYS_SHOW.add(player.getUUID());
        }
        LAST_SENT.remove(player.getUUID());
        // Send right away so the client also learns when the outline was switched off.
        PacketDistributor.sendToPlayer(player, new Sync(collect(SettlementData.get(player.level()), player.blockPosition()), enabled));
        return enabled;
    }

    /**
     * @param stack an item stack
     * @return whether the stack is a JobBlock item
     */
    public static boolean isJobBlockItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof JobBlock;
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % CHECK_INTERVAL != 0) {
            return;
        }
        boolean alwaysShow = ALWAYS_SHOW.contains(player.getUUID());
        boolean holding = isJobBlockItem(player.getItemInHand(InteractionHand.MAIN_HAND))
                || isJobBlockItem(player.getItemInHand(InteractionHand.OFF_HAND));
        if (!holding && !alwaysShow) {
            return;
        }
        ServerLevel level = player.level();
        SettlementData data = SettlementData.get(level);
        Key key = new Key(level.dimension().identifier().toString(), data.revision(), player.chunkPosition().pack(), alwaysShow);
        if (Objects.equals(LAST_SENT.get(player.getUUID()), key)) {
            return;
        }
        LAST_SENT.put(player.getUUID(), key);
        PacketDistributor.sendToPlayer(player, new Sync(collect(data, player.blockPosition()), alwaysShow));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
        ALWAYS_SHOW.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    private static List<OutlineSettlement> collect(SettlementData data, BlockPos center) {
        List<OutlineSettlement> result = new ArrayList<>();
        for (Settlement settlement : data.settlements()) {
            List<OutlineBlock> blocks = settlement.blocks().stream()
                    .filter(SettlementBlock::connected)
                    .map(block -> new OutlineBlock(block.pos(), block.level()))
                    .toList();
            boolean near = blocks.stream().anyMatch(block -> SettlementRules.horizontalDistance(block.pos(), center) <= RANGE);
            if (near) {
                result.add(new OutlineSettlement(settlement.color(), blocks));
            }
        }
        return result;
    }
}
