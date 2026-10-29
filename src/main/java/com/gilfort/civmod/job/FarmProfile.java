package com.gilfort.civmod.job;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ResourceCollectionProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What the farming Specializations of the Gatherer with their own scan share (vault {@code Jobs/Veggy Farmer.md},
 * {@code Jobs/Animal Farmer.md}, {@code Jobs/Beekeeper.md}): the work place is found within 20 blocks of the JobBlock,
 * the scan centres on the work place of the day, reaches 5 × JobBlock level and runs every day, and an empty scan
 * stops the Job ("No resources").
 */
abstract class FarmProfile implements ResourceCollectionProfile {

    /** How far along x and z the work place search reaches from the JobBlock. */
    static final int AUTO_SEARCH_HORIZONTAL = 20;
    /** The scan radius per JobBlock level. */
    static final int RADIUS_PER_LEVEL = 5;

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
        return autoSearch(level, jobBlock, pos -> true);
    }

    /** The work place of the day (the scarecrow, hay bale or hive). */
    @Override
    public BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPlace;
    }

    @Override
    public int scanRadius(int jobBlockLevel) {
        return RADIUS_PER_LEVEL * jobBlockLevel;
    }

    @Override
    public boolean rescanDaily() {
        return true;
    }

    @Override
    public boolean needsResources() {
        return true;
    }
}
