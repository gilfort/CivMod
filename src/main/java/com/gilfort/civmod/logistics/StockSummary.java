package com.gilfort.civmod.logistics;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * How much of each item resource one or more containers hold, as last read. Reading a container uses only
 * {@code size()}, {@code getResource(i)} and {@code getAmountAsLong(i)}: no item stack per slot, never a transaction
 * (a simulated extraction may make the container copy its whole contents). Predicates on item stacks are tested once
 * per resource, on a stack made the first time the resource shows up.
 */
final class StockSummary {

    private final Map<ItemResource, Entry> entries = new HashMap<>();

    /** The amount of one resource and the stack predicates are tested on. */
    private static final class Entry {
        private long amount;
        private @Nullable ItemStack stack;

        private ItemStack stack(ItemResource resource) {
            if (stack == null) {
                stack = resource.toStack();
            }
            return stack;
        }
    }

    /**
     * @param handler a container
     * @return its contents, read now
     */
    static StockSummary of(ResourceHandler<ItemResource> handler) {
        StockSummary summary = new StockSummary();
        summary.read(handler, null);
        return summary;
    }

    /**
     * @param resource an item resource
     * @return how much of it is held
     */
    long amount(ItemResource resource) {
        Entry entry = entries.get(resource);
        return entry == null ? 0L : entry.amount;
    }

    /**
     * @param resource an item resource
     * @param delta    how much more of it is held (negative: less); an amount that drops to zero or below is removed
     */
    void add(ItemResource resource, long delta) {
        Entry entry = entries.computeIfAbsent(resource, key -> new Entry());
        entry.amount += delta;
        if (entry.amount <= 0) {
            entries.remove(resource);
        }
    }

    /**
     * Forgets everything held.
     *
     * @param total a summary this one is part of, updated by the difference
     */
    void clear(@Nullable StockSummary total) {
        if (total != null) {
            entries.forEach((resource, entry) -> total.add(resource, -entry.amount));
        }
        entries.clear();
    }

    /**
     * Re-reads a container.
     *
     * @param handler the container
     * @param total   a summary this one is part of, updated by the difference
     * @return whether the amounts differ from the last read
     */
    boolean read(ResourceHandler<ItemResource> handler, @Nullable StockSummary total) {
        Map<ItemResource, Long> read = new HashMap<>();
        for (int i = 0; i < handler.size(); i++) {
            ItemResource resource = handler.getResource(i);
            if (!resource.isEmpty()) {
                read.merge(resource, handler.getAmountAsLong(i), Long::sum);
            }
        }
        boolean changed = read.size() != entries.size();
        for (Map.Entry<ItemResource, Long> held : read.entrySet()) {
            Entry entry = entries.get(held.getKey());
            changed |= entry == null || entry.amount != held.getValue();
        }
        if (!changed) {
            return false;
        }
        Map<ItemResource, Entry> before = new HashMap<>(entries);
        clear(total);
        read.forEach((resource, amount) -> {
            Entry entry = before.getOrDefault(resource, new Entry());
            entry.amount = amount;
            entries.put(resource, entry);
            if (total != null) {
                total.add(resource, amount);
            }
        });
        return true;
    }

    /**
     * @param kind which items count
     * @return how many of them are held
     */
    int count(Predicate<ItemStack> kind) {
        long total = 0;
        for (Map.Entry<ItemResource, Entry> entry : entries.entrySet()) {
            if (kind.test(entry.getValue().stack(entry.getKey()))) {
                total += entry.getValue().amount;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * @param kind which items count
     * @return whether any of them is held
     */
    boolean contains(Predicate<ItemStack> kind) {
        for (Map.Entry<ItemResource, Entry> entry : entries.entrySet()) {
            if (kind.test(entry.getValue().stack(entry.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param counts the counts to add how many of each item are held to (components ignored)
     */
    void addCounts(Map<Item, Integer> counts) {
        entries.forEach((resource, entry) -> ItemCounts.add(counts, resource.getItem(), entry.amount));
    }
}
