package com.gilfort.civmod.job;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * The mission a settler reserved or is on (vault {@code Systems/Mission Jobs.md}), saved with the settler. A departed
 * settler first walks out of the settlement and vanishes there ({@code exit}); it reappears at that spot.
 *
 * @param board      the Mission Board the mission was taken from
 * @param mission    the mission id
 * @param returnTime the world time of the return, {@code -1} while the mission is only reserved
 * @param exit       where the settler vanished, empty while it is still walking out
 */
public record MissionTrip(BlockPos board, Identifier mission, long returnTime, Optional<BlockPos> exit) {

    /** Save codec. */
    public static final Codec<MissionTrip> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("board").forGetter(MissionTrip::board),
            Identifier.CODEC.fieldOf("mission").forGetter(MissionTrip::mission),
            Codec.LONG.optionalFieldOf("return_time", -1L).forGetter(MissionTrip::returnTime),
            BlockPos.CODEC.optionalFieldOf("exit").forGetter(MissionTrip::exit)
    ).apply(instance, MissionTrip::new));

    /**
     * A trip without an exit (reserved, or departed and still walking out).
     *
     * @param board      the Mission Board
     * @param mission    the mission id
     * @param returnTime the world time of the return, {@code -1} while reserved
     */
    public MissionTrip(BlockPos board, Identifier mission, long returnTime) {
        this(board, mission, returnTime, Optional.empty());
    }

    /**
     * @return whether the settler has left for the mission (not only reserved it)
     */
    public boolean departed() {
        return returnTime >= 0;
    }

    /**
     * @return whether the settler has vanished outside the settlement
     */
    public boolean gone() {
        return exit.isPresent();
    }

    /**
     * @param spot where the settler vanished
     * @return this trip with that exit
     */
    public MissionTrip withExit(BlockPos spot) {
        return new MissionTrip(board, mission, returnTime, Optional.of(spot.immutable()));
    }
}
