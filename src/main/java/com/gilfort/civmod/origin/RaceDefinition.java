package com.gilfort.civmod.origin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * What a race draws its settlers' origins from, resolved from the race file, the tables it names and its first-name lists
 * ({@link OriginData}).
 *
 * @param id             the race id
 * @param firstNames     the first-name lists by name culture
 * @param surnames       the surname rules in the order the race lists them
 * @param places         the place table: one entry per biome type, with the biomes it covers
 * @param upbringings    the upbringing table
 * @param turningPoints  the turning point table
 * @param reasons        the reason table
 * @param surnameEntries the entries of the tables that the surname rules name (trades, traits, clans)
 */
public record RaceDefinition(Identifier id, Map<String, NameList> firstNames, List<SurnameRule> surnames, List<OriginEntry> places,
        List<OriginEntry> upbringings, List<OriginEntry> turningPoints, List<OriginEntry> reasons,
        Map<SurnameType, List<OriginEntry>> surnameEntries) {

    /**
     * First names of one name culture.
     *
     * @param male    names for men
     * @param female  names for women
     * @param unisex  names for both
     */
    public record NameList(List<String> male, List<String> female, List<String> unisex) {

        /** Data codec. */
        public static final Codec<NameList> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("male", List.of()).forGetter(NameList::male),
                Codec.STRING.listOf().optionalFieldOf("female", List.of()).forGetter(NameList::female),
                Codec.STRING.listOf().optionalFieldOf("unisex", List.of()).forGetter(NameList::unisex)
        ).apply(instance, NameList::new));

        /** No names. */
        public static final NameList EMPTY = new NameList(List.of(), List.of(), List.of());

        /**
         * @param gender a gender
         * @return the names a settler of that gender may get: its own list and the unisex names
         */
        public List<String> pool(Gender gender) {
            return java.util.stream.Stream.concat(gender == Gender.MALE ? male.stream() : female.stream(), unisex.stream()).toList();
        }
    }

    /**
     * One surname rule of a race.
     *
     * @param type   the rule type
     * @param weight the draw weight, 0 switches the type off
     * @param table  the table with the trades, traits or clans, for the types that need one
     */
    public record SurnameRule(SurnameType type, int weight, Optional<Identifier> table) {

        private static final int MAX_WEIGHT = 1000;

        /** Data codec. */
        public static final Codec<SurnameRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                SurnameType.CODEC.fieldOf("type").forGetter(SurnameRule::type),
                Codec.intRange(0, MAX_WEIGHT).fieldOf("weight").forGetter(SurnameRule::weight),
                Identifier.CODEC.optionalFieldOf("table").forGetter(SurnameRule::table)
        ).apply(instance, SurnameRule::new));
    }

    /**
     * @param biomeType a biome type id
     * @return that entry of the place table
     */
    Optional<OriginEntry> place(Identifier biomeType) {
        return places.stream().filter(entry -> entry.id().equals(biomeType)).findFirst();
    }

    /**
     * @param id an upbringing id
     * @return that entry of the upbringing table
     */
    Optional<OriginEntry> upbringing(Identifier id) {
        return upbringings.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }
}
