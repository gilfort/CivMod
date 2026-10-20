package com.gilfort.civmod.logistics;

import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * Which producers of a settlement can make an item (vault {@code Systems/Producing Jobs.md}). Decides the request
 * status "No Craftsperson can make this": a settler of a JobBlock whose Producing Job's library has a checked pattern
 * for the item that it can use.
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
        return settlement.blocks().stream().anyMatch(block -> block.assignees().stream()
                .anyMatch(assignee -> Patterns.hasLibrary(block, assignee.job())
                        && Patterns.usable(level, block, assignee.job()).stream().anyMatch(pattern -> pattern.output() == item)));
    }
}
