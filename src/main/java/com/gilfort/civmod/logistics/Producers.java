package com.gilfort.civmod.logistics;

import com.gilfort.civmod.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * Which producers of a settlement can make an item (vault {@code Systems/Producing Jobs.md}). Decides the request
 * status "No Craftsperson can make this". There is no producer yet; the Craftsperson (#57) answers here from the
 * checked patterns of its JobBlocks.
 */
public final class Producers {

    private Producers() {
    }

    /**
     * @param level      the level
     * @param settlement the settlement
     * @param item       an item
     * @return whether a producer of the settlement can make it
     */
    public static boolean canMake(ServerLevel level, Settlement settlement, Item item) {
        return false;
    }
}
