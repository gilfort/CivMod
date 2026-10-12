package com.gilfort.civmod.item;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a Scroll of Assignment is bound to: a JobBlock (job points and assignment) or a settler (NPC settings).
 *
 * @param jobBlock the bound JobBlock
 * @param settler  the bound settler
 * @param label    display text for the tooltip (job name or settler name)
 */
public record ScrollBinding(Optional<BlockPos> jobBlock, Optional<UUID> settler, String label) {

    /** Save codec. */
    public static final Codec<ScrollBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.optionalFieldOf("job_block").forGetter(ScrollBinding::jobBlock),
            UUIDUtil.CODEC.optionalFieldOf("settler").forGetter(ScrollBinding::settler),
            Codec.STRING.optionalFieldOf("label", "").forGetter(ScrollBinding::label)
    ).apply(instance, ScrollBinding::new));

    /** Network codec. */
    public static final StreamCodec<ByteBuf, ScrollBinding> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), ScrollBinding::jobBlock,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), ScrollBinding::settler,
            ByteBufCodecs.STRING_UTF8, ScrollBinding::label,
            ScrollBinding::new);

    /**
     * @param pos   the JobBlock position
     * @param label the job name
     * @return a binding to a JobBlock
     */
    public static ScrollBinding jobBlock(BlockPos pos, String label) {
        return new ScrollBinding(Optional.of(pos.immutable()), Optional.empty(), label);
    }

    /**
     * @param settler the settler UUID
     * @param label   the settler name
     * @return a binding to a settler
     */
    public static ScrollBinding settler(UUID settler, String label) {
        return new ScrollBinding(Optional.empty(), Optional.of(settler), label);
    }
}
