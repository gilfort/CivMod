package com.gilfort.civmod.settlement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.origin.PlaceHit;
import com.gilfort.civmod.origin.Rescuer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * What a settlement keeps for the origins of its settlers (vault {@code Systems/Settler Origin.md}): who founded it and where the
 * nearest biome of each biome type of its race lies. The lookup runs once after the founding, one biome type per tick
 * ({@code OriginPlaces}); arrivals only read the result.
 *
 * @param founder who placed the Center Block, empty if a command, a structure or a dispenser did
 * @param places  the nearest biome per biome type; a type without a hit has no entry
 * @param todo    the biome types the lookup still has to search; empty before the lookup starts, an empty list once it is done
 */
public record SettlementOrigins(Optional<Rescuer> founder, Map<Identifier, PlaceHit> places, Optional<List<Identifier>> todo) {

    /** A settlement whose lookup has not started and whose founder is unknown. */
    public static final SettlementOrigins NONE = new SettlementOrigins(Optional.empty(), Map.of(), Optional.empty());

    /** Save codec. */
    public static final Codec<SettlementOrigins> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Rescuer.CODEC.optionalFieldOf("founder").forGetter(SettlementOrigins::founder),
            Codec.unboundedMap(Identifier.CODEC, PlaceHit.CODEC).optionalFieldOf("places", Map.of()).forGetter(SettlementOrigins::places),
            Identifier.CODEC.listOf().optionalFieldOf("todo").forGetter(SettlementOrigins::todo)
    ).apply(instance, SettlementOrigins::new));

    /**
     * @return whether the lookup has searched every biome type of the race
     */
    public boolean lookupDone() {
        return todo.isPresent() && todo.get().isEmpty();
    }

    /**
     * @param newFounder who founded the settlement
     * @return these origins with that founder
     */
    public SettlementOrigins withFounder(Rescuer newFounder) {
        return new SettlementOrigins(Optional.of(newFounder), places, todo);
    }

    /**
     * @param biomeTypes the biome types to search, in order
     * @return these origins with a lookup that is about to start
     */
    public SettlementOrigins withLookup(List<Identifier> biomeTypes) {
        return new SettlementOrigins(founder, places, Optional.of(List.copyOf(biomeTypes)));
    }

    /**
     * Records the result of the search for the first biome type in the list and takes the type off it.
     *
     * @param hit where the nearest biome lies, empty if there is none within the radius
     * @return these origins one biome type further
     */
    public SettlementOrigins searched(Optional<PlaceHit> hit) {
        List<Identifier> remaining = todo.orElseThrow(() -> new IllegalStateException("No lookup is running"));
        if (remaining.isEmpty()) {
            throw new IllegalStateException("The lookup is already done");
        }
        Map<Identifier, PlaceHit> found = new LinkedHashMap<>(places);
        hit.ifPresent(value -> found.put(remaining.get(0), value));
        return new SettlementOrigins(founder, Map.copyOf(found), Optional.of(List.copyOf(remaining.subList(1, remaining.size()))));
    }
}
