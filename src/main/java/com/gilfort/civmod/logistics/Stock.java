package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

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
 * At a Clerk JobBlock the counts come from its stock view ({@link ClerkStock}): counted at most once per tick, kept
 * right by every transaction made through this Stock. Taking always works on the real containers and corrects the
 * view when it finds less than expected. Anywhere else the container is counted on every access. A Stock is not kept
 * across ticks.
 */
public final class Stock {

    /** Nothing reachable. */
    public static final Stock EMPTY = new Stock(List.of(), null, false);

    private final List<Part> parts;
    private final @Nullable List<StockSummary> summaries;
    private final boolean counter;

    /**
     * Keeps the stock view of one container right when CivMod changes the container.
     */
    interface Tracker {

        /** For a container without a stock view. */
        Tracker NONE = new Tracker() {
            @Override
            public long mark() {
                return 0L;
            }

            @Override
            public void changed(long mark, ItemResource resource, long delta) {
            }

            @Override
            public void drained(Item item) {
            }

            @Override
            public void reread() {
            }
        };

        /**
         * @return a mark taken right before a transaction
         */
        long mark();

        /**
         * Writes a committed transaction through.
         *
         * @param mark     the mark taken before it
         * @param resource the resource moved
         * @param delta    how much came in (negative: went out)
         */
        void changed(long mark, ItemResource resource, long delta);

        /**
         * Re-reads the container at once if its view still counts an item that taking found none of.
         *
         * @param item the item the real container has no more of
         */
        void drained(Item item);

        /**
         * Re-reads the container at once: an extraction found less than the view expected.
         */
        void reread();
    }

    /**
     * One container of a Stock.
     *
     * @param handler the container
     * @param tracker keeps its stock view right
     */
    record Part(ResourceHandler<ItemResource> handler, Tracker tracker) {
    }

    /**
     * @param handlers the containers in order, counted on every access
     * @param counter  whether this is the whole storage of a working Clerk, served at its counter
     */
    static Stock live(List<ResourceHandler<ItemResource>> handlers, boolean counter) {
        return new Stock(handlers.stream().map(handler -> new Part(handler, Tracker.NONE)).toList(), null, counter);
    }

    /**
     * @param parts     the containers in order (the Clerk JobBlock's own slots first)
     * @param summaries the stock view of all of them, null to count them on every access
     * @param counter   whether this is the whole storage of a working Clerk, served at its counter
     */
    Stock(List<Part> parts, @Nullable List<StockSummary> summaries, boolean counter) {
        this.parts = List.copyOf(parts);
        this.summaries = summaries == null ? null : List.copyOf(summaries);
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
        return parts.isEmpty();
    }

    /**
     * Reads the real containers (for picking a slot to take from).
     *
     * @return one stack (count 1) per slot of all containers, empty stacks for empty slots; indices are valid for
     *         {@link #takeOne}
     */
    public List<ItemStack> contents() {
        List<ItemStack> stacks = new ArrayList<>();
        for (Part part : parts) {
            ResourceHandler<ItemResource> handler = part.handler();
            for (int i = 0; i < handler.size(); i++) {
                ItemResource resource = handler.getResource(i);
                stacks.add(resource.isEmpty() ? ItemStack.EMPTY : resource.toStack());
            }
        }
        return stacks;
    }

    private List<StockSummary> summaries() {
        return summaries != null ? summaries : parts.stream().map(part -> StockSummary.of(part.handler())).toList();
    }

    /**
     * @param kind which items count
     * @return how many of them are reachable
     */
    public int count(Predicate<ItemStack> kind) {
        long total = 0;
        for (StockSummary summary : summaries()) {
            total += summary.count(kind);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * @return how many of each item are reachable (components ignored)
     */
    public Map<Item, Integer> counts() {
        Map<Item, Integer> counts = new HashMap<>();
        summaries().forEach(summary -> summary.addCounts(counts));
        return counts;
    }

    /**
     * @param kind which items count
     * @return whether any of them is reachable
     */
    public boolean contains(Predicate<ItemStack> kind) {
        return summaries().stream().anyMatch(summary -> summary.contains(kind));
    }

    /**
     * Takes up to {@code count} items of a kind, container by container. A container left behind with items still to
     * take has none of them left; if its stock view still counts some, it is re-read.
     *
     * @param item  the item
     * @param count how many at most
     * @return the taken stacks
     */
    public List<ItemStack> take(Item item, int count) {
        List<ItemStack> taken = new ArrayList<>();
        int rest = count;
        for (Part part : parts) {
            if (rest <= 0) {
                break;
            }
            ResourceHandler<ItemResource> handler = part.handler();
            for (int i = 0; i < handler.size() && rest > 0; i++) {
                ItemResource resource = handler.getResource(i);
                if (resource.isEmpty() || resource.getItem() != item) {
                    continue;
                }
                long mark = part.tracker().mark();
                try (Transaction transaction = Transaction.openRoot()) {
                    int extracted = handler.extract(i, resource, rest, transaction);
                    transaction.commit();
                    if (extracted > 0) {
                        part.tracker().changed(mark, resource, -extracted);
                        taken.add(resource.toStack(extracted));
                        rest -= extracted;
                    }
                }
            }
            if (rest > 0) {
                part.tracker().drained(item);
            }
        }
        return taken;
    }

    /**
     * Takes one item out of a slot.
     *
     * @param index the slot index in {@link #contents()}
     * @return the item, empty if nothing could be taken (the container is then re-read into the stock view)
     */
    public ItemStack takeOne(int index) {
        int rest = index;
        for (Part part : parts) {
            ResourceHandler<ItemResource> handler = part.handler();
            if (rest >= handler.size()) {
                rest -= handler.size();
                continue;
            }
            ItemResource resource = handler.getResource(rest);
            if (!resource.isEmpty()) {
                long mark = part.tracker().mark();
                try (Transaction transaction = Transaction.openRoot()) {
                    if (handler.extract(rest, resource, 1, transaction) == 1) {
                        transaction.commit();
                        part.tracker().changed(mark, resource, -1);
                        return resource.toStack(1);
                    }
                }
            }
            part.tracker().reread();
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
        for (Part part : parts) {
            if (rest.isEmpty()) {
                break;
            }
            ItemResource resource = ItemResource.of(rest);
            long mark = part.tracker().mark();
            try (Transaction transaction = Transaction.openRoot()) {
                int inserted = ResourceHandlerUtil.insertStacking(part.handler(), resource, rest.getCount(), transaction);
                transaction.commit();
                if (inserted > 0) {
                    part.tracker().changed(mark, resource, inserted);
                }
                rest.shrink(inserted);
            }
        }
        return rest;
    }
}
