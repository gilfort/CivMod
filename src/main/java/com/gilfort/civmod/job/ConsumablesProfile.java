package com.gilfort.civmod.job;

import java.util.List;

import net.minecraft.world.item.Item;

/**
 * A farming Job whose work uses up things from the collect inventory that the Home Clerk can supply: the food of the Animal
 * Farmer's rolls and its shears (vault {@code Jobs/Animal Farmer.md}, decisions of 2026-11-15). The {@link WorkRules} and
 * the work loop ask it what is missing; {@link ConsumableOrders} fetches that from the Home Clerk like a tool.
 */
public interface ConsumablesProfile extends DropPoolProfile {

    /**
     * One kind of thing the collect inventory lacks.
     *
     * @param items  the items that serve, in registry order (one of them is enough)
     * @param amount how many of them one request asks for
     */
    record Need(List<Item> items, int amount) {
    }

    /**
     * What the collect inventory lacks.
     *
     * @param missing what is to be fetched, for every kind once
     * @param stuck   whether the Job could roll something but nothing can be rolled for lack of it: it shows "No food"
     */
    record Needs(List<Need> missing, boolean stuck) {
    }

    /**
     * @param context the production context
     * @param tools   the collect inventory
     * @return what the collect inventory lacks for the work that is possible now
     */
    Needs needs(Context context, JobDrops.Tools tools);
}
