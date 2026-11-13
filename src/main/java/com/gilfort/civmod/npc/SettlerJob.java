package com.gilfort.civmod.npc;

import com.gilfort.civmod.api.Job;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A job assigned to a settler via its JobBlock. The {@code Laborer} fallback is implicit and never stored.
 *
 * @param jobType  the job
 * @param jobBlock the position of the JobBlock offering it
 * @param auto     the "Auto" switch of a Deliveryman job: whether the Deliveryman also takes the automatic tasks; off, it
 *                 works Delivery Slips only (vault {@code Jobs/Deliveryman.md}); no meaning for other Jobs
 */
public record SettlerJob(Job jobType, BlockPos jobBlock, boolean auto) {

    /** Save codec. */
    public static final Codec<SettlerJob> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Job.CODEC.fieldOf("job").forGetter(SettlerJob::jobType),
            BlockPos.CODEC.fieldOf("block").forGetter(SettlerJob::jobBlock),
            Codec.BOOL.optionalFieldOf("auto", true).forGetter(SettlerJob::auto)
    ).apply(instance, SettlerJob::new));

    /** Network codec. */
    public static final StreamCodec<ByteBuf, SettlerJob> STREAM_CODEC = StreamCodec.composite(
            Job.STREAM_CODEC, SettlerJob::jobType,
            BlockPos.STREAM_CODEC, SettlerJob::jobBlock,
            ByteBufCodecs.BOOL, SettlerJob::auto,
            SettlerJob::new);

    /**
     * @param jobType  the job
     * @param jobBlock the position of the JobBlock offering it
     */
    public SettlerJob(Job jobType, BlockPos jobBlock) {
        this(jobType, jobBlock, true);
    }

    /**
     * @param on the wanted state of the "Auto" switch
     * @return this job with the switch set
     */
    public SettlerJob withAuto(boolean on) {
        return new SettlerJob(jobType, jobBlock, on);
    }
}
