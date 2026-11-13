package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.registry.ModMenuTypes;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu of a JobBlock: a window of tabs ({@link JobBlockTab}, chosen by {@link JobBlockTabs}). Every window has the
 * Workers tab and, except for a Clerk (no slots of its own), the Storage tab (27 slots + player inventory); the other tabs depend on the Job Category. The menu gives
 * every tab its index and its range of menu button ids, shows the player inventory on the tabs that pick items from
 * it, sends the tabs' synced data ({@link TabData}) and otherwise only iterates over its tabs.
 */
public class JobBlockMenu extends CivMenu {

    /**
     * Panel height of the tall window (a grid of 45 / 54 slots, the Clerk's three-row stock rule editor, a Pantry's up
     * to seven storage rows and the player inventory).
     */
    public static final int CLERK_HEIGHT = 262;
    /** Ticks between two checks whether a tab's synced data changed. */
    private static final int TAB_SYNC_INTERVAL = 20;

    /**
     * The server side of an open JobBlock window.
     *
     * @param level    the level
     * @param pos      the JobBlock position
     * @param jobBlock the JobBlock
     */
    record Site(ServerLevel level, BlockPos pos, JobBlockEntity jobBlock) {
    }

    private final Container container;
    /** The storage slots of the window: 27, a Pantry's grow with its level. */
    private final int storageSlots;
    private final Player player;
    private final List<JobBlockTab> tabs;
    /** The tabs' synced data, each once, in tab order. */
    private final List<TabData<?>> synced;
    private JobBlockInfo info;
    private int tabSyncIn;

