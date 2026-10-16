package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.item.ScrollBinding;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the work point display: sends the points of JobBlocks to players holding a Scroll of Assignment.
 * Bound to a JobBlock: only its points; otherwise the points of all JobBlocks within {@value #RANGE} blocks.
 * Resent only on change (settlement revision, chunk, binding).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobPointsDisplay {

    private static final int CHECK_INTERVAL = 10;
    private static final int RANGE = 32;
    private static final Map<UUID, Key> LAST_SENT = new HashMap<>();

    private record Key(String dimension, int revision, long chunk, Optional<BlockPos> bound, Optional<UUID> settler, boolean holding) {
    }

    private JobPointsDisplay() {
    }

    /**
     * Points of one JobBlock.
     *
     * @param jobBlock   the JobBlock position
     * @param work       work POIs (first = primary)
     * @param collect    collect inventory
     * @param dropOff    drop-off inventory
     * @param storage    containers linked as Clerk storage
     * @param standSpots Clerk stand spots (numbered in this order)
     */
    public record JobPoints(BlockPos jobBlock, List<BlockPos> work, Optional<BlockPos> collect, Optional<BlockPos> dropOff,
            List<BlockPos> storage, List<SettlementBlock.StandSpot> standSpots) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, JobPoints> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, JobPoints::jobBlock,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), JobPoints::work,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), JobPoints::collect,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), JobPoints::dropOff,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), JobPoints::storage,
                SettlementBlock.StandSpot.STREAM_CODEC.apply(ByteBufCodecs.list()), JobPoints::standSpots,
                JobPoints::new);
    }

    /**
     * Server → client: points to display.
     *
     * @param points  the JobBlocks with their points
     * @param bound   the JobBlock the scroll is bound to (its points are highlighted)
     * @param beds    owned beds of the bound settler's settlement (only when bound to a settler)
     * @param ownBed  the bed of the bound settler
     * @param settler the bound settler
     */
    public record Sync(List<JobPoints> points, Optional<BlockPos> bound, List<BlockPos> beds, Optional<BlockPos> ownBed,
            Optional<UUID> settler) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("job_points"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                JobPoints.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::points,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), Sync::bound,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::beds,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), Sync::ownBed,
                net.minecraft.core.UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Sync::settler,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * @param stack an item stack
     * @return whether it is a Scroll of Assignment
     */
    public static boolean isScroll(ItemStack stack) {
        return stack.getItem() instanceof ScrollOfAssignmentItem;
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % CHECK_INTERVAL != 0) {
            return;
        }
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack scroll = isScroll(main) ? main : player.getItemInHand(InteractionHand.OFF_HAND);
        boolean holding = isScroll(scroll);
        Key last = LAST_SENT.get(player.getUUID());
        if (!holding) {
            if (last != null && last.holding()) {
                LAST_SENT.put(player.getUUID(), new Key("", -1, 0L, Optional.empty(), Optional.empty(), false));
            }
            return;
        }
        ServerLevel level = player.level();
        SettlementData data = SettlementData.get(level);
        Optional<BlockPos> bound = ScrollOfAssignmentItem.binding(scroll).flatMap(ScrollBinding::jobBlock);
        Optional<UUID> settler = ScrollOfAssignmentItem.binding(scroll).flatMap(ScrollBinding::settler);
        Key key = new Key(level.dimension().identifier().toString(), data.revision(), player.chunkPosition().pack(), bound, settler, true);
        if (Objects.equals(last, key)) {
            return;
        }
        LAST_SENT.put(player.getUUID(), key);
        Optional<Settlement> settlement = settler.flatMap(uuid -> data.settlements().stream().filter(s -> s.members().contains(uuid)).findFirst());
        List<BlockPos> beds = settlement.map(s -> List.copyOf(s.beds().keySet())).orElse(List.of());
        Optional<BlockPos> ownBed = settlement.flatMap(s -> s.bedOf(settler.get()));
        PacketDistributor.sendToPlayer(player, new Sync(collect(data, player.blockPosition(), bound), bound, beds, ownBed, settler));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    /**
     * @param data   the dimension's settlement data
     * @param center the player position
     * @param bound  the bound JobBlock
     * @return the points to display
     */
    static List<JobPoints> collect(SettlementData data, BlockPos center, Optional<BlockPos> bound) {
        List<JobPoints> result = new ArrayList<>();
        for (Settlement settlement : data.settlements()) {
            for (SettlementBlock block : settlement.blocks()) {
                boolean include = bound.map(block.pos()::equals)
                        .orElseGet(() -> SettlementRules.horizontalDistance(block.pos(), center) <= RANGE);
                if (include) {
                    result.add(new JobPoints(block.pos(), block.workPoints(), block.collect(), block.dropOff(), block.storage(),
                            block.standSpots()));
                }
            }
        }
        return result;
    }
}
