package com.gilfort.civmod.settlement;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * How the Guards of a Guard Post hold their ground (vault {@code Jobs/Guard.md}, Modes). The mode is chosen in the Guard
 * Post window and applies to every Guard of the block.
 */
public enum GuardMode implements StringRepresentable {

    /** Each Guard stands at one guard point, switching at 06:00 and 18:30; without points it stands next to the Guard Post. */
    GUARD("guard"),
    /** Each Guard walks the guard points in order. */
    PATROL("patrol"),
    /** Each Guard moves through the whole settlement on its own. */
    ROAM("roam");

    /** Save codec. */
    public static final Codec<GuardMode> CODEC = StringRepresentable.fromEnum(GuardMode::values);

    /** Network codec. */
    public static final StreamCodec<ByteBuf, GuardMode> STREAM_CODEC = ByteBufCodecs.idMapper(index -> values()[index], GuardMode::ordinal);

    private final String id;

    GuardMode(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /**
     * @return the mode after this one in the order Guard, Patrol, Roam, which wraps around
     */
    public GuardMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * @return the translated name for the mode button ("Mode: Guard")
     */
    public Component buttonLabel() {
        return Component.translatable("civmod.gui.job_block.guard_mode." + id);
    }
}