    /**
     * Client constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param buffer      extra data written by the server
     */
    public JobBlockMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, JobBlockInfo.STREAM_CODEC.decode(buffer));
    }

    private JobBlockMenu(int containerId, Inventory inventory, JobBlockInfo info) {
        this(containerId, inventory, new SimpleContainer(SettlementRules.storageSlots(info.jobType(), info.level())), info);
    }

    /**
     * Server constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param container   the JobBlock storage
     * @param info        the info snapshot
     */
    public JobBlockMenu(int containerId, Inventory inventory, Container container, JobBlockInfo info) {
        this(containerId, inventory, container, info, JobBlockTabs.of(info.jobType()));
    }

    private JobBlockMenu(int containerId, Inventory inventory, Container container, JobBlockInfo info, List<JobBlockTab> tabs) {
        super(ModMenuTypes.JOB_BLOCK.get(), containerId, tabs.size());
        this.storageSlots = SettlementRules.storageSlots(info.jobType(), info.level());
        checkContainerSize(container, storageSlots);
        this.container = container;
        this.player = inventory.player;
        this.info = info;
        this.tabs = List.copyOf(tabs);
        this.synced = this.tabs.stream().flatMap(tab -> tab.synced().stream()).distinct().toList();
        int button = 0;
        for (int i = 0; i < this.tabs.size(); i++) {
            JobBlockTab tab = this.tabs.get(i);
            tab.attach(this, i, button);
            button += tab.buttons();
        }
        if (button > tabButtonId(0)) {
            throw new IllegalStateException("The tab buttons of a JobBlock window overlap its tab selection buttons: " + button);
        }
        // A Clerk JobBlock has no slots and no Storage tab.
        tab(StorageTab.class).ifPresent(storage -> {
            for (int slot = 0; slot < storageSlots; slot++) {
                addTabSlot(container, slot, 8 + slot % 9 * 18, 18 + slot / 9 * 18, storage.index());
            }
        });
        int[] inventoryTabs = this.tabs.stream().filter(JobBlockTab::showsInventory).mapToInt(JobBlockTab::index).toArray();
        addPlayerInventory(inventory, 8, isTall() ? CLERK_HEIGHT - 82 : 84, inventoryTabs);
    }

    /**
     * @return the tabs of the window, in tab order (the tabs below the panel last)
     */
    public List<JobBlockTab> tabs() {
        return tabs;
    }

    /**
     * @param type a tab class
     * @param <T>  the tab type
     * @return the first tab of that class, if the window has one
     */
    public <T extends JobBlockTab> Optional<T> tab(Class<T> type) {
        return tabs.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    /**
     * @return the selected tab
     */
    public JobBlockTab activeTab() {
        return tabs.get(getActiveTab());
    }

    /**
     * @return the Producing Jobs with a Patterns tab, in tab order (the Base Job first; empty without Patterns tabs)
     */
    public List<Job> patternJobs() {
        return tabs.stream().filter(PatternsTab.class::isInstance).map(tab -> ((PatternsTab) tab).job()).toList();
    }

    /**
     * @return whether the menu has a tall panel (one of its tabs needs it)
     */
    public boolean isTall() {
        return tabs.stream().anyMatch(JobBlockTab::tall);
    }

    /**
     * @return the info snapshot
     */
    public JobBlockInfo getInfo() {
        return info;
    }

    /**
     * Client: the server sent a new info snapshot; both sides: a tab changed it.
     *
     * @param info the info
     */
    public void setInfo(JobBlockInfo info) {
        this.info = info;
    }

    /**
     * @return the Workers data as last synced (the block's settlement state, for the header too)
     */
    public WorkersTabData workers() {
        return tab(WorkersTab.class).map(workers -> workers.data().get()).orElse(WorkersTabData.EMPTY);
    }

    /**
     * @return the JobBlock this window shows on the server, empty on the client
     */
    Optional<Site> site() {
        if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
            return Optional.of(new Site(level, jobBlock.getBlockPos(), jobBlock));
        }
        return Optional.empty();
    }

    /**
     * @return the player who opened the window
     */
    Player player() {
        return player;
    }

    /**
     * @return the JobBlock this window shows on the server while the window is still valid for its player, empty on the
     *         client and once the player is away
     */
    Optional<Site> validSite() {
        return site().filter(found -> stillValid(player));
    }

    /**
     * Server: a tab changed the JobBlock; reads the tabs' synced data now and sends what changed, to this window's
     * player. The windows of other players at the block pick the change up with their next broadcast.
     */
    public void resend() {
        site().ifPresent(site -> synced.forEach(data -> data.sync(this, site.level(), site.pos())));
        tabSyncIn = TAB_SYNC_INTERVAL;
    }

    /** Server: sends the tabs' synced data with the next broadcast instead of waiting for the interval. */
    void syncSoon() {
        tabSyncIn = 0;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (synced.isEmpty() || !(player instanceof ServerPlayer) || site().isEmpty() || --tabSyncIn > 0) {
            return;
        }
        resend();
    }

    /**
     * @param player      the player
     * @param containerId the container id a packet named
     * @return the JobBlock window the player has open under that id, while it is still valid for the player
     */
    public static Optional<JobBlockMenu> open(Player player, int containerId) {
        return player.containerMenu instanceof JobBlockMenu menu && menu.containerId == containerId && menu.stillValid(player)
                ? Optional.of(menu) : Optional.empty();
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        for (JobBlockTab tab : tabs) {
            if (tab.ownsButton(buttonId)) {
                return tab.clickButton(player, buttonId - tab.buttonId(0));
            }
        }
        if (buttonId >= tabButtonId(0) && buttonId < tabButtonId(getTabCount())) {
            // A new tab: its synced data (a Patterns tab's library) is sent at once.
            tabSyncIn = 0;
        }
        return super.clickMenuButton(player, buttonId);
    }

    /**
     * Shift-click moves between the player inventory and the JobBlock storage on the Storage tab only; on the other
     * tabs (Stock, Patterns, Station Types: the player inventory is there to pick items from) it does nothing.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!(activeTab() instanceof StorageTab) || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = slotIndex < storageSlots
                ? moveItemStackTo(stack, storageSlots, slots.size(), true)
                : moveItemStackTo(stack, 0, storageSlots, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
