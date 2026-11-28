package com.gilfort.civmod.settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;

/**
 * A point a settler works at: the block it works at or stands on and the direction it faces there. Set with the
 * Scroll of Assignment; the facing is the reverse of the player's look direction at the click (vault
 * {@code Systems/Scroll of Assignment.md}). Used for the work points of a JobBlock and for the stand spots of a Clerk
 * (vault {@code Jobs/Clerk.md}).
 *
 * @param pos    the block of the point
 * @param facing the direction the settler faces there
 */
public record WorkPoint(BlockPos pos, Direction facing) {

    /** Save codec. */
    public static final Codec<WorkPoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(WorkPoint::pos),
            Direction.CODEC.fieldOf("facing").forGetter(WorkPoint::facing)
    ).apply(instance, WorkPoint::new));

    /** Network codec. */
    public static final StreamCodec<ByteBuf, WorkPoint> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, WorkPoint::pos,
            Direction.STREAM_CODEC, WorkPoint::facing,
            WorkPoint::new);
}
