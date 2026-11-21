package com.gilfort.civmod.origin;

import java.util.List;

import com.gilfort.civmod.job.BiomeBonuses;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/**
 * One entry of an origin table in a datapack ({@code data/<namespace>/civmod_origin_tables/<table>.json}). Each slot reads only
 * the fields it needs; the text of an entry is the language key built from its id ({@link OriginText#key}).
 *
 * @param id       the entry id, also its language key
 * @param weight   the draw weight
 * @param jobs     upbringing: the one or two Base Jobs it favours; trade: the Base Jobs it fits; otherwise empty
 * @param places   turning point: the places it fits (biome type ids, {@link Place#VILLAGE_ID}, {@link Place#FAR_AWAY_ID} or a mission's
 *                 place); empty fits every place
 * @param channels reason: the channels it is told for ({@link Channel#key()})
 * @param biomes   place: the biomes of this biome type, as ids and {@code #tags}
 */
public record OriginEntry(Identifier id, int weight, List<Identifier> jobs, List<Identifier> places, List<String> channels,
        List<ExtraCodecs.TagOrElementLocation> biomes) {

    private static final int MAX_WEIGHT = 1000;

    /** Data codec. */
    public static final Codec<OriginEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("id").forGetter(OriginEntry::id),
            Codec.intRange(1, MAX_WEIGHT).optionalFieldOf("weight", 1).forGetter(OriginEntry::weight),
            Identifier.CODEC.listOf().optionalFieldOf("jobs", List.of()).forGetter(OriginEntry::jobs),
            Identifier.CODEC.listOf().optionalFieldOf("places", List.of()).forGetter(OriginEntry::places),
            Codec.STRING.listOf().optionalFieldOf("channels", List.of()).forGetter(OriginEntry::channels),
            BiomeBonuses.IDS_OR_TAGS.optionalFieldOf("biomes", List.of()).forGetter(OriginEntry::biomes)
    ).apply(instance, OriginEntry::new));

    /**
     * @param other the Base Jobs of an upbringing or a trade
     * @return whether this entry and the other share a Base Job
     */
    boolean sharesJobWith(OriginEntry other) {
        return jobs.stream().anyMatch(other.jobs::contains);
    }
}
