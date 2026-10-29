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
 * counts the blocks of a kind per block type and per counted state in a cylinder around the scan centre. Processes one chunk section per
 * {@link #step} call, skips unloaded chunks and sections whose palette has none of the blocks; types found fewer times
 * than the minimum count are left out of the result.
 */
public final class BlockCountScan implements SectionScan<BlockCountScan.Result> {

    /**
     * Result of a scan.
     *
     * @param counts block count per block id (only types at or above the minimum count)
     * @param states block count per counted state of those types (a roll uses the loot of the state it picks, e.g. an
     *               age-2 or an age-3 berry bush)
     */
    public record Result(Map<Identifier, Integer> counts, Map<BlockState, Integer> states) implements ScanResult {

        /** Empty result. */
        public static final Result EMPTY = new Result(Map.of(), Map.of());

        /**
         * A result with every block of a type in its rolled state ({@link PlantStates#rolled(net.minecraft.world.level.block.Block)}).
         *
         * @param counts block count per block id
         */
        public Result(Map<Identifier, Integer> counts) {
            this(counts, byRolledState(counts));
        }

        private static Map<BlockState, Integer> byRolledState(Map<Identifier, Integer> counts) {
            Map<BlockState, Integer> states = new HashMap<>();
            counts.forEach((type, count) -> states.merge(PlantStates.rolled(BuiltInRegistries.BLOCK.getValue(type)), count, Integer::sum));
            return Map.copyOf(states);
        }

        /**
         * @param states block count per counted state
         * @return the result with the counts per block id summed from the states
         */
        public static Result ofStates(Map<BlockState, Integer> states) {
            Map<Identifier, Integer> counts = new HashMap<>();
            states.forEach((state, count) -> counts.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), count, Integer::sum));
            return new Result(Map.copyOf(counts), Map.copyOf(states));
        }

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
    private final Map<BlockState, Integer> counts = new HashMap<>();

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
        Result all = Result.ofStates(counts);
        if (minimumCount <= 1) {
            return all;
        }
        Map<BlockState, Integer> kept = new HashMap<>();
        counts.forEach((state, count) -> {
            if (all.counts().get(BuiltInRegistries.BLOCK.getKey(state.getBlock())) >= minimumCount) {
                kept.put(state, count);
            }
        });
        return Result.ofStates(kept);
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
                        counts.merge(state, 1, Integer::sum);
                    }
                }
            }
        }
    }
}
