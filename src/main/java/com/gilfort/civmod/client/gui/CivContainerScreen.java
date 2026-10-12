package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.menu.CivMenu;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Base class for all container screens of CivMod: vanilla-style panel in any size, optional tabs, slot backgrounds
 * for every active slot and widgets bound to tabs.
 *
 * @param <T> the menu type
 */
public abstract class CivContainerScreen<T extends CivMenu> extends AbstractContainerScreen<T> {

    private final List<TabBar.Tab> tabs;
    private final List<TabWidget> tabWidgets = new ArrayList<>();
    private TabBar tabBar;

    private record TabWidget(AbstractWidget widget, int tab) {
    }

    /**
     * @param menu        the menu
     * @param inventory   the player inventory
     * @param title       the title
     * @param imageWidth  the panel width
     * @param imageHeight the panel height
     * @param tabs        the tabs (must match {@link CivMenu#getTabCount()}), empty for no tab row
     */
    protected CivContainerScreen(T menu, Inventory inventory, Component title, int imageWidth, int imageHeight, List<TabBar.Tab> tabs) {
        super(menu, inventory, title, imageWidth, imageHeight);
        this.tabs = List.copyOf(tabs);
    }

    @Override
    protected void init() {
        super.init();
        tabWidgets.clear();
        List<TabBar.Tab> bottom = bottomTabs();
        tabBar = tabs.isEmpty() && bottom.isEmpty() ? null : new TabBar(tabs, bottom, leftPos, topPos, imageHeight);
        if (tabBar != null && menu.getActiveTab() >= tabBar.size()) {
            // The selected tab disappeared (e.g. a job tab after removing the job).
            menu.setActiveTab(0);
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CivMenu.tabButtonId(0));
        }
        initContent();
        updateTabVisibility();
    }

    /**
     * Adds the widgets of the screen; called from {@link #init()} after the layout is known.
     */
    protected abstract void initContent();

    /**
     * Tabs below the panel (e.g. one per job); re-evaluated on every {@link #init()}. Their indices continue after
     * the top tabs.
     *
     * @return the bottom tabs
     */
    protected List<TabBar.Tab> bottomTabs() {
        return List.of();
    }

    /**
     * Adds a widget that is only visible while the given tab is selected.
     *
     * @param tab    the tab index or {@link CivMenu#ALL_TABS}
     * @param widget the widget
     * @param <W>    the widget type
     * @return the widget
     */
    protected <W extends AbstractWidget> W addTabWidget(int tab, W widget) {
        addRenderableWidget(widget);
        tabWidgets.add(new TabWidget(widget, tab));
        return widget;
    }

    /**
     * Called after the selected tab changed.
     *
     * @param tab the new tab index
     */
    protected void onTabChanged(int tab) {
    }

    /**
     * Selects a tab on client and server.
     *
     * @param tab the tab index
     */
    protected void selectTab(int tab) {
        if (tab == menu.getActiveTab()) {
            return;
        }
        menu.setActiveTab(tab);
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CivMenu.tabButtonId(tab));
        updateTabVisibility();
        onTabChanged(tab);
    }

    private void updateTabVisibility() {
        for (TabWidget entry : tabWidgets) {
            entry.widget().visible = menu.isTabActive(entry.tab());
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (tabBar != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int tab = tabBar.tabAt(event.x(), event.y());
            if (tab >= 0) {
                selectTab(tab);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        if (tabBar != null) {
            tabBar.extractUnselected(graphics, menu.getActiveTab(), mouseX, mouseY);
        }
        CivGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        if (tabBar != null) {
            tabBar.extractSelected(graphics, menu.getActiveTab());
        }
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                CivGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        extractTabBackground(graphics, menu.getActiveTab(), mouseX, mouseY);
    }

    /**
     * Draws additional background content of the selected tab (absolute coordinates).
     *
     * @param graphics the graphics extractor
     * @param tab      the selected tab
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     */
    protected void extractTabBackground(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, titleLabelX, titleLabelY, CivGui.LABEL_COLOR, false);
        if (showsPlayerInventory()) {
            graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, CivGui.LABEL_COLOR, false);
        }
        extractTabLabels(graphics, menu.getActiveTab(), mouseX, mouseY);
    }

    /**
     * Draws texts of the selected tab (coordinates relative to the panel).
     *
     * @param graphics the graphics extractor
     * @param tab      the selected tab
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     */
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
    }

    private boolean showsPlayerInventory() {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.isActive()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        if (tabBar != null) {
            tabBar.extractTooltip(graphics, font, mouseX, mouseY);
        }
    }
}
