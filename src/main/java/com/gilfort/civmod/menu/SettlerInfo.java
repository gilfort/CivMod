package com.gilfort.civmod.menu;

import java.util.List;

import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Snapshot of a settler shown in its GUI; sent to the client when the menu opens.
 *
 * @param name           the settler name
 * @param status         the status text
 * @param homeless       whether the settler belongs to no settlement
 * @param settlementName the settlement name (empty if homeless)
 * @param jobs           the assigned jobs in priority order (Laborer excluded)
 * @param jobStates      status and buffer per job, aligned with {@code jobs}
 * @param bed            where the settler sleeps
 * @param food           food level, saturation and appetite
 */
public record SettlerInfo(Component name, Component status, boolean homeless, Component settlementName, List<SettlerJob> jobs,
        List<JobState> jobStates, Component bed, FoodInfo food) {

    /**
     * Food state shown in the overview.
     *
     * @param level      the food level (0–20)
     * @param saturation the saturation buffer
     * @param appetite   the appetite multiplier
     */
    public record FoodInfo(int level, float saturation, float appetite) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, FoodInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FoodInfo::level,
                ByteBufCodecs.FLOAT, FoodInfo::saturation,
                ByteBufCodecs.FLOAT, FoodInfo::appetite,
                FoodInfo::new);
    }

    /**
     * Status and buffer of a job.
     *
     * @param status   the job status
     * @param buffered the number of buffered items
     * @param preview  the buffered stacks (up to 18)
     * @param found    icons of the resource types found by the scan
     * @param missing  per found type: whether the current tool cannot obtain it
     * @param notes    per found type: tooltip lines (empty: the item name)
     */
    public record JobState(JobStatus status, int buffered, List<ItemStack> preview, List<ItemStack> found, List<Boolean> missing,
            List<List<Component>> notes) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, JobState> STREAM_CODEC = StreamCodec.composite(
                JobStatus.STREAM_CODEC, JobState::status,
                ByteBufCodecs.VAR_INT, JobState::buffered,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), JobState::preview,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), JobState::found,
                ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), JobState::missing,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list()), JobState::notes,
                JobState::new);
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, SettlerInfo> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.STREAM_CODEC, SettlerInfo::name,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::status,
            ByteBufCodecs.BOOL, SettlerInfo::homeless,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::settlementName,
            SettlerJob.STREAM_CODEC.apply(ByteBufCodecs.list()), SettlerInfo::jobs,
            JobState.STREAM_CODEC.apply(ByteBufCodecs.list()), SettlerInfo::jobStates,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::bed,
            FoodInfo.STREAM_CODEC, SettlerInfo::food,
            SettlerInfo::new);

    /**
     * @param settler a settler (server side)
     * @return its current info
     */
    public static SettlerInfo of(Settler settler) {
        Component settlementName = Component.empty();
        Component bed = Component.translatable("civmod.gui.settler.bed.none");
        if (settler.level() instanceof ServerLevel level && !settler.isHomeless()) {
            java.util.Optional<Settlement> settlement = SettlementData.get(level).settlement(settler.getSettlementId());
            settlementName = settlement.map(Settlement::displayName).orElse(Component.empty());
            java.util.Optional<net.minecraft.core.BlockPos> bedPos = settlement.flatMap(s -> s.bedOf(settler.getUUID()));
            if (bedPos.isPresent()) {
                bed = Component.literal(bedPos.get().toShortString());
            } else if (settler.getWork().restPlace().isPresent() || settler.isSleepingWithoutBed()) {
                bed = Component.translatable("civmod.gui.settler.bed.straw");
            }
        }
        List<JobState> states = settler.getJobs().stream().map(job -> {
            JobBuffer buffer = settler.getWork().buffer(job.jobBlock());
            List<com.gilfort.civmod.job.JobProfile.Found> found = settler.getWork().found(job);
            return new JobState(settler.getWork().status(job.jobBlock()), buffer.count(),
                    buffer.stacks().stream().limit(18).map(ItemStack::copy).toList(),
                    found.stream().map(com.gilfort.civmod.job.JobProfile.Found::icon).toList(),
                    found.stream().map(entry -> !entry.available()).toList(),
                    found.stream().map(com.gilfort.civmod.job.JobProfile.Found::tooltip).toList());
        }).toList();
        com.gilfort.civmod.food.SettlerFood food = settler.getFood();
        float appetite = food.appetite(settler.getRandom(), com.gilfort.civmod.config.CivModConfig.APPETITE_MIN.get(),
                com.gilfort.civmod.config.CivModConfig.APPETITE_MAX.get());
        return new SettlerInfo(settler.getName(), settler.statusText(), settler.isHomeless(), settlementName, settler.getJobs(), states, bed,
                new FoodInfo(food.level(), food.saturation(), appetite));
    }

    /**
     * @param jobs the new job list
     * @return a copy with the given jobs
     */
    public SettlerInfo withJobs(List<SettlerJob> jobs) {
        List<JobState> states = jobs.stream().map(job -> {
            int index = this.jobs.indexOf(job);
            return index >= 0 && index < jobStates.size() ? jobStates.get(index) : new JobState(JobStatus.IDLE, 0, List.of(), List.of(), List.of(), List.of());
        }).toList();
        return new SettlerInfo(name, status, homeless, settlementName, List.copyOf(jobs), states, bed, food);
    }
}
