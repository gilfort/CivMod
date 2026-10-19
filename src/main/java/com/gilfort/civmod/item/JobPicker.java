package com.gilfort.civmod.item;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The job picker (vault {@code Systems/Scroll of Assignment.md}): right-clicking a settler with a scroll bound to a
 * JobBlock opens a window listing the block's Jobs with taken / places ({@link JobPlaces}); the player's choice is
 * assigned like every assignment ({@link com.gilfort.civmod.settlement.JobAssignments#assign}).
 */
public final class JobPicker {

    /** How far the player may be from the settler when the choice arrives (blocks). */
    private static final double MAX_DISTANCE = 10.0;

    private JobPicker() {
    }

    /**
     * Server → client: open the job picker.
     *
     * @param jobBlock    the JobBlock the scroll is bound to
     * @param settlerId   the settler's entity id
     * @param settlerName the settler's name
     * @param lines       the block's Jobs with taken / places
     */
    public record Open(BlockPos jobBlock, int settlerId, Component settlerName, List<JobPlaces.Line> lines) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Open> TYPE = new Type<>(CivMod.id("job_picker_open"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::jobBlock,
                ByteBufCodecs.VAR_INT, Open::settlerId,
                ComponentSerialization.STREAM_CODEC, Open::settlerName,
                JobPlaces.Line.STREAM_CODEC.apply(ByteBufCodecs.list()), Open::lines,
                Open::new);

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: assign the chosen Job.
     *
     * @param jobBlock  the JobBlock the picker was opened for
     * @param settlerId the settler's entity id
     * @param job       the chosen Job
     */
    public record Choose(BlockPos jobBlock, int settlerId, Job job) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Choose> TYPE = new Type<>(CivMod.id("job_picker_choose"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Choose> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Choose::jobBlock,
                ByteBufCodecs.VAR_INT, Choose::settlerId,
                Job.STREAM_CODEC, Choose::job,
                Choose::new);

        @Override
        public Type<Choose> type() {
            return TYPE;
        }
    }

    /**
     * Opens the job picker for a settler.
     *
     * @param player  the player holding the scroll
     * @param block   the bound JobBlock
     * @param settler the settler
     */
    static void open(ServerPlayer player, SettlementBlock block, Settler settler) {
        PacketDistributor.sendToPlayer(player, new Open(block.pos(), settler.getId(), settler.getName(), JobPlaces.lines(block)));
    }

    /**
     * Handles the player's choice; ignored unless the player still holds a scroll bound to that JobBlock and stands
     * near the settler.
     *
     * @param player the player
     * @param choice the choice
     */
    public static void choose(ServerPlayer player, Choose choice) {
        boolean bound = List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND).stream()
                .map(player::getItemInHand)
                .filter(stack -> stack.getItem() instanceof ScrollOfAssignmentItem)
                .flatMap(stack -> ScrollOfAssignmentItem.binding(stack).flatMap(ScrollBinding::jobBlock).stream())
                .anyMatch(choice.jobBlock()::equals);
        if (bound && player.level().getEntity(choice.settlerId()) instanceof Settler settler && settler.distanceTo(player) <= MAX_DISTANCE) {
            ScrollOfAssignmentItem.assign(player, choice.jobBlock(), settler, choice.job());
        }
    }
}
