package com.gilfort.civmod.menu;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/**
 * A slot bound to one or more tabs of a {@link CivMenu}; it is inactive (hidden and not clickable) while another tab
 * is selected.
 */
public class CivSlot extends Slot {

    private final CivMenu menu;
    private final int[] tabs;

    /**
     * @param menu      the owning menu
     * @param container the backing container
     * @param index     the index within the container
     * @param x         the x position relative to the screen
     * @param y         the y position relative to the screen
     * @param tabs      the tab indices the slot is active on, or {@link CivMenu#ALL_TABS}
     */
    public CivSlot(CivMenu menu, Container container, int index, int x, int y, int... tabs) {
        super(container, index, x, y);
        this.menu = menu;
        this.tabs = tabs.clone();
    }

    @Override
    public boolean isActive() {
        for (int tab : tabs) {
            if (menu.isTabActive(tab)) {
                return true;
            }
        }
        return false;
    }
}
