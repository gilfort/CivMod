package com.gilfort.civmod.job;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Fisherman scan (vault {@code Jobs/Fisherman.md}): counts water source blocks in a cylinder around the scan centre
 * (±{@value #HALF_HEIGHT} vertically). Sections without water are skipped via their palette; every 4th block is
 * sampled (2×2 grid in x/z) and extrapolated ×4.
 */
public final class WaterScan implements SectionScan<WaterScan.Result> {

    /** Vertical half height of the scan. */
    public static final int HALF_HEIGHT = 15;
    private static final int SAMPLE_FACTOR = 4;

    /**
     * Result of a scan.
     *
     * @param water estimated number of water source blocks
     */
    public record Result(int water) implements ScanResult {

        @Override
        public List<Identifier> foundBlocks() {
            return List.of();
        }

        @Override
        public boolean isEmpty() {
            return water <= 0;
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
    private int water;

    /**
     * @param center the scan centre
     * @param radius the horizontal radius (10 × JobBlock level)
     */
    public WaterScan(BlockPos center, int radius) {
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
        return new Result(water);
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

    private static boolean isWaterSource(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isSource();
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
        if (section.hasOnlyAir() || !section.maybeHas(WaterScan::isWaterSource)) {
            return;
        }
        int baseX = SectionPos.sectionToBlockCoord(sx);
        int baseY = SectionPos.sectionToBlockCoord(sy);
        int baseZ = SectionPos.sectionToBlockCoord(sz);
        long radiusSquared = (long) radius * radius;
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                long dx = baseX + x - center.getX();
                long dz = baseZ + z - center.getZ();
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                for (int y = 0; y < 16; y++) {
                    if (Math.abs(baseY + y - center.getY()) <= HALF_HEIGHT && isWaterSource(section.getBlockState(x, y, z))) {
                        water += SAMPLE_FACTOR;
                    }
                }
            }
        }
    }
}
