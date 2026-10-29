package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The automatic work place search of Resource Collection Jobs: the nearest (or every) matching block in a box around
 * the JobBlock, and whether a found block is exposed (the Miner's stone face, the Animal Farmer's hay bale, a builder
 * Job's block).
 * Unloaded chunks and chunk sections whose palette has no matching block are skipped.
 */
public final class BlockSearch {

    private BlockSearch() {
    }

    /**
     * @param level the level
     * @param pos   a block
     * @return whether a settler can stand next to or on top of it (not buried in a wall or floor)
     */
    public static boolean isExposed(ServerLevel level, BlockPos pos) {
        if (SettlerWork.canStand(level, pos.above())) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (level.isLoaded(side) && SettlerWork.canStand(level, side)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param level      the level
     * @param center     the centre of the search (the JobBlock)
     * @param horizontal how far along x and z
     * @param vertical   how far up and down
     * @param matches    the blocks searched for
     * @return the nearest matching block (by straight distance), null if there is none
     */
    public static @Nullable BlockPos nearest(ServerLevel level, BlockPos center, int horizontal, int vertical, Predicate<BlockState> matches) {
        return nearest(level, center, horizontal, vertical, matches, pos -> true);
    }

    /**
     * @param level      the level
     * @param center     the centre of the search (the JobBlock)
     * @param horizontal how far along x and z
     * @param vertical   how far up and down
     * @param matches    the blocks searched for
     * @param usable     whether a matching block can be a work place (checked only for blocks nearer than the best so far)
     * @return the nearest usable matching block (by straight distance), null if there is none
     */
    public static @Nullable BlockPos nearest(ServerLevel level, BlockPos center, int horizontal, int vertical, Predicate<BlockState> matches,
            Predicate<BlockPos> usable) {
        BlockPos[] best = new BlockPos[1];
        double[] bestDistance = {Double.MAX_VALUE};
        visit(level, center, horizontal, vertical, matches, pos -> {
            double distance = pos.distSqr(center);
            if (distance < bestDistance[0] && usable.test(pos)) {
                bestDistance[0] = distance;
                best[0] = pos;
            }
        });
        return best[0];
    }

    /**
     * @param level      the level
     * @param center     the centre of the search (the JobBlock)
     * @param horizontal how far along x and z
     * @param vertical   how far up and down
     * @param matches    the blocks searched for
     * @return every matching block in the box, in no particular order
     */
    public static List<BlockPos> all(ServerLevel level, BlockPos center, int horizontal, int vertical, Predicate<BlockState> matches) {
        List<BlockPos> found = new ArrayList<>();
        visit(level, center, horizontal, vertical, matches, found::add);
        return found;
    }

    private static void visit(ServerLevel level, BlockPos center, int horizontal, int vertical, Predicate<BlockState> matches, Consumer<BlockPos> found) {
        for (int sx = SectionPos.blockToSectionCoord(center.getX() - horizontal); sx <= SectionPos.blockToSectionCoord(center.getX() + horizontal); sx++) {
            for (int sz = SectionPos.blockToSectionCoord(center.getZ() - horizontal); sz <= SectionPos.blockToSectionCoord(center.getZ() + horizontal); sz++) {
                if (!level.hasChunk(sx, sz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(sx, sz);
                for (int sy = SectionPos.blockToSectionCoord(center.getY() - vertical); sy <= SectionPos.blockToSectionCoord(center.getY() + vertical); sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= chunk.getSectionsCount()) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(index);
                    if (section.hasOnlyAir() || !section.maybeHas(matches)) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (!matches.test(section.getBlockState(x, y, z))) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(SectionPos.sectionToBlockCoord(sx) + x, SectionPos.sectionToBlockCoord(sy) + y,
                                        SectionPos.sectionToBlockCoord(sz) + z);
                                if (Math.abs(pos.getX() - center.getX()) <= horizontal && Math.abs(pos.getZ() - center.getZ()) <= horizontal
                                        && Math.abs(pos.getY() - center.getY()) <= vertical) {
                                    found.accept(pos);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
