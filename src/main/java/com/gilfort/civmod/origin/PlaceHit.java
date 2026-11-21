package com.gilfort.civmod.origin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Where the nearest biome of one biome type lies, seen from a settlement: what the settlement keeps after its founding lookup
 * (vault {@code Systems/Settler Origin.md}). Never coordinates; a text can only name the direction and the days of walking.
 *
 * @param direction the compass direction from the settlement
 * @param blocks    the distance in blocks
 */
public record PlaceHit(Compass direction, int blocks) {

    /** How far a settler gets on foot in one day. */
    public static final int WALKING_DAY_BLOCKS = 1000;

    /** Save codec. */
    public static final Codec<PlaceHit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Compass.CODEC.fieldOf("direction").forGetter(PlaceHit::direction),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("blocks").forGetter(PlaceHit::blocks)
    ).apply(instance, PlaceHit::new));

    /**
     * @return the distance in days of walking, at least 1
     */
    public int days() {
        return Math.max(1, Math.round((float) blocks / WALKING_DAY_BLOCKS));
    }
}
