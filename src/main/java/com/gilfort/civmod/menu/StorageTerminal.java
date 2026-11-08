package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ClerkTerminal;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The Storage tab of a Clerk's settler window (vault {@code Jobs/Clerk.md}, terminal): the Clerk's whole storage as one
 * list, usable during the Clerk's shift. The server sends the full list when the tab opens, then only what changed, at
 * most once per second and only while the tab is open; searching and sorting happen on the client.
 * <p>
 * One instance per settler window on each side: the server one tracks what this viewer was sent, the client one holds
 * what it received.
 */
public final class StorageTerminal {

    /** Ticks between two syncs while the tab is open. */
    public static final int SYNC_INTERVAL = 20;

    /** What a click on the Storage tab does. */
    public enum Action {
        /** Takes a stack onto the cursor (left click, empty cursor). */
        TAKE_STACK,
        /** Takes half a stack onto the cursor (right click, empty cursor). */
        TAKE_HALF,
        /** Takes a stack into the player inventory (shift-click). */
        TAKE_TO_INVENTORY,
        /** Stores the cursor stack (left click with an item). */
        STORE,
        /** Stores one item of the cursor stack (right click with an item). */
        STORE_ONE,
        /** Takes one item onto the cursor, or adds one to the same item already there (Ctrl + left click). */
        TAKE_ONE
    }

