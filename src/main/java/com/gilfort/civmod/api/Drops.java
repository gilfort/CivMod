package com.gilfort.civmod.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * The {@code drops} of an innate recipe (vault {@code Systems/Producing Jobs.md}): weighted outcomes, one of which is
 * drawn per execution. They are either written into the recipe or named as a shared pool by id; the pool files of all
 * packs with that id are merged, so a pack adds outcomes to the pool of another pack.
 * <p>
 * JSON, in a recipe:
 *
 * <pre>{@code
 * "drops": "civmod:sifter"
 * "drops": [ { "weight": 3, "results": [] }, { "weight": 1, "results": [ { "id": "minecraft:flint" } ] } ]
 * }</pre>
 *
 * A pool is the file {@code data/<namespace>/civmod_drop_pools/<name>.json}, id {@code <namespace>:<name>}:
 *
 * <pre>{@code
 * { "outcomes": [ { "weight": 200, "results": [ { "id": "minecraft:bone_meal" } ] } ] }
 * }</pre>
 *
 * @param pool     the shared pool the outcomes come from, empty if they are written into the recipe
 * @param outcomes the outcomes written into the recipe, empty if a pool is named
 */
@ApiStatus.Experimental
public record Drops(Optional<Identifier> pool, List<DropOutcome> outcomes) {

    /** Datapack codec: a pool id, or the list of outcomes. */
    public static final Codec<Drops> CODEC = Codec.either(Identifier.CODEC, DropOutcome.CODEC.listOf(1, Integer.MAX_VALUE))
            .xmap(either -> either.map(Drops::ofPool, Drops::of),
                    drops -> drops.pool.<Either<Identifier, List<DropOutcome>>>map(Either::left).orElseGet(() -> Either.right(drops.outcomes)));

    /** Network codec (recipe viewers). */
    public static final StreamCodec<RegistryFriendlyByteBuf, Drops> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), Drops::pool,
            DropOutcome.STREAM_CODEC.apply(ByteBufCodecs.list()), Drops::outcomes,
            Drops::new);

    /**
     * @param pool     the shared pool, if one is named
     * @param outcomes the outcomes written into the recipe
     */
    public Drops {
        outcomes = List.copyOf(outcomes);
        if (pool.isPresent() && !outcomes.isEmpty()) {
            throw new IllegalArgumentException("drops name a pool or list outcomes, not both: " + pool.get());
        }
    }

    /**
     * @param pool a pool id
     * @return drops that name the pool
     */
    public static Drops ofPool(Identifier pool) {
        return new Drops(Optional.of(pool), List.of());
    }

    /**
     * @param outcomes outcomes written into the recipe
     * @return drops that list them
     */
    public static Drops of(List<DropOutcome> outcomes) {
        return new Drops(Optional.empty(), outcomes);
    }

    /**
     * @param pools looks up the merged outcomes of a pool id (an empty list for a pool nobody ships)
     * @return the outcomes to draw from: the pool's, else the recipe's own
     */
    public List<DropOutcome> resolve(Function<Identifier, List<DropOutcome>> pools) {
        return pool.map(pools).orElse(outcomes);
    }
}
