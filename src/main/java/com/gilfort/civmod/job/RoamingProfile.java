package com.gilfort.civmod.job;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.settlement.JobBlockSite;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A built-in Resource Collection profile whose settler moves around its work place while working (CivMod's Lumberjack
 * and Veggy Farmer); not part of the addon API. The loop asks for the {@link Roaming} of the work place of the day and
 * walks to its stand target.
 */
public interface RoamingProfile extends ResourceCollectionProfile {

    /**
     * @param level    the level
     * @param site     the JobBlock the settler works for
     * @param anchor   the work place of the day
     * @param gameTime the game time now
     * @return how the settler moves around the anchor today, standing at the anchor first
     */
    Roaming roaming(ServerLevel level, JobBlockSite site, BlockPos anchor, long gameTime);
}
