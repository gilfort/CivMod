package com.gilfort.civmod.origin;

/**
 * A way a settler comes in (vault {@code Systems/Settler Origin.md}). The key is what the reasons of a race name in their data
 * ({@code channels}), so a later channel such as a visitor needs a key and a place rule, not a change to the tables.
 *
 * @param key           the key reason entries list in their {@code channels}
 * @param placeRule     where the place of the settler comes from
 * @param founderRescues whether the founder of the settlement is recorded as the one who took the settler in
 */
public record Channel(String key, PlaceRule placeRule, boolean founderRescues) {

    /** Starting settlers: survivors from a region near the settlement. */
    public static final Channel STARTER = new Channel("starter", PlaceRule.NEARBY, true);
    /** Pilgrims of the Priest's prayer: a biome type of their race. */
    public static final Channel PILGRIM = new Channel("pilgrim", PlaceRule.ANY_HIT, true);
    /** A converted Villager: the village at the spot of the conversion. */
    public static final Channel VILLAGER = new Channel("villager", PlaceRule.VILLAGE, true);
    /** A converted Villager a mission brought: the place the mission names. */
    public static final Channel MISSION = new Channel("mission", PlaceRule.MISSION, true);
    /** A settler from a spawn egg: a biome type of its race, nobody took it in. */
    public static final Channel EGG = new Channel("egg", PlaceRule.ANY_TYPE, false);
    /** A visitor of an inn: a biome type of its race that the settlement found, nobody took it in. */
    public static final Channel VISITOR = new Channel("visitor", PlaceRule.ANY_HIT, false);

    /** Where the place of a settler comes from. */
    public enum PlaceRule {
        /** A real region near the settlement: the nearer half of the biome types that have a hit. */
        NEARBY,
        /** Any biome type that has a hit. */
        ANY_HIT,
        /** Any biome type of the race, without a direction (there is no settlement to measure from). */
        ANY_TYPE,
        /** The village at the spot of the conversion. */
        VILLAGE,
        /** The place a mission names. */
        MISSION
    }
}
