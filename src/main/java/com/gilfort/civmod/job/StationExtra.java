package com.gilfort.civmod.job;

import java.util.List;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One entry of {@code station_extras} in a Job's datapack job file (vault {@code Systems/Producing Jobs.md}): blocks
 * that, standing within {@code radius} blocks of the station of the day, replace the Job's base job factor by
 * {@code job_factor} (the Smith's anvil: 0.3 → 1.2).
 *
 * @param blocks    the matching blocks: block ids and {@code #tags}, written as one string or a list
 * @param radius    how far from the station a block counts, in blocks in every direction (a cube, 1–8)
 * @param jobFactor the base job factor while a matching block stands within the radius
 */
public record StationExtra(List<Either<TagKey<Block>, Block>> blocks, int radius, double jobFactor) {

    /** The largest radius: the cube around the station is read once per day and settler. */
    public static final int MAX_RADIUS = 8;

    private static final Codec<Either<TagKey<Block>, Block>> BLOCK = Codec.either(TagKey.hashedCodec(Registries.BLOCK),
            BuiltInRegistries.BLOCK.byNameCodec());

    /** Datapack codec: {@code { "blocks": id | "#tag" | [...], "radius": int, "job_factor": double }}. */
    public static final Codec<StationExtra> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ExtraCodecs.compactListCodec(BLOCK, BLOCK.listOf(1, Integer.MAX_VALUE)).fieldOf("blocks").forGetter(StationExtra::blocks),
            Codec.intRange(1, MAX_RADIUS).fieldOf("radius").forGetter(StationExtra::radius),
            Codec.doubleRange(0.0, 100.0).fieldOf("job_factor").forGetter(StationExtra::jobFactor)
    ).apply(instance, StationExtra::new));

    /**
     * @param state a block state
     * @return whether the block is one of the entry's blocks or in one of its tags
     */
    public boolean matches(BlockState state) {
        return blocks.stream().anyMatch(entry -> entry.map(state::is, state::is));
    }
}
