package com.gilfort.civmod.logistics;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Item counts kept in a map (how many of each thing a buffer, a stock or a plan holds, a thing being an item with its
 * relevant components, see {@link ItemKey}); sums saturate at {@link Integer#MAX_VALUE}.
 */
public final class ItemCounts {

    private record Entry(ItemKey item, int count) {

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemKey.CODEC.fieldOf("item").forGetter(Entry::item),
                Codec.INT.fieldOf("count").forGetter(Entry::count)
        ).apply(instance, Entry::new));
    }

    /** Save codec: a list of {@code {item, count}}, because a key with components cannot be a map key in a save. */
    public static final Codec<Map<ItemKey, Integer>> CODEC = Entry.CODEC.listOf().xmap(entries -> {
        Map<ItemKey, Integer> counts = new LinkedHashMap<>();
        entries.forEach(entry -> add(counts, entry.item(), entry.count()));
        return counts;
    }, counts -> counts.entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue())).toList());

    private ItemCounts() {
    }

    /**
     * @param counts the counts to add to
     * @param item   a thing (item and relevant components)
     * @param amount how many of it are added
     */
    public static void add(Map<ItemKey, Integer> counts, ItemKey item, long amount) {
        counts.merge(item, (int) Math.min(Integer.MAX_VALUE, amount), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
    }

    /**
     * @param counts the counts to add to
     * @param more   the counts added
     */
    public static void addAll(Map<ItemKey, Integer> counts, Map<ItemKey, Integer> more) {
        more.forEach((item, amount) -> add(counts, item, amount));
    }
}
