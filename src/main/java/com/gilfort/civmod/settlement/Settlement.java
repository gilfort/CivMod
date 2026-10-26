package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Request;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * A settlement: a chain of JobBlocks in one dimension with its settlers (vault {@code Systems/Settlement.md}).
 */
public final class Settlement {

    /** Value of {@link #settlersDue()} once the starting settlers have arrived. */
    public static final long SETTLERS_ARRIVED = -1L;

    /** Save codec. */
    public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("id").forGetter(Settlement::id),
            Codec.STRING.optionalFieldOf("name").forGetter(Settlement::name),
            Codec.LONG.fieldOf("founded").forGetter(Settlement::foundedAt),
            Codec.INT.optionalFieldOf("color", SettlementColors.DEFAULT).forGetter(Settlement::color),
            Codec.LONG.optionalFieldOf("settlers_due", SETTLERS_ARRIVED).forGetter(Settlement::settlersDue),
            UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(settlement -> List.copyOf(settlement.members)),
            SettlementBlock.CODEC.listOf().fieldOf("blocks").forGetter(settlement -> List.copyOf(settlement.blocks.values())),
            // Saves from before the Clerk may hold "pantries"; they are ignored (vault Systems/Settlement.md, 2026-10-16).
            Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC).optionalFieldOf("beds", Map.of()).forGetter(Settlement::bedsForCodec),
            Request.CODEC.listOf().optionalFieldOf("requests", List.of()).forGetter(Settlement::requests),
            DeliveryTask.CODEC.listOf().optionalFieldOf("deliveries", List.of()).forGetter(Settlement::deliveries)
    ).apply(instance, Settlement::new));

    private final int id;
    private Optional<String> name;
    private final long foundedAt;
    private int color;
    private long settlersDue;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Map<BlockPos, SettlementBlock> blocks = new LinkedHashMap<>();
    private final Map<BlockPos, UUID> beds = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<DeliveryTask> deliveries = new ArrayList<>();
    /** Clerk JobBlock → its reservations (not saved, see {@link Reservations}). */
    private final Map<BlockPos, List<Reservations.Reservation>> reservations = new HashMap<>();
    /** Occupied work place → who occupies it (not saved, see {@link OccupiedPlaces}). */
    private final Map<BlockPos, OccupiedPlaces.Occupation> occupied = new HashMap<>();

    /**
     * @param id          the settlement id (unique per dimension)
     * @param name        the name, empty until the settlers choose one
     * @param foundedAt   the game time of founding
     * @param color       the settlement colour (RGB)
     * @param settlersDue game time at which the starting settlers arrive, {@link #SETTLERS_ARRIVED} once they did
     * @param members     UUIDs of the settlement's NPCs
     * @param blocks      the JobBlocks
     */
    public Settlement(int id, Optional<String> name, long foundedAt, int color, long settlersDue, List<UUID> members,
            List<SettlementBlock> blocks) {
        this(id, name, foundedAt, color, settlersDue, members, blocks, Map.of(), List.of(), List.of());
    }

    private Settlement(int id, Optional<String> name, long foundedAt, int color, long settlersDue, List<UUID> members,
            List<SettlementBlock> blocks, Map<String, UUID> beds, List<Request> requests, List<DeliveryTask> deliveries) {
        this.requests.addAll(requests);
        this.deliveries.addAll(deliveries);
        beds.forEach((key, owner) -> this.beds.put(BlockPos.of(Long.parseLong(key)), owner));
        this.id = id;
        this.name = name;
        this.foundedAt = foundedAt;
        this.color = color;
        this.settlersDue = settlersDue;
        this.members.addAll(members);
        blocks.forEach(block -> this.blocks.put(block.pos(), block));
    }

    /**
     * @return the settlement id
     */
    public int id() {
        return id;
    }

    /**
     * @return the name, empty until the settlers choose one
     */
    public Optional<String> name() {
        return name;
    }

    /**
     * @return the name to show to players, in the settlement colour
     */
    public Component displayName() {
        Component text = name.<Component>map(Component::literal).orElseGet(() -> Component.translatable("civmod.settlement.unnamed"));
        return text.copy().withStyle(style -> style.withColor(TextColor.fromRgb(color)));
    }

    /**
     * @return the game time of founding
     */
    public long foundedAt() {
        return foundedAt;
    }

    /**
     * The single colour of the settlement; every settlement visual must use it (vault {@code Jobs/Administrator.md}).
     *
     * @return the colour as RGB
     */
    public int color() {
        return color;
    }

    /**
     * @return game time at which the starting settlers arrive, {@link #SETTLERS_ARRIVED} once they did
     */
    public long settlersDue() {
        return settlersDue;
    }

    /**
     * @return UUIDs of the settlement's NPCs (read-only)
     */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    /**
     * @return all JobBlocks (read-only)
     */
    public Collection<SettlementBlock> blocks() {
        return Collections.unmodifiableCollection(blocks.values());
    }

    /**
     * @param pos a position
     * @return the JobBlock at that position, if it belongs to this settlement
     */
    public Optional<SettlementBlock> block(BlockPos pos) {
        return Optional.ofNullable(blocks.get(pos));
    }

    /**
     * @return the first connected JobBlock (normally the founding block)
     */
    public Optional<SettlementBlock> anchor() {
        return blocks.values().stream().filter(SettlementBlock::connected).findFirst();
    }

    /**
     * @return whether the settlement has no JobBlocks left
     */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * @return the number of connected JobBlocks
     */
    public long connectedCount() {
        return blocks.values().stream().filter(SettlementBlock::connected).count();
    }

    /**
     * @return bed (head position) → owning settler (read-only)
     */
    public Map<BlockPos, UUID> beds() {
        return Collections.unmodifiableMap(beds);
    }

    /**
     * @param settler a settler UUID
     * @return the bed (head position) owned by the settler
     */
    public Optional<BlockPos> bedOf(UUID settler) {
        return beds.entrySet().stream().filter(entry -> entry.getValue().equals(settler)).map(Map.Entry::getKey).findFirst();
    }

    private Map<String, UUID> bedsForCodec() {
        Map<String, UUID> result = new LinkedHashMap<>();
        beds.forEach((pos, owner) -> result.put(Long.toString(pos.asLong()), owner));
        return result;
    }

    Optional<UUID> putBed(BlockPos head, UUID owner) {
        beds.entrySet().removeIf(entry -> entry.getValue().equals(owner));
        return Optional.ofNullable(beds.put(head.immutable(), owner));
    }

    Optional<UUID> removeBed(BlockPos head) {
        return Optional.ofNullable(beds.remove(head));
    }

    /**
     * @return the settlement's requests, in posting order (read-only; see {@link Requests})
     */
    public List<Request> requests() {
        return Collections.unmodifiableList(requests);
    }

    List<Request> mutableRequests() {
        return requests;
    }

    /**
     * @return the open Delivery tasks, in creation order (read-only; see {@link Deliveries})
     */
    public List<DeliveryTask> deliveries() {
        return Collections.unmodifiableList(deliveries);
    }

    List<DeliveryTask> mutableDeliveries() {
        return deliveries;
    }

    Map<BlockPos, List<Reservations.Reservation>> mutableReservations() {
        return reservations;
    }

    Map<BlockPos, OccupiedPlaces.Occupation> mutableOccupied() {
        return occupied;
    }

    void setName(Optional<String> name) {
        this.name = name;
    }

    void setSettlersDue(long settlersDue) {
        this.settlersDue = settlersDue;
    }

    boolean addMember(UUID member) {
        return members.add(member);
    }

    boolean removeMember(UUID member) {
        return members.remove(member);
    }

    void put(SettlementBlock block) {
        blocks.put(block.pos(), block);
    }

    SettlementBlock remove(BlockPos pos) {
        // The requests of a removed Clerk's rules go with it; other requests delivering to it go to the producer's own
        // drop-off, like requests without a Clerk.
        requests.removeIf(request -> request.rule().isPresent() && request.target().filter(pos::equals).isPresent());
        requests.replaceAll(request -> request.target().filter(pos::equals).isPresent() ? request.withoutTarget() : request);
        // Its pickups and result deliveries go with it (also those to a removed requesting Clerk); a removed Clerk is no
        // Served Clerk any more.
        deliveries.removeIf(task -> task.jobBlock().equals(pos) || task.source().equals(pos)
                || task.result().filter(result -> result.clerk().equals(pos)).isPresent());
        blocks.values().forEach(block -> block.mutableServed().remove(pos));
        // A removed Clerk's reservations go with it; those of removed tasks elsewhere expire on the next read.
        reservations.remove(pos);
        // The work places its settlers occupied are free again.
        occupied.values().removeIf(occupation -> occupation.jobBlock().equals(pos));
        return blocks.remove(pos);
    }
}
