package com.gilfort.civmod.item;

import net.minecraft.world.item.Item;

/**
 * A JobBlock Upgrade item (vault {@code Systems/JobBlock.md}): right-clicked on a JobBlock one level below its target
 * level, it raises the block by one level once a worker there has reached the skill cap
 * ({@link com.gilfort.civmod.block.JobBlockUpgrade}). One item per target level 2 to 5, crafted around the Scroll of
 * Assignment.
 */
public class JobBlockUpgradeItem extends Item {

    private final int targetLevel;

    /**
     * @param targetLevel the level a JobBlock reaches with this item (2–5)
     * @param properties  the item properties
     */
    public JobBlockUpgradeItem(int targetLevel, Properties properties) {
        super(properties);
        this.targetLevel = targetLevel;
    }

    /**
     * @return the level a JobBlock reaches with this item
     */
    public int targetLevel() {
        return targetLevel;
    }
}
