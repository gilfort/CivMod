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
 */
public record SettlerInfo(Component name, Component status, boolean homeless, Component settlementName, List<SettlerJob> jobs,
        List<JobState> jobStates) {

    /**
     * Status and buffer of a job.
     *
     * @param status   the job status
     * @param buffered the number of buffered items
     * @param preview  up to five buffered stacks
     */
    public record JobState(JobStatus status, int buffered, List<ItemStack> preview) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, JobState> STREAM_CODEC = StreamCodec.composite(
                JobStatus.STREAM_CODEC, JobState::status,
                ByteBufCodecs.VAR_INT, JobState::buffered,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), JobState::preview,
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
            SettlerInfo::new);

    /**
     * @param settler a settler (server side)
     * @return its current info
     */
    public static SettlerInfo of(Settler settler) {
        Component settlementName = Component.empty();
        if (settler.level() instanceof ServerLevel level && !settler.isHomeless()) {
            settlementName = SettlementData.get(level).settlement(settler.getSettlementId())
                    .map(Settlement::displayName).orElse(Component.empty());
        }
        List<JobState> states = settler.getJobs().stream().map(job -> {
            JobBuffer buffer = settler.getWork().buffer(job.jobBlock());
            return new JobState(settler.getWork().status(job.jobBlock()), buffer.count(),
                    buffer.stacks().stream().limit(5).map(ItemStack::copy).toList());
        }).toList();
        return new SettlerInfo(settler.getName(), settler.statusText(), settler.isHomeless(), settlementName, settler.getJobs(), states);
    }

    /**
     * @param jobs the new job list
     * @return a copy with the given jobs
     */
    public SettlerInfo withJobs(List<SettlerJob> jobs) {
        List<JobState> states = jobs.stream().map(job -> {
            int index = this.jobs.indexOf(job);
            return index >= 0 && index < jobStates.size() ? jobStates.get(index) : new JobState(JobStatus.IDLE, 0, List.of());
        }).toList();
        return new SettlerInfo(name, status, homeless, settlementName, List.copyOf(jobs), states);
    }
}
