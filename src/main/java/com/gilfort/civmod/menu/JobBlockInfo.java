package com.gilfort.civmod.menu;

import java.util.List;

import com.gilfort.civmod.api.Job;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a JobBlock window knows from the moment it opens: the header (the Job and the block level) and what never
 * changes while the window is open. Everything else that changes (the Workers and Missions tabs, the header tooltip)
 * syncs through {@link TabData}. Sent to the client when the menu opens.
 *
 * @param jobType  the offered job
 * @param level    the JobBlock level
 * @param overview the "?" overview of the Job's biome bonuses ({@link BiomeBonusText#overview}), static for the Job
 */
public record JobBlockInfo(Job jobType, int level, List<Component> overview) {

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, JobBlockInfo> STREAM_CODEC = StreamCodec.composite(
            Job.STREAM_CODEC, JobBlockInfo::jobType,
            ByteBufCodecs.VAR_INT, JobBlockInfo::level,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), JobBlockInfo::overview,
            JobBlockInfo::new);

    /**
     * @param jobType  the offered job
     * @param level    the JobBlock level
     * @param overview the biome bonus overview
     */
    public JobBlockInfo {
        overview = List.copyOf(overview);
    }

    /**
     * @param jobType  the offered job
     * @param jobLevel the JobBlock level
     * @return the info of a JobBlock of that Job and level
     */
    public static JobBlockInfo of(Job jobType, int jobLevel) {
        return new JobBlockInfo(jobType, jobLevel, BiomeBonusText.overview(jobType));
    }
}
