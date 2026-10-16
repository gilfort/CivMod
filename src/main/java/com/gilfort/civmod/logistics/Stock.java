package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * What a visitor reaches at one place: the containers it may take from and put into, seen as one list of slots
 * (vault {@code Jobs/Clerk.md}). At a Clerk JobBlock that is its own 27 slots plus, through a working Clerk at its
 * counter, the linked storage; anywhere else the one container. Built by {@link ClerkStorage#stock}.
 * <p>
 * Stock is counted on every access (no snapshot, see the RISK in the vault); a Stock is a live view and is not kept
 * across ticks.
 */
public final class Stock {

    /** Nothing reachable. */
    public static final Stock EMPTY = new Stock(List.of(), false);

    private final List<ResourceHandler<ItemResource>> handlers;
    private final boolean counter;

    /**
     * @param handlers the containers in order (the Clerk JobBlock's own slots first)
     * @param counter  whether this is the whole storage of a working Clerk, served at its counter
     */
    Stock(List<ResourceHandler<ItemResource>> handlers, boolean counter) {
        this.handlers = List.copyOf(handlers);
        this.counter = counter;
    }

    /**
     * @return whether this is the whole storage of a working Clerk: what is taken from it is a hand-out
     */
    public boolean counter() {
        return counter;
    }

    /**
     * @return whether nothing is reachable
     */
    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /**
     * @return one stack (count 1) per slot of all containers, empty stacks for empty slots; indices are valid for
     *         {@link #takeOne}
     */
    public List<ItemStack> contents() {
        List<ItemStack> stacks = new ArrayList<>();
        for (ResourceHandler<ItemResource> handler : handlers) {
            for (int i = 0; i < handler.size(); i++) {
                ItemResource resource = handler.getResource(i);
                stacks.add(resource.isEmpty() ? ItemStack.EMPTY : resource.toStack());
            }
        }
        return stacks;
    }

    /**
     * @param kind which items count
     * @return how many of them are reachable
     */
    public int count(Predicate<ItemStack> kind) {
        int total = 0;
        for (ResourceHandler<ItemResource> handler : handlers) {
            for (int i = 0; i < handler.size(); i++) {
                ItemResource resource = handler.getResource(i);
                if (!resource.isEmpty() && kind.test(resource.toStack())) {
                    total += (int) Math.min(Integer.MAX_VALUE - (long) total, handler.getAmountAsLong(i));
                }
            }
        }
        return total;
    }

    /**
     * @return how many of each item are reachable (components ignored)
     */
    public Map<Item, Integer> counts() {
        Map<Item, Integer> counts = new HashMap<>();
        for (ResourceHandler<ItemResource> handler : handlers) {
            for (int i = 0; i < handler.size(); i++) {
                ItemResource resource = handler.getResource(i);
                if (!resource.isEmpty()) {
                    ItemCounts.add(counts, resource.getItem(), handler.getAmountAsLong(i));
                }
            }
        }
        return counts;
    }

    /**
     * Takes up to {@code count} items of a kind, container by container.
     *
     * @param item  the item
     * @param count how many at most
     * @return the taken stacks
     */
    public List<ItemStack> take(Item item, int count) {
        List<ItemStack> taken = new ArrayList<>();
        int rest = count;
        for (ResourceHandler<ItemResource> handler : handlers) {
            for (int i = 0; i < handler.size() && rest > 0; i++) {
                ItemResource resource = handler.getResource(i);
                if (resource.isEmpty() || resource.getItem() != item) {
                    continue;
                }
                try (Transaction transaction = Transaction.openRoot()) {
                    int extracted = handler.extract(i, resource, rest, transaction);
                    transaction.commit();
                    if (extracted > 0) {
                        taken.add(resource.toStack(extracted));
                        rest -= extracted;
                    }
                }
            }
        }
        return taken;
    }

    /**
     * @param kind which items count
     * @return whether any of them is reachable
     */
    public boolean contains(Predicate<ItemStack> kind) {
        for (ResourceHandler<ItemResource> handler : handlers) {
            for (int i = 0; i < handler.size(); i++) {
                ItemResource resource = handler.getResource(i);
                if (!resource.isEmpty() && kind.test(resource.toStack())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Takes one item out of a slot.
     *
     * @param index the slot index in {@link #contents()}
     * @return the item, empty if nothing could be taken
     */
    public ItemStack takeOne(int index) {
        int rest = index;
        for (ResourceHandler<ItemResource> handler : handlers) {
            if (rest >= handler.size()) {
                rest -= handler.size();
                continue;
            }
            ItemResource resource = handler.getResource(rest);
            if (resource.isEmpty()) {
                return ItemStack.EMPTY;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                if (handler.extract(rest, resource, 1, transaction) == 1) {
                    transaction.commit();
                    return resource.toStack(1);
                }
            }
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    /**
     * Puts a stack in, container by container (the Clerk JobBlock's own slots first).
     *
     * @param stack the stack
     * @return what did not fit
     */
    public ItemStack insert(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (ResourceHandler<ItemResource> handler : handlers) {
            if (rest.isEmpty()) {
                break;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int inserted = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(rest), rest.getCount(), transaction);
                transaction.commit();
                rest.shrink(inserted);
            }
        }
        return rest;
    }
}
