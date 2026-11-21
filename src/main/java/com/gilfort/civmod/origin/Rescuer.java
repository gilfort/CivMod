package com.gilfort.civmod.origin;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * The player who took a settler in. Display only: it gives no control rights (vault {@code Systems/Settler Origin.md}).
 *
 * @param player the player's id
 * @param name   the player's name when it happened
 * @param day    the settler day it happened on
 */
public record Rescuer(UUID player, String name, long day) {

    /** Save codec. */
    public static final Codec<Rescuer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("player").forGetter(Rescuer::player),
            Codec.STRING.fieldOf("name").forGetter(Rescuer::name),
            Codec.LONG.fieldOf("day").forGetter(Rescuer::day)
    ).apply(instance, Rescuer::new));
}
