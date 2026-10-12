package com.gilfort.civmod.settlement;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * All settlements of one dimension, saved with the dimension.
 * <p>
 * Keeps transient indices by position and by chunk; lookups that need distances iterate all JobBlocks, which is
 * cheap at PoC scale and only happens on place / break.
 */
public final class SettlementData extends SavedData {

    /** Save codec. */
    public static final Codec<SettlementData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("next_id", 1).forGetter(data -> data.nextId),
            Settlement.CODEC.listOf().optionalFieldOf("settlements", List.of()).forGetter(data -> List.copyOf(data.settlements.values())),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).optionalFieldOf("last_founding_day", Map.of())
                    .forGetter(data -> Map.copyOf(data.lastFoundingDay))
    ).apply(instance, SettlementData::new));

    /** Saved data type (one file per dimension). */
    public static final SavedDataType<SettlementData> TYPE = new SavedDataType<>(CivMod.id("settlements"), SettlementData::new, CODEC);

    private int nextId;
    private final Map<Integer, Settlement> settlements = new LinkedHashMap<>();
    private final Map<BlockPos, Settlement> byPos = new HashMap<>();
    private final Long2ObjectMap<Set<BlockPos>> byChunk = new Long2ObjectOpenHashMap<>();
    private final Map<UUID, Long> lastFoundingDay = new HashMap<>();
    private int revision;

    /**
     * Creates empty data.
     */
    public SettlementData() {
        this(1, List.of(), Map.of());
    }

    private SettlementData(int nextId, List<Settlement> settlements, Map<UUID, Long> lastFoundingDay) {
        this.nextId = nextId;
        this.lastFoundingDay.putAll(lastFoundingDay);
        for (Settlement settlement : settlements) {
            this.settlements.put(settlement.id(), settlement);
            settlement.blocks().forEach(block -> index(settlement, block.pos()));
        }
    }

    /**
     * @param level the dimension
     * @return the settlement data of that dimension
     */
    public static SettlementData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * @return all settlements (read-only)
     */
    public Collection<Settlement> settlements() {
        return Collections.unmodifiableCollection(settlements.values());
    }

    /**
     * @param id a settlement id
     * @return the settlement with that id
     */
    public Optional<Settlement> settlement(int id) {
        return Optional.ofNullable(settlements.get(id));
    }

    /**
     * @param pos a position
     * @return the settlement owning a JobBlock at that position
     */
    public Optional<Settlement> settlementAt(BlockPos pos) {
        return Optional.ofNullable(byPos.get(pos));
    }

    /**
     * @param chunk a chunk position
     * @return positions of recorded JobBlocks in that chunk (copy)
     */
    public Set<BlockPos> blocksInChunk(ChunkPos chunk) {
        Set<BlockPos> positions = byChunk.get(chunk.pack());
        return positions == null ? Set.of() : new HashSet<>(positions);
    }

    /**
     * Increments on every change; used to detect when clients need a fresh outline. Not saved.
     *
     * @return the current revision
     */
    public int revision() {
        return revision;
    }

    /**
     * Adds an NPC to a settlement.
     *
     * @param settlement the settlement
     * @param member     the NPC UUID
     */
    public void addMember(Settlement settlement, UUID member) {
        if (settlement.addMember(member)) {
            changed();
        }
    }

    /**
     * Removes an NPC from a settlement.
     *
     * @param settlement the settlement
     * @param member     the NPC UUID
     */
    public void removeMember(Settlement settlement, UUID member) {
        if (settlement.removeMember(member)) {
            changed();
        }
    }

    /**
     * Gives a bed to a settler (it loses any other bed; a previous owner of this bed loses it).
     *
     * @param settlement the settlement
     * @param head       the bed head position
     * @param owner      the settler UUID
     * @return the previous owner of this bed
     */
    public Optional<UUID> assignBed(Settlement settlement, BlockPos head, UUID owner) {
        Optional<UUID> previous = settlement.putBed(head, owner).filter(uuid -> !uuid.equals(owner));
        changed();
        return previous;
    }

    /**
     * Releases a bed.
     *
     * @param settlement the settlement
     * @param head       the bed head position
     * @return the previous owner
     */
    public Optional<UUID> releaseBed(Settlement settlement, BlockPos head) {
        Optional<UUID> previous = settlement.removeBed(head);
        if (previous.isPresent()) {
            changed();
        }
        return previous;
    }

    /**
     * Sets the settlement name chosen by the settlers.
     *
     * @param settlement the settlement
     * @param name       the name
     */
    public void setName(Settlement settlement, String name) {
        settlement.setName(Optional.of(name));
        changed();
    }

    /**
     * Marks the starting settlers of a settlement as arrived.
     *
     * @param settlement the settlement
     */
    public void settlersArrived(Settlement settlement) {
        settlement.setSettlersDue(Settlement.SETTLERS_ARRIVED);
        changed();
    }

    /**
     * @param player a player UUID
     * @return the in-game day on which the player last founded a settlement
     */
    public Optional<Long> lastFoundingDay(UUID player) {
        return Optional.ofNullable(lastFoundingDay.get(player));
    }

    /**
     * Records that a player founded a settlement.
     *
     * @param player the player UUID
     * @param day    the in-game day
     */
    public void recordFounding(UUID player, long day) {
        lastFoundingDay.put(player, day);
        setDirty();
    }

    Settlement create(long gameTime, int color, long settlersDue) {
        Settlement settlement = new Settlement(nextId++, Optional.empty(), gameTime, color, settlersDue, List.of(), List.of());
        settlements.put(settlement.id(), settlement);
        changed();
        return settlement;
    }

    void addBlock(Settlement settlement, SettlementBlock block) {
        settlement.put(block);
        index(settlement, block.pos());
        changed();
    }

    Optional<Settlement> removeBlock(BlockPos pos) {
        Settlement settlement = byPos.remove(pos);
        if (settlement == null) {
            return Optional.empty();
        }
        settlement.remove(pos);
        long chunk = ChunkPos.containing(pos).pack();
        Set<BlockPos> positions = byChunk.get(chunk);
        if (positions != null) {
            positions.remove(pos);
            if (positions.isEmpty()) {
                byChunk.remove(chunk);
            }
        }
        changed();
        return Optional.of(settlement);
    }

    void dissolve(Settlement settlement) {
        settlements.remove(settlement.id());
        changed();
    }

    /**
     * Marks the data as changed (saved and outline revision bumped).
     */
    void changed() {
        revision++;
        setDirty();
    }

    private void index(Settlement settlement, BlockPos pos) {
        byPos.put(pos, settlement);
        byChunk.computeIfAbsent(ChunkPos.containing(pos).pack(), key -> new HashSet<>()).add(pos);
    }
}
