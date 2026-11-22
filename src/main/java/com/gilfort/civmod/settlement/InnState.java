package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;

/**
 * What a settlement keeps for its inn (vault {@code Systems/NPC Population.md}): the guest beds bound to each Pantry, the visitors
 * who stay and the last arrival check that was run. Immutable; {@link SettlementData#updateInn} swaps it for a changed copy.
 *
 * @param guestBeds the guest beds (head positions) per Pantry, in the order they were bound
 * @param visits    the visits by visitor UUID
 * @param lastSlot  the last arrival check ({@link InnSlots}) that was run, {@link #NO_SLOT} before the first
 */
public record InnState(Map<BlockPos, List<BlockPos>> guestBeds, Map<UUID, Visit> visits, long lastSlot) {

    /** Value of {@link #lastSlot()} before any check ran. */
    public static final long NO_SLOT = Long.MIN_VALUE;

    /** A settlement without an inn. */
    public static final InnState NONE = new InnState(Map.of(), Map.of(), NO_SLOT);

    private record BedList(BlockPos pantry, List<BlockPos> beds) {

        static final Codec<BedList> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pantry").forGetter(BedList::pantry),
                BlockPos.CODEC.listOf().fieldOf("beds").forGetter(BedList::beds)
        ).apply(instance, BedList::new));
    }

    /** Save codec (the fields sit next to the settlement's other fields, see {@link Settlement#CODEC}). */
    public static final MapCodec<InnState> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BedList.CODEC.listOf().optionalFieldOf("guest_beds", List.of()).forGetter(
                    state -> state.guestBeds.entrySet().stream().map(entry -> new BedList(entry.getKey(), entry.getValue())).toList()),
            Visit.CODEC.listOf().optionalFieldOf("visits", List.of()).forGetter(state -> List.copyOf(state.visits.values())),
            Codec.LONG.optionalFieldOf("last_slot", NO_SLOT).forGetter(InnState::lastSlot)
    ).apply(instance, InnState::of));

    /**
     * @param guestBeds the guest beds per Pantry, in binding order
     * @param visits    the visits
     * @param lastSlot  the last arrival check that was run
     */
    public InnState {
        guestBeds = Collections.unmodifiableMap(new LinkedHashMap<>(guestBeds));
        visits = Collections.unmodifiableMap(new LinkedHashMap<>(visits));
    }

    private static InnState of(List<BedList> beds, List<Visit> visits, long lastSlot) {
        Map<BlockPos, List<BlockPos>> byPantry = new LinkedHashMap<>();
        beds.forEach(list -> byPantry.put(list.pantry(), List.copyOf(list.beds())));
        Map<UUID, Visit> byId = new LinkedHashMap<>();
        visits.forEach(visit -> byId.put(visit.id(), visit));
        return new InnState(byPantry, byId, lastSlot);
    }

    /**
     * @param pantry a Pantry position
     * @return its guest beds in binding order
     */
    public List<BlockPos> bedsOf(BlockPos pantry) {
        return guestBeds.getOrDefault(pantry, List.of());
    }

    /**
     * @param bed a bed head position
     * @return whether it is a guest bed of any Pantry
     */
    public boolean isGuestBed(BlockPos bed) {
        return guestBeds.values().stream().anyMatch(beds -> beds.contains(bed));
    }

    /**
     * @param visitor a visitor UUID
     * @return its visit, empty if the settlement has none of it
     */
    public Optional<Visit> visit(UUID visitor) {
        return Optional.ofNullable(visits.get(visitor));
    }

    /**
     * @return the Pantries with at least one guest bed, in the order their first bed was bound
     */
    public List<BlockPos> pantries() {
        return guestBeds.entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    }

    /**
     * @param bed a bed head position
     * @return the visitor sleeping in it
     */
    public Optional<Visit> visitIn(BlockPos bed) {
        return visits.values().stream().filter(visit -> visit.bed().filter(bed::equals).isPresent()).findFirst();
    }

    /**
     * @param pantry a Pantry position
     * @return the visits at that Pantry
     */
    public List<Visit> visitsOf(BlockPos pantry) {
        return visits.values().stream().filter(visit -> visit.pantry().equals(pantry)).toList();
    }

    /**
     * @param pantry a Pantry position
     * @param bed    a bed head position
     * @return this state with the bed bound to the Pantry as its last guest bed
     */
    public InnState withBed(BlockPos pantry, BlockPos bed) {
        Map<BlockPos, List<BlockPos>> beds = new LinkedHashMap<>(guestBeds);
        List<BlockPos> list = new ArrayList<>(bedsOf(pantry));
        list.add(bed.immutable());
        beds.put(pantry.immutable(), List.copyOf(list));
        return new InnState(beds, visits, lastSlot);
    }

    /**
     * Unbinds a guest bed; a visitor who slept in it is left without one.
     *
     * @param bed a bed head position
     * @return this state without that guest bed
     */
    public InnState withoutBed(BlockPos bed) {
        Map<BlockPos, List<BlockPos>> beds = new LinkedHashMap<>();
        guestBeds.forEach((pantry, list) -> beds.put(pantry, list.stream().filter(other -> !other.equals(bed)).toList()));
        Map<UUID, Visit> byVisitor = new LinkedHashMap<>(visits);
        byVisitor.replaceAll((id, visit) -> visit.bed().filter(bed::equals).isPresent() ? visit.sleepingIn(Optional.empty()) : visit);
        return new InnState(beds, byVisitor, lastSlot);
    }

    /**
     * Unbinds every guest bed of a Pantry that is gone; the visitors who slept in them are left without a bed.
     *
     * @param pantry a Pantry position
     * @return this state without that Pantry
     */
    public InnState withoutPantry(BlockPos pantry) {
        InnState result = this;
        for (BlockPos bed : bedsOf(pantry)) {
            result = result.withoutBed(bed);
        }
        Map<BlockPos, List<BlockPos>> beds = new LinkedHashMap<>(result.guestBeds);
        beds.remove(pantry);
        return new InnState(beds, result.visits, lastSlot);
    }

    /**
     * @param visit a visit, new or changed
     * @return this state with it
     */
    public InnState withVisit(Visit visit) {
        Map<UUID, Visit> byVisitor = new LinkedHashMap<>(visits);
        byVisitor.put(visit.id(), visit);
        return new InnState(guestBeds, byVisitor, lastSlot);
    }

    /**
     * @param visitor a visitor UUID
     * @return this state without its visit, which frees its guest bed
     */
    public InnState withoutVisit(UUID visitor) {
        Map<UUID, Visit> byVisitor = new LinkedHashMap<>(visits);
        byVisitor.remove(visitor);
        return new InnState(guestBeds, byVisitor, lastSlot);
    }

    /**
     * @param slot the arrival check that was run last
     * @return this state with it
     */
    public InnState withLastSlot(long slot) {
        return new InnState(guestBeds, visits, slot);
    }
}
