package com.gilfort.civmod.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * The mission a settler reserved or is away on (vault {@code Systems/Mission Jobs.md}), saved with the settler.
 *
 * @param board      the Mission Board the mission was taken from
 * @param mission    the mission id
 * @param returnTime the world time of the return, {@code -1} while the mission is only reserved
 */
public record MissionTrip(BlockPos board, Identifier mission, long returnTime) {

    /** Save codec. */
    public static final Codec<MissionTrip> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("board").forGetter(MissionTrip::board),
            Identifier.CODEC.fieldOf("mission").forGetter(MissionTrip::mission),
            Codec.LONG.optionalFieldOf("return_time", -1L).forGetter(MissionTrip::returnTime)
    ).apply(instance, MissionTrip::new));

    /**
     * @return whether the settler has left (not only reserved the mission)
     */
    public boolean away() {
        return returnTime >= 0;
    }
}
