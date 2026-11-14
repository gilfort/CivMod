package com.gilfort.civmod.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
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
 * <li>{@code tag_result} (optional): {@code {"tag": "#c:flowers/small", "count": 1}}; besides the {@code results}, the
 * outcome gives {@code count} (default 1) of one item of the item tag, drawn when the outcome is drawn: every member
 * except the items the recipe used up is equally likely. A tag with no other member adds nothing. The tag is read at
 * draw time, so items that packs add to it take part.</li>
 * </ul>
 *
 * @param weight    the share of the outcome in the draw (at least 1)
 * @param minLevel  the skill level from which the outcome is drawn (at least 1)
 * @param results   what the outcome gives, empty for "nothing found"
 * @param tagResult one random item of a tag the outcome gives in addition, if it has one
 */
@ApiStatus.Experimental
public record DropOutcome(int weight, int minLevel, List<ItemStackTemplate> results, Optional<TagResult> tagResult) {

    /** The {@code min_level} of an outcome without one. */
    public static final int DEFAULT_MIN_LEVEL = 1;

    /** Datapack codec. */
    public static final Codec<DropOutcome> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(DropOutcome::weight),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("min_level", DEFAULT_MIN_LEVEL).forGetter(DropOutcome::minLevel),
            ItemStackTemplate.CODEC.listOf().fieldOf("results").forGetter(DropOutcome::results),
            TagResult.CODEC.optionalFieldOf("tag_result").forGetter(DropOutcome::tagResult)
    ).apply(instance, DropOutcome::new));

    /** Network codec (recipe viewers). */
    public static final StreamCodec<RegistryFriendlyByteBuf, DropOutcome> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DropOutcome::weight,
            ByteBufCodecs.VAR_INT, DropOutcome::minLevel,
            ItemStackTemplate.STREAM_CODEC.apply(ByteBufCodecs.list()), DropOutcome::results,
            ByteBufCodecs.optional(TagResult.STREAM_CODEC), DropOutcome::tagResult,
            DropOutcome::new);

    /**
     * @param weight   the share of the outcome in the draw (at least 1)
     * @param minLevel the skill level from which the outcome is drawn (at least 1)
     * @param results  what the outcome gives
     */
    public DropOutcome(int weight, int minLevel, List<ItemStackTemplate> results) {
        this(weight, minLevel, results, Optional.empty());
    }

    /**
     * @param weight    the share of the outcome in the draw (at least 1)
     * @param minLevel  the skill level from which the outcome is drawn (at least 1)
     * @param results   what the outcome gives
     * @param tagResult one random item of a tag in addition
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

    /**
     * One random item of an item tag, given in addition to the fixed results of an outcome.
     *
     * @param tag   the item tag
     * @param count how many of the drawn item the outcome gives (at least 1)
     */
    public record TagResult(TagKey<Item> tag, int count) {

        /** Datapack codec. */
        public static final Codec<TagResult> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TagKey.hashedCodec(Registries.ITEM).fieldOf("tag").forGetter(TagResult::tag),
                Codec.intRange(1, 99).optionalFieldOf("count", 1).forGetter(TagResult::count)
        ).apply(instance, TagResult::new));

        /** Network codec (recipe viewers). */
        public static final StreamCodec<RegistryFriendlyByteBuf, TagResult> STREAM_CODEC = StreamCodec.composite(
                TagKey.streamCodec(Registries.ITEM), TagResult::tag,
                ByteBufCodecs.VAR_INT, TagResult::count,
                TagResult::new);

        /**
         * @param excluded the items the recipe used up, which the draw never picks
         * @return the members of the tag the draw can pick, in tag order; empty while the tag is unknown or has no other member
         */
        public List<Item> candidates(Collection<? extends Item> excluded) {
            List<Item> found = new ArrayList<>();
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                if (!excluded.contains(holder.value())) {
                    found.add(holder.value());
                }
            }
            return found;
        }
    }
}
