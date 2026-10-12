package com.gilfort.civmod.settlement;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * The jobs a JobBlock can offer. Only base jobs of the PoC exist so far.
 */
public enum JobType implements StringRepresentable {

    /** See vault {@code Jobs/Lumberjack.md}. */
    LUMBERJACK("lumberjack"),
    /** See vault {@code Jobs/Miner.md}. */
    MINER("miner"),
    /** See vault {@code Jobs/Fisherman.md}. */
    FISHERMAN("fisherman");

    /** Codec by serialized name. */
    public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);

    /** Network codec by ordinal. */
    public static final StreamCodec<ByteBuf, JobType> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], JobType::ordinal);

    private final String id;

    JobType(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /**
     * @return the translated job name
     */
    public Component displayName() {
        return Component.translatable("job.civmod." + id);
    }

    /**
     * @return how much harder than standing around this job makes hungry per work minute (server config)
     */
    public double hungerFactor() {
        return switch (this) {
            case LUMBERJACK -> com.gilfort.civmod.config.CivModConfig.HUNGER_FACTOR_LUMBERJACK.get();
            case MINER -> com.gilfort.civmod.config.CivModConfig.HUNGER_FACTOR_MINER.get();
            case FISHERMAN -> com.gilfort.civmod.config.CivModConfig.HUNGER_FACTOR_FISHERMAN.get();
        };
    }
}
