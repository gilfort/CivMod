package com.gilfort.civmod.origin;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/**
 * Where a settler comes from. Only the ids and the journey are saved; the text is built from language keys.
 *
 * @param kind      what kind of place it is
 * @param id        the biome type, the mission's place, or {@link #VILLAGE_ID} / {@link #FAR_AWAY_ID}
 * @param direction the compass direction, empty without a real place to look for
 * @param days      the days of walking, 0 without a direction
 */
public record Place(Kind kind, Identifier id, Optional<Compass> direction, int days) {

    /** The id of the village a Villager comes from. */
    public static final Identifier VILLAGE_ID = CivMod.id("village");
    /** The id of "from far away". */
    public static final Identifier FAR_AWAY_ID = CivMod.id("far_away");

    /** Save codec. */
    public static final Codec<Place> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Kind.CODEC.fieldOf("kind").forGetter(Place::kind),
            Identifier.CODEC.fieldOf("id").forGetter(Place::id),
            Compass.CODEC.optionalFieldOf("direction").forGetter(Place::direction),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("days", 0).forGetter(Place::days)
    ).apply(instance, Place::new));

    /** What kind of place it is. */
    public enum Kind implements StringRepresentable {
        /** A real region of the world: the nearest biome of a biome type. */
        BIOME("biome"),
        /** The village a Villager came from. */
        VILLAGE("village"),
        /** No real place was found. */
        FAR_AWAY("far_away"),
        /** The place a mission names. */
        MISSION("mission");

        /** Save codec. */
        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String serializedName;

        Kind(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    /**
     * @param biomeType the biome type
     * @param hit       where its nearest biome lies
     * @return the place
     */
    public static Place biome(Identifier biomeType, PlaceHit hit) {
        return new Place(Kind.BIOME, biomeType, Optional.of(hit.direction()), hit.days());
    }

    /**
     * @param biomeType the biome type
     * @return a place of that biome type without a direction (no settlement to measure from)
     */
    public static Place somewhere(Identifier biomeType) {
        return new Place(Kind.BIOME, biomeType, Optional.empty(), 0);
    }

    /**
     * @return the village a Villager came from
     */
    public static Place village() {
        return new Place(Kind.VILLAGE, VILLAGE_ID, Optional.empty(), 0);
    }

    /**
     * @return "from far away"
     */
    public static Place farAway() {
        return new Place(Kind.FAR_AWAY, FAR_AWAY_ID, Optional.empty(), 0);
    }

    /**
     * @param place the place id from the mission
     * @return that place
     */
    public static Place mission(Identifier place) {
        return new Place(Kind.MISSION, place, Optional.empty(), 0);
    }

    /**
     * @return the ids a turning point's {@code places} condition can match: the place itself, and for a mission's place also the village
     */
    public List<Identifier> conditionIds() {
        return kind == Kind.MISSION ? List.of(id, VILLAGE_ID) : List.of(id);
    }
}
