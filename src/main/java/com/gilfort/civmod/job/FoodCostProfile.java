package com.gilfort.civmod.job;

import java.util.List;

import net.minecraft.world.item.Item;

/**
 * A farming Job whose rolls cost food from the collect inventory (the Animal Farmer, vault {@code Jobs/Animal Farmer.md},
 * decisions of 2026-11-15). The {@link WorkRules} and the work loop ask it what is missing; {@link FoodOrders} fetches that
 * food from the Home Clerk like a tool.
 */
interface FoodCostProfile extends DropPoolProfile {

    /**
     * @param context the production context
     * @param tools   the collect inventory
     * @return whether the Job could roll something but has no food for any of it: it shows "No food"
     */
    boolean noFood(Context context, JobDrops.Tools tools);

    /**
     * @param context the production context
     * @param tools   the collect inventory
     * @return for every rollable type whose food the collect inventory lacks: the items that feed it, in registry order
     *         (types with the same foods give one list)
     */
    List<List<Item>> unfed(Context context, JobDrops.Tools tools);
}
