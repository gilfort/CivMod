package com.gilfort.civmod.menu;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/**
 * A slot bound to a tab of a {@link CivMenu}; it is inactive (hidden and not clickable) while another tab is selected.
 */
public class CivSlot extends Slot {

    private final CivMenu menu;
    private final int tab;

    /**
     * @param menu      the owning menu
     * @param container the backing container
     * @param index     the index within the container
     * @param x         the x position relative to the screen
     * @param y         the y position relative to the screen
     * @param tab       the tab index or {@link CivMenu#ALL_TABS}
     */
    public CivSlot(CivMenu menu, Container container, int index, int x, int y, int tab) {
        super(container, index, x, y);
        this.menu = menu;
        this.tab = tab;
    }

    /**
     * @return the tab index or {@link CivMenu#ALL_TABS}
     */
    public int getTab() {
        return tab;
    }

    @Override
    public boolean isActive() {
        return menu.isTabActive(tab);
    }
}
