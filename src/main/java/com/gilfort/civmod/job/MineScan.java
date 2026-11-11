package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.common.Tags;

/**
 * Miner scan (vault {@code Jobs/Miner.md}): how many blocks of each stone and ore type lie in a cylinder around the
 * scan centre, from the bottom to the top of the world. It reads the packed block storage of each chunk section in one
 * pass ({@link net.minecraft.world.level.chunk.PalettedContainer#count}), so no block is looked up on its own, a
 * section of one block type costs a single step, and a single ore block is never missed. A section that the radius
 * touches counts whole, also the part outside the radius.
 */
public final class MineScan implements SectionScan<MineScan.Result> {

    /**
     * Result of a scan.
     *
     * @param stones block count per stone type found
     * @param ores   block count per ore type found
     */
    public record Result(Map<Identifier, Integer> stones, Map<Identifier, Integer> ores) implements ScanResult {

        /** Empty result. */
        public static final Result EMPTY = new Result(Map.of(), Map.of());

        @Override
        public List<Identifier> foundBlocks() {
            return Stream.concat(stones.keySet().stream().sorted(), ores.keySet().stream().sorted()).toList();
        }
    }

    private final BlockPos center;
    private final int radius;
    private final int minSectionX;
    private final int maxSectionX;
    private final int minSectionZ;
    private final int maxSectionZ;
    private int sectionX;
    private int sectionY = Integer.MIN_VALUE;
    private int sectionZ;
    private boolean done;
    private final Map<Identifier, Integer> stones = new HashMap<>();
    private final Map<Identifier, Integer> ores = new HashMap<>();

    /**
     * @param center the scan centre (primary work place or JobBlock)
     * @param radius the horizontal radius (10 × JobBlock level)
     */
    public MineScan(BlockPos center, int radius) {
        this.center = center.immutable();
        this.radius = radius;
        this.minSectionX = SectionPos.blockToSectionCoord(center.getX() - radius);
        this.maxSectionX = SectionPos.blockToSectionCoord(center.getX() + radius);
        this.minSectionZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        this.maxSectionZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        this.sectionX = minSectionX;
        this.sectionZ = minSectionZ;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public Result result() {
        return new Result(Map.copyOf(stones), Map.copyOf(ores));
    }

    @Override
    public void step(ServerLevel level) {
        if (done) {
            return;
        }
        if (sectionY == Integer.MIN_VALUE) {
            sectionY = level.getMinSectionY();
        }
        scanSection(level, sectionX, sectionY, sectionZ);
        if (++sectionY > level.getMaxSectionY()) {
            sectionY = level.getMinSectionY();
            if (++sectionZ > maxSectionZ) {
                sectionZ = minSectionZ;
                if (++sectionX > maxSectionX) {
                    done = true;
                }
            }
        }
    }

    private void scanSection(ServerLevel level, int sx, int sy, int sz) {
        if (!level.hasChunk(sx, sz) || !intersects(sx, sz)) {
            return;
        }
        LevelChunk chunk = level.getChunk(sx, sz);
        int index = level.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSectionsCount()) {
            return;
        }
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir()) {
            return;
        }
        section.getStates().count(this::collect);
    }

    private boolean intersects(int sx, int sz) {
        int nearestX = Math.clamp(center.getX(), SectionPos.sectionToBlockCoord(sx), SectionPos.sectionToBlockCoord(sx) + 15);
        int nearestZ = Math.clamp(center.getZ(), SectionPos.sectionToBlockCoord(sz), SectionPos.sectionToBlockCoord(sz) + 15);
        long dx = nearestX - center.getX();
        long dz = nearestZ - center.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private void collect(BlockState state, int count) {
        if (state.is(Tags.Blocks.ORES)) {
            ores.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), count, Integer::sum);
        } else if (state.is(Tags.Blocks.STONES)) {
            stones.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), count, Integer::sum);
        }
    }
}
