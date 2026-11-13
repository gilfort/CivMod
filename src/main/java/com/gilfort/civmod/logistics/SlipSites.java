package com.gilfort.civmod.logistics;

import com.gilfort.civmod.settlement.JobBlockSite;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * What counts as a place a Delivery Slip can take items from or bring them to (vault {@code Jobs/Deliveryman.md}): a
 * Clerk JobBlock (its linked storage) or any block with an item inventory (a chest, a Pantry, a barrel).
 */
public final class SlipSites {

    private SlipSites() {
    }

    /**
     * @param level the level
     * @param pos   a block position, in a loaded chunk
     * @return whether a slip can use the block
     */
    public static boolean isContainer(ServerLevel level, BlockPos pos) {
        return JobBlockSite.blockAt(level, pos).filter(ClerkStorage::isClerk).isPresent()
                || level.getCapability(Capabilities.Item.BLOCK, pos, null) != null;
    }

    /**
     * @param level the level
     * @param pos   a block position
     * @return whether the place is gone: only a loaded chunk can tell, an unloaded one counts as still there
     */
    public static boolean isMissing(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && !isContainer(level, pos);
    }
}
