package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.block.JobBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The stock view of one Clerk JobBlock (vault {@code Jobs/Clerk.md}, stock counting rework): what its own 27 slots and
 * its linked containers hold. Reads are lookups; nothing is counted more than once per tick unless it changed.
 * <ul>
 * <li>The own slots are re-read only after the block entity reports a change ({@link JobBlockEntity#changes()}), so a
 * change by a player or a hopper shows at once.</li>
 * <li>Linked containers are checked at most once per tick.</li>
 * <li>Linked containers cannot report changes (no listener for foreign handlers): each one is re-read after its
 * re-read interval, {@value #MIN_REREAD} ticks after a re-read that found a change, doubling up to
 * {@value #MAX_REREAD} ticks (10 s) while nothing changes.</li>
 * <li>Every transaction CivMod makes through a {@link Stock} is written through at once; an extraction that finds
 * less than expected re-reads the container. Increases are noted for the surplus check ({@link #takeGrown}).</li>
 * <li>Unloaded linked containers are skipped, never loaded; each linked position keeps a capability cache.</li>
 * </ul>
 * Kept per level and position while the block entity exists.
 */
final class ClerkStock {

    /** Re-read interval of a linked container after a re-read that found a change (2 s). */
    static final int MIN_REREAD = 40;
    /** Longest re-read interval of a linked container (10 s). */
    static final int MAX_REREAD = 200;

    private static final Map<ServerLevel, Map<BlockPos, ClerkStock>> VIEWS = new WeakHashMap<>();
    /** Per level: the Clerks whose stock a CivMod transaction increased since the last {@link #takeGrown}, with the items. */
    private static final Map<ServerLevel, Map<BlockPos, Set<Item>>> GROWN = new WeakHashMap<>();

    private final ServerLevel level;
    private final JobBlockEntity block;
    private final BlockCapabilityCache<ResourceHandler<ItemResource>, @Nullable Direction> ownCache;
    private final StockSummary own = new StockSummary();
    private final OwnTracker ownTracker = new OwnTracker();
    private long ownChanges = Long.MIN_VALUE;
    private final StockSummary linkedTotal = new StockSummary();
    private List<Linked> linked = List.of();
    private long linkedTick = Long.MIN_VALUE;
    private boolean valid = true;

    private ClerkStock(ServerLevel level, JobBlockEntity block) {
        this.level = level;
        this.block = block;
        this.ownCache = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, block.getBlockPos(), null, () -> valid, () -> {
        });
    }

    /**
     * @param level the level
     * @param pos   a loaded Clerk JobBlock
     * @return its stock view, null if there is no JobBlock block entity
     */
    static @Nullable ClerkStock of(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof JobBlockEntity block)) {
            return null;
        }
        Map<BlockPos, ClerkStock> views = VIEWS.computeIfAbsent(level, key -> new HashMap<>());
        ClerkStock view = views.get(pos);
        if (view == null || view.block != block) {
            if (view != null) {
                view.valid = false;
            }
            view = new ClerkStock(level, block);
            views.put(pos.immutable(), view);
        }
        return view;
    }

    /**
     * Drops the view of a removed (or unloaded) JobBlock block entity.
     *
     * @param level the level
     * @param block the block entity
     */
    static void forget(ServerLevel level, JobBlockEntity block) {
        Map<BlockPos, ClerkStock> views = VIEWS.get(level);
        ClerkStock view = views == null ? null : views.get(block.getBlockPos());
        if (view != null && view.block == block) {
            view.valid = false;
            views.remove(block.getBlockPos());
        }
    }

    /**
     * Drops the views of an unloaded level.
     *
     * @param level the level
     */
    static void forget(ServerLevel level) {
        GROWN.remove(level);
        Map<BlockPos, ClerkStock> views = VIEWS.remove(level);
        if (views != null) {
            views.values().forEach(view -> view.valid = false);
        }
    }

    /**
     * @param level the level
     * @return the Clerks whose stock a CivMod transaction increased since the last call, with the items; cleared
     */
    static Map<BlockPos, Set<Item>> takeGrown(ServerLevel level) {
        Map<BlockPos, Set<Item>> grown = GROWN.remove(level);
        return grown == null ? Map.of() : grown;
    }

    /** Notes an increase by a CivMod transaction (own slots or a linked container). */
    private void grew(ItemResource resource, long delta) {
        if (delta > 0) {
            GROWN.computeIfAbsent(level, key -> new HashMap<>()).computeIfAbsent(block.getBlockPos().immutable(), key -> new HashSet<>())
                    .add(resource.getItem());
        }
    }

    /**
     * @param storage the linked containers in use, empty for the own slots only
     * @param linked  whether the linked storage is reachable
     * @return the stock: the own slots, plus the loaded linked containers if reachable
     */
    Stock stock(List<BlockPos> storage, boolean linked) {
        long tick = level.getGameTime();
        ResourceHandler<ItemResource> ownHandler = ownCache.getCapability();
        if (ownHandler == null) {
            return Stock.EMPTY;
        }
        if (block.changes() != ownChanges) {
            ownTracker.reread();
        }
        List<Stock.Part> parts = new ArrayList<>();
        parts.add(new Stock.Part(ownHandler, ownTracker));
        if (!linked) {
            return new Stock(parts, List.of(own), false);
        }
        if (linkedTick != tick) {
            linkedTick = tick;
            link(storage);
            this.linked.forEach(container -> container.refresh(tick));
        }
        for (Linked container : this.linked) {
            if (container.handler != null) {
                parts.add(new Stock.Part(container.handler, container));
            }
        }
        return new Stock(parts, List.of(own, linkedTotal), true);
    }

    /** Follows the linked containers in use, keeping the views of those still linked. */
    private void link(List<BlockPos> storage) {
        if (linkedAt(storage)) {
            return;
        }
        Map<BlockPos, Linked> kept = new HashMap<>();
        linked.forEach(container -> kept.put(container.pos, container));
        List<Linked> next = new ArrayList<>();
        for (BlockPos pos : storage) {
            Linked container = kept.remove(pos);
            next.add(container != null ? container : new Linked(pos.immutable()));
        }
        kept.values().forEach(container -> {
            container.inUse = false;
            container.summary.clear(linkedTotal);
        });
        linked = List.copyOf(next);
    }

    private boolean linkedAt(List<BlockPos> storage) {
        if (linked.size() != storage.size()) {
            return false;
        }
        for (int i = 0; i < storage.size(); i++) {
            if (!linked.get(i).pos.equals(storage.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Write-through for the own slots: valid only if no foreign change came in since they were last read. */
    private final class OwnTracker implements Stock.Tracker {

        @Override
        public long mark() {
            return block.changes();
        }

        @Override
        public void changed(long mark, ItemResource resource, long delta) {
            grew(resource, delta);
            if (mark != ownChanges) {
                return;
            }
            if (!own.covers(resource, delta)) {
                reread();
                return;
            }
            own.add(resource, delta);
            ownChanges = block.changes();
        }

        @Override
        public void drained(Item item) {
            if (own.contains(item)) {
                reread();
            }
        }

        @Override
        public void reread() {
            ResourceHandler<ItemResource> handler = ownCache.getCapability();
            if (handler != null) {
                own.read(handler, null);
            }
            ownChanges = block.changes();
        }
    }

    /** One linked container: its capability cache, its view and when it is re-read next. */
    private final class Linked implements Stock.Tracker {

        private final BlockPos pos;
        private final BlockCapabilityCache<ResourceHandler<ItemResource>, @Nullable Direction> cache;
        private final StockSummary summary = new StockSummary();
        private @Nullable ResourceHandler<ItemResource> handler;
        private long readTick;
        private int interval = MAX_REREAD;
        private boolean stale = true;
        private boolean inUse = true;

        private Linked(BlockPos pos) {
            this.pos = pos;
            this.cache = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, pos, null, () -> inUse && valid, () -> stale = true);
        }

        /** Skips the container while it is unloaded or gone, re-reads it when its interval is over. */
        private void refresh(long tick) {
            handler = level.isLoaded(pos) ? cache.getCapability() : null;
            if (handler == null) {
                summary.clear(linkedTotal);
                stale = true;
                return;
            }
            if (stale || tick - readTick >= interval) {
                read(handler, tick);
            }
        }

        private void read(ResourceHandler<ItemResource> handler, long tick) {
            boolean changed = summary.read(handler, linkedTotal);
            interval = changed ? MIN_REREAD : Math.min(MAX_REREAD, interval * 2);
            readTick = tick;
            stale = false;
        }

        @Override
        public long mark() {
            return 0L;
        }

        @Override
        public void changed(long mark, ItemResource resource, long delta) {
            grew(resource, delta);
            if (!summary.covers(resource, delta)) {
                reread();
                return;
            }
            summary.add(resource, delta);
            linkedTotal.add(resource, delta);
        }

        @Override
        public void drained(Item item) {
            if (summary.contains(item)) {
                reread();
            }
        }

        @Override
        public void reread() {
            if (handler != null) {
                read(handler, level.getGameTime());
            }
        }
    }
}
