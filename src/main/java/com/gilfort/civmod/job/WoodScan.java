package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.Map;

import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.common.Tags;

/**
 * Lumberjack scan (vault {@code Jobs/Lumberjack.md}): counts logs per block type ({@link #isLog}: stripped logs and wood
 * are left out) and leaves per block type in a cylinder around the primary work place.
 * <p>
 * Processes one chunk section per {@link #step} call, skips sections without logs or leaves (palette pre-filter)
 * and unloaded chunks, and samples every 4th block (2×2 grid in x/z), extrapolating ×4. A sparse area is counted again
 * block by block: on the grid a lone tree reads as 0 or ×4, and the Lumberjack's minimum and speed need its real logs.
 */
public final class WoodScan implements SectionScan<WoodScan.Result> {

    /** Vertical half height of the scan. */
    public static final int HALF_HEIGHT = 10;
    /** Sampling factor (every 4th block). */
    private static final int SAMPLE_FACTOR = 4;
    /** Distance between the sampled columns. */
    private static final int SAMPLE_GRID = 2;

    /**
     * Result of a scan.
     *
     * @param logs   estimated log count per block id
     * @param leaves estimated leaf count per block id
     */
    public record Result(Map<Identifier, Integer> logs, Map<Identifier, Integer> leaves) implements ScanResult {

        /** Empty result. */
        public static final Result EMPTY = new Result(Map.of(), Map.of());

        /**
         * @return the estimated total number of logs
         */
        public int totalLogs() {
            return logs.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public java.util.List<Identifier> foundBlocks() {
            return logs.keySet().stream().sorted().toList();
        }
    }

    private final BlockPos center;
    private final int radius;
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
    /** Whether the second pass runs, which counts every block. */
    private boolean exact;
    private final Map<Identifier, Integer> logs = new HashMap<>();
    private final Map<Identifier, Integer> leaves = new HashMap<>();

    /**
     * @param center the primary work place
     * @param radius the horizontal radius (10 × JobBlock level)
     */
    public WoodScan(BlockPos center, int radius) {
        this.center = center.immutable();
        this.radius = radius;
        this.minSectionX = SectionPos.blockToSectionCoord(center.getX() - radius);
        this.maxSectionX = SectionPos.blockToSectionCoord(center.getX() + radius);
        this.minSectionY = SectionPos.blockToSectionCoord(center.getY() - HALF_HEIGHT);
        this.maxSectionY = SectionPos.blockToSectionCoord(center.getY() + HALF_HEIGHT);
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
        return new Result(Map.copyOf(logs), Map.copyOf(leaves));
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
                    finishPass();
                }
            }
        }
    }

    /**
     * Below this estimate fewer than the full amount of real logs is possible (a sample finds at most every log ×
     * {@value #SAMPLE_FACTOR}), so the pass is repeated exactly.
     */
    private void finishPass() {
        int estimate = logs.values().stream().mapToInt(Integer::intValue).sum();
        if (exact || estimate >= LumberjackOutput.fullLogs() * SAMPLE_FACTOR) {
            done = true;
            return;
        }
        exact = true;
        logs.clear();
        leaves.clear();
        sectionX = minSectionX;
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
        if (section.hasOnlyAir() || !section.maybeHas(WoodScan::isRelevant)) {
            return;
        }
        int baseX = SectionPos.sectionToBlockCoord(sx);
        int baseY = SectionPos.sectionToBlockCoord(sy);
        int baseZ = SectionPos.sectionToBlockCoord(sz);
        long radiusSquared = (long) radius * radius;
        int grid = exact ? 1 : SAMPLE_GRID;
        int factor = exact ? 1 : SAMPLE_FACTOR;
        // Sample on an even grid in world coordinates so neighbouring sections line up.
        for (int x = 0; x < 16; x += grid) {
            for (int z = 0; z < 16; z += grid) {
                int dx = baseX + x - center.getX();
                int dz = baseZ + z - center.getZ();
                if ((long) dx * dx + (long) dz * dz > radiusSquared) {
                    continue;
                }
                for (int y = 0; y < 16; y++) {
                    int worldY = baseY + y;
                    if (Math.abs(worldY - center.getY()) > HALF_HEIGHT) {
                        continue;
                    }
                    BlockState state = section.getBlockState(x, y, z);
                    if (isLog(state)) {
                        logs.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), factor, Integer::sum);
                    } else if (state.is(BlockTags.LEAVES)) {
                        leaves.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), factor, Integer::sum);
                    }
                }
            }
        }
    }

    private static boolean isRelevant(BlockState state) {
        return isLog(state) || state.is(BlockTags.LEAVES);
    }

    /**
     * Stripped logs and stripped wood only come from buildings, so they are no forest (vault
     * {@code Jobs/Lumberjack.md}).
     *
     * @param state a block state
     * @return whether it counts as a log of a tree: {@code #minecraft:logs} without {@code #c:stripped_logs} and
     *         {@code #c:stripped_woods}
     */
    static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS) && !state.is(Tags.Blocks.STRIPPED_LOGS) && !state.is(Tags.Blocks.STRIPPED_WOODS);
    }
}
