package com.gilfort.civmod.item;

import java.util.List;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The job picker (vault {@code Systems/Scroll of Assignment.md}): right-clicking a settler with a scroll bound to a
 * JobBlock opens a window listing the block's Jobs with taken / places ({@link JobPlaces}); the player's choice is
 * assigned like every assignment ({@link com.gilfort.civmod.settlement.JobAssignments#assign}). A Job whose places are
 * all taken opens the replace window instead: the player picks the holder the settler replaces.
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
     * One holder of a full Job in the replace window.
     *
     * @param id   the holder's UUID
     * @param name the holder's name
     * @param jobs the display names of all Jobs the holder has (mouseover)
     */
    public record Holder(UUID id, String name, List<Component> jobs) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Holder> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Holder::id,
                ByteBufCodecs.STRING_UTF8, Holder::name,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), Holder::jobs,
                Holder::new);
    }

    /**
     * Server -> client: open the replace window for a Job whose places are all taken.
     *
     * @param jobBlock    the JobBlock the scroll is bound to
     * @param settlerId   the settler's entity id
     * @param settlerName the settler's name
     * @param job         the Job
     * @param holders     the settlers holding the Job
     */
    public record OpenReplace(BlockPos jobBlock, int settlerId, Component settlerName, Job job, List<Holder> holders)
            implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<OpenReplace> TYPE = new Type<>(CivMod.id("job_picker_open_replace"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenReplace> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenReplace::jobBlock,
                ByteBufCodecs.VAR_INT, OpenReplace::settlerId,
                ComponentSerialization.STREAM_CODEC, OpenReplace::settlerName,
                Job.STREAM_CODEC, OpenReplace::job,
                Holder.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenReplace::holders,
                OpenReplace::new);

        @Override
        public Type<OpenReplace> type() {
            return TYPE;
        }
    }

    /**
     * Client -> server: replace a holder of the Job with the settler.
     *
     * @param jobBlock  the JobBlock the window was opened for
     * @param settlerId the settler's entity id
     * @param job       the Job
     * @param holder    the UUID of the holder to replace
     */
    public record Replace(BlockPos jobBlock, int settlerId, Job job, UUID holder) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Replace> TYPE = new Type<>(CivMod.id("job_picker_replace"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Replace> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Replace::jobBlock,
                ByteBufCodecs.VAR_INT, Replace::settlerId,
                Job.STREAM_CODEC, Replace::job,
                UUIDUtil.STREAM_CODEC, Replace::holder,
                Replace::new);

        @Override
        public Type<Replace> type() {
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
     * Opens the replace window for a Job whose places are all taken.
     *
     * @param player  the player holding the scroll
     * @param block   the bound JobBlock
     * @param settler the settler
     * @param job     the Job
     */
    static void openReplace(ServerPlayer player, SettlementBlock block, Settler settler, Job job) {
        ServerLevel level = player.level();
        List<Holder> holders = JobPlaces.holders(block, job).stream().map(assignee -> {
            List<Component> jobs = level.getEntity(assignee.id()) instanceof Settler held
                    ? held.getJobs().stream().map(SettlerJob::jobType).map(Job::displayName).toList()
                    : List.of(assignee.job().displayName());
            return new Holder(assignee.id(), assignee.name(), jobs);
        }).toList();
        PacketDistributor.sendToPlayer(player, new OpenReplace(block.pos(), settler.getId(), settler.getName(), job, holders));
    }

    /**
     * Handles the player's choice; ignored unless the player still holds a scroll bound to that JobBlock and stands
     * near the settler.
     *
     * @param player the player
     * @param choice the choice
     */
    public static void choose(ServerPlayer player, Choose choice) {
        if (bound(player, choice.jobBlock()) && player.level().getEntity(choice.settlerId()) instanceof Settler settler
                && settler.distanceTo(player) <= MAX_DISTANCE) {
            ScrollOfAssignmentItem.assign(player, choice.jobBlock(), settler, choice.job());
        }
    }

    /**
     * Handles the click on the swap button in the replace window; validated like {@link #choose} and again by
     * {@link SettlementWorld#replace} (the holder must still hold the Job).
     *
     * @param player  the player
     * @param replace the click
     */
    public static void replace(ServerPlayer player, Replace replace) {
        if (bound(player, replace.jobBlock()) && player.level().getEntity(replace.settlerId()) instanceof Settler settler
                && settler.distanceTo(player) <= MAX_DISTANCE) {
            ScrollOfAssignmentItem.replace(player, replace.jobBlock(), settler, replace.job(), replace.holder());
        }
    }

    private static boolean bound(ServerPlayer player, BlockPos jobBlock) {
        return List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND).stream()
                .map(player::getItemInHand)
                .filter(stack -> stack.getItem() instanceof ScrollOfAssignmentItem)
                .flatMap(stack -> ScrollOfAssignmentItem.binding(stack).flatMap(ScrollBinding::jobBlock).stream())
                .anyMatch(jobBlock::equals);
    }
}
