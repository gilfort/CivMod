package com.gilfort.civmod.job;

import java.util.List;
import java.util.Map;

import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Scan of the Beekeeper (vault {@code Jobs/Beekeeper.md}): counts the beehives and bee nests holding honey (honey level
 * at least 1) per block type, and how many of them are full (honey level 5). Reads block states only, never changes
 * them; one chunk section per {@link #step}, like {@link BlockCountScan}.
 */
public final class HiveScan implements SectionScan<HiveScan.Result> {

    /**
     * Result of a scan.
     *
     * @param counts hives and nests with honey per block id
     * @param full   how many of them are full (honey level 5)
     */
    public record Result(Map<Identifier, Integer> counts, int full) implements ScanResult {

        /** Empty result. */
        public static final Result EMPTY = new Result(Map.of(), 0);

        /**
         * @return the number of hives and nests with honey
         */
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public List<Identifier> foundBlocks() {
            return counts.keySet().stream().sorted().toList();
        }
    }

    private final BlockCountScan withHoney;
    private final BlockCountScan full;

    /**
     * @param center     the scan centre
     * @param radius     the horizontal radius
     * @param halfHeight how far up and down
     */
    public HiveScan(BlockPos center, int radius, int halfHeight) {
        this.withHoney = new BlockCountScan(center, radius, halfHeight, state -> honey(state) >= 1, 1);
        this.full = new BlockCountScan(center, radius, halfHeight, state -> honey(state) == BeehiveBlock.MAX_HONEY_LEVELS, 1);
    }

    /**
     * @param state a block state
     * @return its honey level if it is a beehive or bee nest, else -1
     */
    public static int honey(BlockState state) {
        return state.is(BlockTags.BEEHIVES) && state.hasProperty(BeehiveBlock.HONEY_LEVEL) ? state.getValue(BeehiveBlock.HONEY_LEVEL) : -1;
    }

    @Override
    public boolean isDone() {
        return withHoney.isDone() && full.isDone();
    }

    @Override
    public void step(ServerLevel level) {
        withHoney.step(level);
        full.step(level);
    }

    @Override
    public Result result() {
        return new Result(withHoney.result().counts(), full.result().total());
    }
}
