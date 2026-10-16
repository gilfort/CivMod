package com.gilfort.civmod.logistics;

import java.util.Map;

import net.minecraft.world.item.Item;

/**
 * Item counts kept in a map (how many of each item a buffer, a stock or a plan holds); sums saturate at
 * {@link Integer#MAX_VALUE}.
 */
public final class ItemCounts {

    private ItemCounts() {
    }

    /**
     * @param counts the counts to add to
     * @param item   an item
     * @param amount how many of it are added
     */
    public static void add(Map<Item, Integer> counts, Item item, long amount) {
        counts.merge(item, (int) Math.min(Integer.MAX_VALUE, amount), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
    }

    /**
     * @param counts the counts to add to
     * @param more   the counts added
     */
    public static void addAll(Map<Item, Integer> counts, Map<Item, Integer> more) {
        more.forEach((item, amount) -> add(counts, item, amount));
    }
}
