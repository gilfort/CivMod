package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import com.gilfort.civmod.logistics.ItemCounts;
import com.mojang.serialization.Codec;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Internal output buffer of one job (vault {@code Systems/Resource Collection Jobs.md}): produced items wait here
 * until they are delivered, so the settler's pockets stay free for other jobs.
 */
public final class JobBuffer {

    /** Capacity in items (4 stacks). */
    public static final int CAPACITY = 256;
    /** Fill ratio at which the settler delivers during work. */
    public static final double DELIVER_RATIO = 0.8;

    /** Save codec. */
    public static final Codec<JobBuffer> CODEC = ItemStack.CODEC.listOf().xmap(JobBuffer::new, buffer -> List.copyOf(buffer.stacks));

    private final List<ItemStack> stacks = new ArrayList<>();

    /**
     * Creates an empty buffer.
     */
    public JobBuffer() {
    }

    private JobBuffer(List<ItemStack> stacks) {
        stacks.forEach(stack -> add(stack.copy()));
    }

    /**
     * @return the stored stacks (read-only)
     */
    public List<ItemStack> stacks() {
        return Collections.unmodifiableList(stacks);
    }

    /**
     * @return the number of stored items
     */
    public int count() {
        return stacks.stream().mapToInt(ItemStack::getCount).sum();
    }

    /**
     * @return whether no items are stored
     */
    public boolean isEmpty() {
        return stacks.isEmpty();
    }

    /**
     * @return whether the buffer reached {@link #CAPACITY}
     */
    public boolean isFull() {
        return count() >= CAPACITY;
    }

    /**
     * @return whether the settler should deliver during work
     */
    public boolean shouldDeliver() {
        return count() >= CAPACITY * DELIVER_RATIO;
    }

    /**
     * Adds items, merging with equal stacks (items beyond the capacity are still kept; production stops at full).
     *
     * @param stack the items
     */
    public void add(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remaining = stack.copy();
        for (ItemStack existing : stacks) {
            if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                int moved = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(moved);
                remaining.shrink(moved);
                if (remaining.isEmpty()) {
                    return;
                }
            }
        }
        while (!remaining.isEmpty()) {
            stacks.add(remaining.split(remaining.getMaxStackSize()));
        }
    }

    /**
     * @param kind      an item
     * @param maxStacks the most stacks the buffer may hold (a Deliveryman's carry capacity)
     * @return how many of that item still fit: the room in its stacks plus the free stacks
     */
    int room(ItemStack kind, int maxStacks) {
        int room = Math.max(0, maxStacks - stacks.size()) * kind.getMaxStackSize();
        for (ItemStack existing : stacks) {
            if (ItemStack.isSameItemSameComponents(existing, kind)) {
                room += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
        }
        return room;
    }

    /**
     * @return how many of each item are stored
     */
    public Map<Item, Integer> counts() {
        Map<Item, Integer> counts = new HashMap<>();
        stacks.forEach(stack -> ItemCounts.add(counts, stack.getItem(), stack.getCount()));
        return counts;
    }

    /**
     * Takes one item out.
     *
     * @param kind which items may be taken
     * @return the item, empty if none matches
     */
    public ItemStack takeOne(Predicate<ItemStack> kind) {
        for (ItemStack stack : stacks) {
            if (kind.test(stack)) {
                ItemStack one = stack.split(1);
                stacks.removeIf(ItemStack::isEmpty);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Takes all items of a kind out.
     *
     * @param kind which items are taken
     * @return the taken stacks
     */
    List<ItemStack> takeAll(Predicate<ItemStack> kind) {
        List<ItemStack> taken = stacks.stream().filter(kind).toList();
        stacks.removeIf(kind);
        return taken;
    }

    /**
     * Delivers as much as possible.
     *
     * @param inserter inserts a stack somewhere and returns how many items were accepted
     * @return whether everything was delivered
     */
    public boolean deliver(ToIntFunction<ItemStack> inserter) {
        return deliver(inserter, stack -> false);
    }

    /**
     * Delivers as much as possible, except what stays.
     *
     * @param inserter inserts a stack somewhere and returns how many items were accepted
     * @param kept     which items stay in the buffer (a Smelter's fuel rests)
     * @return whether everything but the kept items was delivered
     */
    public boolean deliver(ToIntFunction<ItemStack> inserter, Predicate<ItemStack> kept) {
        for (ItemStack stack : stacks) {
            if (!kept.test(stack)) {
                stack.shrink(inserter.applyAsInt(stack.copy()));
            }
        }
        stacks.removeIf(ItemStack::isEmpty);
        return stacks.stream().allMatch(kept);
    }
}
