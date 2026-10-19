package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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

/**
 * Scan of a {@link com.gilfort.civmod.api.SimpleCollectionProfile} Job (vault {@code Systems/Resource Collection Jobs.md}):
 * counts the blocks of a kind per block type in a cylinder around the scan centre. Processes one chunk section per
 * {@link #step} call, skips unloaded chunks and sections whose palette has none of the blocks; types found fewer times
 * than the minimum count are left out of the result.
 */
public final class BlockCountScan implements SectionScan<BlockCountScan.Result> {

    /**
     * Result of a scan.
     *
     * @param counts block count per block id (only types at or above the minimum count)
     */
    public record Result(Map<Identifier, Integer> counts) implements ScanResult {

        /** Empty result. */
        public static final Result EMPTY = new Result(Map.of());

        /**
         * @return the total number of counted blocks
         */
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public List<Identifier> foundBlocks() {
            return counts.keySet().stream().sorted().toList();
        }
    }

    private final BlockPos center;
    private final int radius;
    private final int halfHeight;
    private final Predicate<BlockState> counted;
    private final int minimumCount;
    private final int minSectionX;
    private final int maxSectionX;
    private final int minSectionY;
    private final int maxSectionY;
    private final int minSectionZ;
    private final int maxSectionZ;
    private int sectionX;
    private int sectionY;
    private int sectionZ;
    private boolean done;
    private final Map<Identifier, Integer> counts = new HashMap<>();

    /**
     * @param center       the scan centre
     * @param radius       the horizontal radius
     * @param halfHeight   how far up and down from the centre
     * @param counted      the blocks to count
     * @param minimumCount how often a type must be found to count
     */
    public BlockCountScan(BlockPos center, int radius, int halfHeight, Predicate<BlockState> counted, int minimumCount) {
        this.center = center.immutable();
        this.radius = radius;
        this.halfHeight = halfHeight;
        this.counted = counted;
        this.minimumCount = minimumCount;
        this.minSectionX = SectionPos.blockToSectionCoord(center.getX() - radius);
        this.maxSectionX = SectionPos.blockToSectionCoord(center.getX() + radius);
        this.minSectionY = SectionPos.blockToSectionCoord(center.getY() - halfHeight);
        this.maxSectionY = SectionPos.blockToSectionCoord(center.getY() + halfHeight);
        this.minSectionZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        this.maxSectionZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        this.sectionX = minSectionX;
        this.sectionY = minSectionY;
        this.sectionZ = minSectionZ;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public Result result() {
        Map<Identifier, Integer> kept = new HashMap<>();
        counts.forEach((type, count) -> {
            if (count >= minimumCount) {
                kept.put(type, count);
            }
        });
        return new Result(Map.copyOf(kept));
    }

    @Override
    public void step(ServerLevel level) {
        if (done) {
            return;
        }
        scanSection(level, sectionX, sectionY, sectionZ);
        if (++sectionY > maxSectionY) {
            sectionY = minSectionY;
            if (++sectionZ > maxSectionZ) {
                sectionZ = minSectionZ;
                if (++sectionX > maxSectionX) {
                    done = true;
                }
            }
        }
    }

    private void scanSection(ServerLevel level, int sx, int sy, int sz) {
        if (!level.hasChunk(sx, sz)) {
            return;
        }
        LevelChunk chunk = level.getChunk(sx, sz);
        int index = level.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSectionsCount()) {
            return;
        }
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir() || !section.maybeHas(counted)) {
            return;
        }
        int baseX = SectionPos.sectionToBlockCoord(sx);
        int baseY = SectionPos.sectionToBlockCoord(sy);
        int baseZ = SectionPos.sectionToBlockCoord(sz);
        long radiusSquared = (long) radius * radius;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                long dx = baseX + x - center.getX();
                long dz = baseZ + z - center.getZ();
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                for (int y = 0; y < 16; y++) {
                    if (Math.abs(baseY + y - center.getY()) > halfHeight) {
                        continue;
                    }
                    BlockState state = section.getBlockState(x, y, z);
                    if (counted.test(state)) {
                        counts.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), 1, Integer::sum);
                    }
                }
            }
        }
    }
}
