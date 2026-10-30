package com.gilfort.civmod.menu;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * One tab of the JobBlock window (vault {@code Systems/JobBlock.md}, {@code Systems/GUI.md}): its entry in the tab bar,
 * the menu buttons it handles and the data the server keeps sending while the window is open. {@link JobBlockTabs}
 * chooses the tabs of a JobBlock; the {@link JobBlockMenu} gives each its index and button ids and only iterates over
 * them. The view of a tab is client code ({@code client.gui.JobBlockTabView}), never loaded on a dedicated server.
 */
public abstract sealed class JobBlockTab permits StorageTab, WorkersTab, FoundTab, MissionsTab, StockTab, ClerkListTab, PatternsTab,
        StationTypesTab, FuelTab {

    private JobBlockMenu menu;
    private int index = -1;
    private int firstButton;

    /**
     * @return the label of the tab bar entry (its tooltip)
     */
    public abstract Component label();

    /**
     * @return the icon of the tab bar entry
     */
    public abstract ItemStack icon();

    /**
     * @return whether the entry sits below the panel instead of on top (such tabs come after the top ones)
     */
    public boolean below() {
        return false;
    }

    /**
     * Whether the tab bar shows the tab; a hidden tab keeps its index and cannot be selected.
     *
     * @return whether it is shown
     */
    public boolean shown() {
        return true;
    }

    /**
     * @return whether the tab needs the tall panel (the window is tall if one of its tabs is)
     */
    boolean tall() {
        return false;
    }

    /**
     * @return whether the player inventory is shown on the tab (to pick items from)
     */
    boolean showsInventory() {
        return false;
    }

    /**
     * @return how many menu button ids the tab handles ({@link #clickButton})
     */
    int buttons() {
        return 0;
    }

    /**
     * Presses one of the tab's menu buttons, on client and server alike.
     *
     * @param player the player
     * @param button the button, {@code 0} to {@link #buttons()} − 1
     * @return whether the press was taken
     */
    boolean clickButton(Player player, int button) {
        return false;
    }

    /**
     * @return the data the server sends while the window is open, if the tab shows any; tabs that show the same data
     *         return the same instance
     */
    public Optional<TabData<?>> synced() {
        return Optional.empty();
    }

    /**
     * Places the tab in its window.
     *
     * @param owner       the window
     * @param tabIndex    the tab index
     * @param buttonStart the window's id of the tab's first button
     */
    final void attach(JobBlockMenu owner, int tabIndex, int buttonStart) {
        this.menu = owner;
        this.index = tabIndex;
        this.firstButton = buttonStart;
    }

    /**
     * @return the window of the tab
     */
    protected final JobBlockMenu menu() {
        return menu;
    }

    /**
     * @return the tab index in its window
     */
    public final int index() {
        return index;
    }

    /**
     * @param button one of the tab's buttons
     * @return the menu button id that presses it in the window
     */
    public final int buttonId(int button) {
        return firstButton + button;
    }

    /**
     * @param buttonId a menu button id of the window
     * @return whether it is one of the tab's buttons
     */
    final boolean ownsButton(int buttonId) {
        return buttonId >= firstButton && buttonId < firstButton + buttons();
    }
}
