package com.gilfort.civmod.settlement;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * A settlement: a chain of JobBlocks in one dimension (vault {@code Systems/Settlement.md}).
 */
public final class Settlement {

    /** Save codec. */
    public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("id").forGetter(Settlement::id),
            Codec.STRING.optionalFieldOf("name").forGetter(Settlement::name),
            Codec.LONG.fieldOf("founded").forGetter(Settlement::foundedAt),
            SettlementBlock.CODEC.listOf().fieldOf("blocks").forGetter(settlement -> List.copyOf(settlement.blocks.values()))
    ).apply(instance, Settlement::new));

    private final int id;
    private Optional<String> name;
    private final long foundedAt;
    private final Map<BlockPos, SettlementBlock> blocks = new LinkedHashMap<>();

    /**
     * @param id        the settlement id (unique per dimension)
     * @param name      the name, empty until the settlers choose one
     * @param foundedAt the game time of founding
     * @param blocks    the JobBlocks
     */
    public Settlement(int id, Optional<String> name, long foundedAt, List<SettlementBlock> blocks) {
        this.id = id;
        this.name = name;
        this.foundedAt = foundedAt;
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
     * @return the name to show to players
     */
    public Component displayName() {
        return name.<Component>map(Component::literal).orElseGet(() -> Component.translatable("civmod.settlement.unnamed"));
    }

    /**
     * @return the game time of founding
     */
    public long foundedAt() {
        return foundedAt;
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

    void setName(Optional<String> name) {
        this.name = name;
    }

    void put(SettlementBlock block) {
        blocks.put(block.pos(), block);
    }

    SettlementBlock remove(BlockPos pos) {
        return blocks.remove(pos);
    }
}
