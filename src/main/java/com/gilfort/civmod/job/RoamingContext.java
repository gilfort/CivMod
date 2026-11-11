package com.gilfort.civmod.job;

import java.util.function.Predicate;

import com.gilfort.civmod.settlement.JobBlockSite;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What a {@link Roaming} move may look at.
 *
 * @param level    the level
 * @param site     the JobBlock the settler works for
 * @param position where the settler is now
 * @param gameTime the game time now
 * @param free     whether another settler leaves a position to this one (not occupied today)
 */
public record RoamingContext(ServerLevel level, JobBlockSite site, BlockPos position, long gameTime, Predicate<BlockPos> free) {
}
