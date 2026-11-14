package com.gilfort.civmod.api;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * One possible result of a {@code drops} recipe (vault {@code Systems/Producing Jobs.md}): a weight, the skill level it
 * opens at and the stacks it gives. Each execution of the recipe draws one outcome among those the settler's skill
 * allows; the chance of an outcome is its weight divided by the total weight of those outcomes.
 * <p>
 * JSON, as an entry of a recipe's {@code drops} list or of a pool file's {@code outcomes} list:
 *
 * <pre>{@code
 * { "weight": 100, "min_level": 21, "results": [ { "id": "minecraft:redstone", "count": 2 } ] }
 * }</pre>
 *
 * <ul>
 * <li>{@code weight}: at least 1; the share of this outcome in the draw.</li>
 * <li>{@code min_level} (optional, default 1): the settler's skill level in the Job from which the outcome is drawn.
 * An outcome stays in the draw at every higher level.</li>
 * <li>{@code results}: the stacks the outcome gives; the list may be empty (the settler finds nothing) or hold several
 * stacks.</li>
 * </ul>
 *
 * @param weight   the share of the outcome in the draw (at least 1)
 * @param minLevel the skill level from which the outcome is drawn (at least 1)
 * @param results  what the outcome gives, empty for "nothing found"
 */
@ApiStatus.Experimental
public record DropOutcome(int weight, int minLevel, List<ItemStackTemplate> results) {

    /** The {@code min_level} of an outcome without one. */
    public static final int DEFAULT_MIN_LEVEL = 1;

    /** Datapack codec. */
    public static final Codec<DropOutcome> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(DropOutcome::weight),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("min_level", DEFAULT_MIN_LEVEL).forGetter(DropOutcome::minLevel),
            ItemStackTemplate.CODEC.listOf().fieldOf("results").forGetter(DropOutcome::results)
    ).apply(instance, DropOutcome::new));

    /** Network codec (recipe viewers). */
    public static final StreamCodec<RegistryFriendlyByteBuf, DropOutcome> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DropOutcome::weight,
            ByteBufCodecs.VAR_INT, DropOutcome::minLevel,
            ItemStackTemplate.STREAM_CODEC.apply(ByteBufCodecs.list()), DropOutcome::results,
            DropOutcome::new);

    /**
     * @param weight   the share of the outcome in the draw (at least 1)
     * @param minLevel the skill level from which the outcome is drawn (at least 1)
     * @param results  what the outcome gives
     */
    public DropOutcome {
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be at least 1: " + weight);
        }
        if (minLevel < 1) {
            throw new IllegalArgumentException("min_level must be at least 1: " + minLevel);
        }
        results = List.copyOf(results);
    }
}
