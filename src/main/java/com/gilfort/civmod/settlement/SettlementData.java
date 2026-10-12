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

import com.gilfort.civmod.CivMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
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
            Settlement.CODEC.listOf().optionalFieldOf("settlements", List.of()).forGetter(data -> List.copyOf(data.settlements.values()))
    ).apply(instance, SettlementData::new));

    /** Saved data type (one file per dimension). */
    public static final SavedDataType<SettlementData> TYPE = new SavedDataType<>(CivMod.id("settlements"), SettlementData::new, CODEC);

    private int nextId;
    private final Map<Integer, Settlement> settlements = new LinkedHashMap<>();
    private final Map<BlockPos, Settlement> byPos = new HashMap<>();
    private final Long2ObjectMap<Set<BlockPos>> byChunk = new Long2ObjectOpenHashMap<>();
    private int revision;

    /**
     * Creates empty data.
     */
    public SettlementData() {
        this(1, List.of());
    }

    private SettlementData(int nextId, List<Settlement> settlements) {
        this.nextId = nextId;
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

    Settlement create(long gameTime) {
        Settlement settlement = new Settlement(nextId++, Optional.empty(), gameTime, List.of());
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
