package com.gilfort.civmod.npc;

import com.gilfort.civmod.job.Job;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

/**
 * A job assigned to a settler via its JobBlock. The {@code Laborer} fallback is implicit and never stored.
 *
 * @param jobType  the job
 * @param jobBlock the position of the JobBlock offering it
 */
public record SettlerJob(Job jobType, BlockPos jobBlock) {

    /** Save codec. */
    public static final Codec<SettlerJob> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Job.CODEC.fieldOf("job").forGetter(SettlerJob::jobType),
            BlockPos.CODEC.fieldOf("block").forGetter(SettlerJob::jobBlock)
    ).apply(instance, SettlerJob::new));

    /** Network codec. */
    public static final StreamCodec<ByteBuf, SettlerJob> STREAM_CODEC = StreamCodec.composite(
            Job.STREAM_CODEC, SettlerJob::jobType,
            BlockPos.STREAM_CODEC, SettlerJob::jobBlock,
            SettlerJob::new);
}
