package com.gilfort.civmod.menu;

import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

/**
 * Base class for all container menus of CivMod.
 * <p>
 * Supports tabs: every slot belongs to one tab (or {@link #ALL_TABS}) and is only active while its tab is selected.
 * The selected tab is kept in sync between client and server via vanilla menu button clicks
 * (see {@link #tabButtonId(int)}), so hidden slots can neither be seen nor clicked on either side.
 */
public abstract class CivMenu extends AbstractContainerMenu {

    /** Tab index for slots that are visible on every tab. */
    public static final int ALL_TABS = -1;

    /** Menu button ids at or above this value select a tab; lower ids are free for subclasses. */
    private static final int TAB_BUTTON_BASE = 1000;

    /** Height of the player inventory block (3 rows, gap, hotbar) in pixels. */
    public static final int PLAYER_INVENTORY_HEIGHT = 76;

    private final int tabCount;
    private int activeTab;

    /**
     * @param type        the menu type
     * @param containerId the container id
     * @param tabCount    the number of tabs; {@code 1} for menus without tabs
     */
    protected CivMenu(MenuType<?> type, int containerId, int tabCount) {
        super(type, containerId);
        this.tabCount = Math.max(1, tabCount);
    }

    /**
     * @return the number of tabs
     */
    public int getTabCount() {
        return tabCount;
    }

    /**
     * @return the index of the selected tab
     */
    public int getActiveTab() {
        return activeTab;
    }

    /**
     * Selects a tab locally. The client additionally has to send {@link #tabButtonId(int)} to the server.
     *
     * @param tab the tab index, clamped to the valid range
     */
    public void setActiveTab(int tab) {
        activeTab = Mth.clamp(tab, 0, tabCount - 1);
    }

    /**
     * @param tab a tab index or {@link #ALL_TABS}
     * @return whether content of that tab is currently shown
     */
    public boolean isTabActive(int tab) {
        return tab == ALL_TABS || tab == activeTab;
    }

    /**
     * @param tab the tab index
     * @return the menu button id that selects the tab
     */
    public static int tabButtonId(int tab) {
        return TAB_BUTTON_BASE + tab;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        int tab = buttonId - TAB_BUTTON_BASE;
        if (tab >= 0 && tab < tabCount) {
            setActiveTab(tab);
            return true;
        }
        return super.clickMenuButton(player, buttonId);
    }

    /**
     * Adds a slot that is only active on the given tab.
     *
     * @param container the backing container
     * @param index     the index within the container
     * @param x         the x position relative to the screen
     * @param y         the y position relative to the screen
     * @param tabs      the tab indices the slot is active on, or {@link #ALL_TABS}
     * @return the added slot
     */
    protected Slot addTabSlot(Container container, int index, int x, int y, int... tabs) {
        return addSlot(new CivSlot(this, container, index, x, y, tabs));
    }

    /**
     * Adds the 27 inventory and 9 hotbar slots of the player in the vanilla layout.
     *
     * @param inventory the player inventory
     * @param left      the x position of the first column
     * @param top       the y position of the first row
     * @param tabs      the tab indices the slots are active on, or {@link #ALL_TABS}
     */
    protected void addPlayerInventory(Inventory inventory, int left, int top, int... tabs) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addTabSlot(inventory, column + row * 9 + 9, left + column * 18, top + row * 18, tabs);
            }
        }
        for (int column = 0; column < 9; column++) {
            addTabSlot(inventory, column, left + column * 18, top + 58, tabs);
        }
    }
}