    /**
     * One resource of the storage.
     *
     * @param icon   the resource (count 1)
     * @param amount how many are reachable; 0 in a delta: none any more
     */
    public record Entry(ItemStack icon, long amount) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Entry::icon,
                ByteBufCodecs.VAR_LONG, Entry::amount,
                Entry::new);
    }

    private final Map<ItemResource, Long> entries = new HashMap<>();
    private boolean available;
    private boolean onShift;
    private int revision;
    /** Server: the Clerk JobBlock whose storage this viewer has open, null while the tab is not open. */
    private @Nullable BlockPos viewing;
    /** Server: the level of {@link #viewing} (the settler may change dimension while the window is open). */
    private @Nullable ServerLevel viewingLevel;
    /** Server: whether the full list went out since the tab opened. */
    private boolean sentFull;
    private int syncIn;

    /**
     * @param available whether the settler holds the Clerk job
     */
    public StorageTerminal(boolean available) {
        this.available = available;
    }

    /**
     * @param jobs a settler's jobs
     * @return the JobBlock of its first Clerk job
     */
    static Optional<BlockPos> clerkOf(List<SettlerJob> jobs) {
        return jobs.stream().filter(job -> job.jobType() == ModJobs.CLERK.get()).map(SettlerJob::jobBlock).findFirst();
    }

    /**
     * @return whether the settler holds the Clerk job (the tab is shown)
     */
    public boolean available() {
        return available;
    }

    /**
     * @return whether the Clerk is on shift: the storage can be used
     */
    public boolean onShift() {
        return onShift;
    }

    /**
     * @return a number that changes whenever the list changes (the screen re-sorts then)
     */
    public int revision() {
        return revision;
    }

    /**
     * @return the reachable resources and their amounts
     */
    public Map<ItemResource, Long> entries() {
        return entries;
    }

    /**
     * An amount as drawn on an icon: as it is below 1,000, then shortened ("1.2k", "12k", "1.2M"), always rounded down.
     *
     * @param amount the amount
     * @return the short text
     */
    public static String shortAmount(long amount) {
        if (amount < 1000) {
            return Long.toString(amount);
        }
        String[] units = {"k", "M", "G"};
        long scale = 1000;
        int unit = 0;
        while (unit < units.length - 1 && amount >= scale * 1000) {
            scale *= 1000;
            unit++;
        }
        long whole = amount / scale;
        long tenth = amount % scale * 10 / scale;
        return whole < 10 && tenth > 0 ? whole + "." + tenth + units[unit] : whole + units[unit];
    }

    // ---------------------------------------------------------------- server

    /**
     * Server, every tick of the open window: follows the tab and the settler's Clerk job, and sends what changed once per
     * {@value #SYNC_INTERVAL} ticks.
     *
     * @param player      the viewer
     * @param settler     the settler
     * @param tabActive   whether the Storage tab is selected
     * @param containerId the window's container id
     * @param dayTime     the day time (the Clerk's shift decides whether the storage can be used)
     */
    void tick(ServerPlayer player, Settler settler, boolean tabActive, int containerId, long dayTime) {
        if (settler.level() instanceof ServerLevel level) {
            update(level, player.getUUID(), clerkOf(settler.getJobs()), tabActive, containerId, dayTime).ifPresent(sync -> PacketDistributor.sendToPlayer(player, sync));
        }
    }

    /**
     * Server: follows the tab and the Clerk job and works out what to send now.
     *
     * @param level       the level
     * @param player      the viewer
     * @param clerk       the JobBlock of the settler's first Clerk job
     * @param tabActive   whether the Storage tab is selected
     * @param containerId the window's container id
     * @param dayTime     the day time
     * @return what to send, empty if nothing is due or nothing changed
     */
    public Optional<Sync> update(ServerLevel level, UUID player, Optional<BlockPos> clerk, boolean tabActive, int containerId, long dayTime) {
        BlockPos target = tabActive ? clerk.orElse(null) : null;
        if (!Objects.equals(target, viewing) || target != null && viewingLevel != level) {
            stop(player);
            if (target != null) {
                viewing = target;
                viewingLevel = level;
                ClerkTerminal.open(level, target, player);
            }
        }
        if (clerk.isPresent() != available) {
            available = clerk.isPresent();
            return Optional.of(new Sync(containerId, true, available, false, List.of()));
        }
        if (viewing == null || --syncIn > 0) {
            return Optional.empty();
        }
        syncIn = SYNC_INTERVAL;
        boolean nowOnShift = ClerkStorage.working(level, viewing, dayTime);
        Map<ItemResource, Long> now = ClerkStorage.stock(level, viewing, true).entries();
        List<Entry> changed = new ArrayList<>();
        now.forEach((resource, amount) -> {
            if (!amount.equals(entries.get(resource))) {
                changed.add(new Entry(resource.toStack(1), amount));
            }
        });
        entries.keySet().stream().filter(resource -> !now.containsKey(resource)).forEach(resource -> changed.add(new Entry(resource.toStack(1), 0L)));
        if (sentFull && changed.isEmpty() && nowOnShift == onShift) {
            return Optional.empty();
        }
        boolean full = !sentFull;
        sentFull = true;
        onShift = nowOnShift;
        entries.clear();
        entries.putAll(now);
        return Optional.of(new Sync(containerId, full, available, onShift, full ? all() : changed));
    }

    private List<Entry> all() {
        return entries.entrySet().stream().map(entry -> new Entry(entry.getKey().toStack(1), entry.getValue())).toList();
    }

    /** Server: sends what changed on the next tick (after the viewer's own click). */
    void syncSoon() {
        syncIn = 0;
    }

    /**
     * Server: the tab is no longer open (other tab, window closed, player gone).
     *
     * @param player the viewer
     */
    public void stop(UUID player) {
        if (viewing != null && viewingLevel != null) {
            BlockPos was = viewing;
            ServerLevel level = viewingLevel;
            viewing = null;
            viewingLevel = null;
            sentFull = false;
            entries.clear();
            // The full list goes out at once when the tab opens again.
            syncIn = 0;
            ClerkTerminal.close(level, was, player);
        }
    }

    /**
     * Server: the storage the viewer may use now.
     *
     * @param level   the level
     * @param dayTime the day time
     * @return the Clerk's whole storage while its Storage tab is open and the Clerk is on shift
     */
    public Optional<Stock> usable(ServerLevel level, long dayTime) {
        if (viewing == null || !ClerkStorage.working(level, viewing, dayTime)) {
            return Optional.empty();
        }
        return Optional.of(ClerkStorage.stock(level, viewing, true));
    }

    /**
     * Server: a click on an entry of the Storage tab. No hand-out: the Clerk gains no EXP.
     *
     * @param player the player
     * @param level   the level
     * @param dayTime the day time
     * @param icon    the clicked resource (empty for a click on free space)
     * @param action  what the click does
     */
    public void click(Player player, ServerLevel level, long dayTime, ItemStack icon, Action action) {
        Optional<Stock> found = usable(level, dayTime);
        if (found.isEmpty()) {
            return;
        }
        Stock stock = found.get();
        ItemStack carried = player.containerMenu.getCarried();
        switch (action) {
            case STORE -> player.containerMenu.setCarried(stock.insert(carried));
            case STORE_ONE -> {
                if (!carried.isEmpty() && stock.insert(carried.copyWithCount(1)).isEmpty()) {
                    carried.shrink(1);
                }
            }
            case TAKE_STACK, TAKE_HALF -> {
                if (!carried.isEmpty() || icon.isEmpty()) {
                    return;
                }
                ItemResource resource = ItemResource.of(icon);
                int max = icon.getMaxStackSize();
                int count = action == Action.TAKE_STACK ? max
                        : (int) ((Math.min(max, stock.entries().getOrDefault(resource, 0L)) + 1) / 2);
                player.containerMenu.setCarried(stock.take(resource, count));
            }
            case TAKE_ONE -> {
                if (icon.isEmpty() || !canStackOne(carried, icon)) {
                    return;
                }
                ItemStack taken = stock.take(ItemResource.of(icon), 1);
                if (carried.isEmpty()) {
                    player.containerMenu.setCarried(taken);
                } else {
                    carried.grow(taken.getCount());
                }
            }
            case TAKE_TO_INVENTORY -> {
                if (icon.isEmpty()) {
                    return;
                }
                ItemStack taken = stock.take(ItemResource.of(icon), icon.getMaxStackSize());
                player.getInventory().add(taken);
                if (!taken.isEmpty()) {
                    // What does not fit into the inventory goes back.
                    ItemStack rest = stock.insert(taken);
                    if (!rest.isEmpty()) {
                        player.spawnAtLocation(level, rest);
                    }
                }
            }
        }
        syncSoon();
    }

    /** Whether one more item of {@code icon} fits onto the cursor stack; an empty cursor takes anything. */
    private static boolean canStackOne(ItemStack carried, ItemStack icon) {
        if (carried.isEmpty()) {
            return true;
        }
        return ItemStack.isSameItemSameComponents(carried, icon) && carried.getCount() < carried.getMaxStackSize();
    }

    // ---------------------------------------------------------------- client

    /** Client: applies a sync. */
    private void apply(Sync sync) {
        available = sync.available();
        onShift = sync.open();
        if (sync.full()) {
            entries.clear();
        }
        for (Entry entry : sync.entries()) {
            ItemResource resource = ItemResource.of(entry.icon());
            if (entry.amount() > 0) {
                entries.put(resource, entry.amount());
            } else {
                entries.remove(resource);
            }
        }
        revision++;
    }

    /**
     * Server → client: the Storage tab's list (full or what changed) and state.
     *
     * @param containerId the settler window's container id
     * @param full        whether this is the whole list (the client drops what it had)
     * @param available   whether the settler still holds the Clerk job
     * @param open        whether the Clerk is on shift
     * @param entries     the entries (in a delta, amount 0: gone)
     */
    public record Sync(int containerId, boolean full, boolean available, boolean open, List<Entry> entries) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("storage_terminal_sync"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                ByteBufCodecs.BOOL, Sync::full,
                ByteBufCodecs.BOOL, Sync::available,
                ByteBufCodecs.BOOL, Sync::open,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::entries,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }

        /**
         * Client: applies the list to the open settler window.
         *
         * @param menu the open settler window
         * @return whether the tab's visibility changed (the screen rebuilds then)
         */
        public boolean applyTo(SettlerMenu menu) {
            if (menu.containerId != containerId) {
                return false;
            }
            boolean was = menu.terminal().available();
            menu.terminal().apply(this);
            return was != available;
        }
    }

    /**
     * Client → server: a click on the Storage tab.
     *
     * @param containerId the settler window's container id
     * @param icon        the clicked resource (count 1), empty for free space
     * @param action      what the click does
     */
    public record Click(int containerId, ItemStack icon, Action action) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Click> TYPE = new Type<>(CivMod.id("storage_terminal_click"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Click> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Click::containerId,
                ItemStack.OPTIONAL_STREAM_CODEC, Click::icon,
                ByteBufCodecs.idMapper(index -> Action.values()[index], Action::ordinal), Click::action,
                Click::new);

        @Override
        public Type<Click> type() {
            return TYPE;
        }

        /**
         * Applies the click to the player's open settler window.
         *
         * @param player the player
         */
        public void handle(ServerPlayer player) {
            if (player.containerMenu instanceof SettlerMenu menu && menu.containerId == containerId) {
                menu.terminalClick(player, icon, action);
            }
        }
    }
}
