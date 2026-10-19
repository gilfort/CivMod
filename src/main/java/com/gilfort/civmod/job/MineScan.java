package com.gilfort.civmod.job;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Miner scan (vault {@code Jobs/Miner.md}): which stone and ore types exist in a cylinder around the scan centre,
 * from the bottom to the top of the world. Every type counts once, so only the block palettes of the chunk sections
 * are read; single blocks are only read for sections whose palette lists every block (very many types).
 */
public final class MineScan implements SectionScan<MineScan.Result> {

    /**
     * Result of a scan.
     *
     * @param stones stone types found
     * @param ores   ore types found
     */
    public record Result(Set<Identifier> stones, Set<Identifier> ores) implements ScanResult {

        /** Empty result. */
        public static final Result EMPTY = new Result(Set.of(), Set.of());

        @Override
        public List<Identifier> foundBlocks() {
            return Stream.concat(stones.stream().sorted(), ores.stream().sorted()).toList();
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
    private final Set<Identifier> stones = new HashSet<>();
    private final Set<Identifier> ores = new HashSet<>();

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
        return new Result(Set.copyOf(stones), Set.copyOf(ores));
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
        // The predicate never matches, so every palette entry is visited; a global palette answers without asking.
        boolean[] visited = new boolean[1];
        boolean maybe = section.maybeHas(state -> {
            visited[0] = true;
            collect(state);
            return false;
        });
        if (maybe && !visited[0]) {
            readBlocks(section, sx, sz);
        }
    }

    private boolean intersects(int sx, int sz) {
        int nearestX = Math.clamp(center.getX(), SectionPos.sectionToBlockCoord(sx), SectionPos.sectionToBlockCoord(sx) + 15);
        int nearestZ = Math.clamp(center.getZ(), SectionPos.sectionToBlockCoord(sz), SectionPos.sectionToBlockCoord(sz) + 15);
        long dx = nearestX - center.getX();
        long dz = nearestZ - center.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private void readBlocks(LevelChunkSection section, int sx, int sz) {
        long radiusSquared = (long) radius * radius;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                long dx = SectionPos.sectionToBlockCoord(sx) + x - center.getX();
                long dz = SectionPos.sectionToBlockCoord(sz) + z - center.getZ();
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                for (int y = 0; y < 16; y++) {
                    collect(section.getBlockState(x, y, z));
                }
            }
        }
    }

    private void collect(BlockState state) {
        if (state.is(Tags.Blocks.ORES)) {
            ores.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        } else if (state.is(Tags.Blocks.STONES)) {
            stones.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
    }
}
