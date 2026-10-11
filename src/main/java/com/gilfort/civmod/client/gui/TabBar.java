package com.gilfort.civmod.client.gui;

import java.util.List;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Vanilla-style tab row on top of a panel, using the creative inventory tab sprites.
 * <p>
 * Unselected tabs are drawn behind the panel, the selected tab in front of it so it merges with the panel.
 */
public final class TabBar {

    /** Width of a tab sprite. */
    public static final int TAB_WIDTH = 26;
    /** Height of a tab sprite. */
    public static final int TAB_HEIGHT = 32;
    /** How far a tab sticks out above the panel. */
    public static final int TAB_OFFSET = 28;
    /** Horizontal distance between two tabs. */
    private static final int TAB_SPACING = 27;

    private static final Identifier[] SELECTED = sprites("selected");
    private static final Identifier[] UNSELECTED = sprites("unselected");

    /**
     * A tab definition.
     *
     * @param title the title (shown as tooltip)
     * @param icon  the icon item
     */
    public record Tab(Component title, ItemStack icon) {
    }

    private final List<Tab> tabs;
    private final int panelX;
    private final int panelY;

    /**
     * @param tabs   the tabs, left to right
     * @param panelX the left edge of the panel
     * @param panelY the top edge of the panel
     */
    public TabBar(List<Tab> tabs, int panelX, int panelY) {
        this.tabs = List.copyOf(tabs);
        this.panelX = panelX;
        this.panelY = panelY;
    }

    private static Identifier[] sprites(String state) {
        Identifier[] sprites = new Identifier[7];
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = Identifier.withDefaultNamespace("container/creative_inventory/tab_top_" + state + "_" + (i + 1));
        }
        return sprites;
    }

    /**
     * Draws all unselected tabs; call before drawing the panel.
     *
     * @param graphics the graphics extractor
     * @param selected the selected tab index
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     */
    public void extractUnselected(GuiGraphicsExtractor graphics, int selected, int mouseX, int mouseY) {
        for (int i = 0; i < tabs.size(); i++) {
            if (i != selected) {
                extractTab(graphics, i, false);
                if (isOver(i, mouseX, mouseY)) {
                    graphics.requestCursor(CursorTypes.POINTING_HAND);
                }
            }
        }
    }

    /**
     * Draws the selected tab; call after drawing the panel.
     *
     * @param graphics the graphics extractor
     * @param selected the selected tab index
     */
    public void extractSelected(GuiGraphicsExtractor graphics, int selected) {
        if (selected >= 0 && selected < tabs.size()) {
            extractTab(graphics, selected, true);
        }
    }

    /**
     * Shows the title of the hovered tab as tooltip.
     *
     * @param graphics the graphics extractor
     * @param font     the font
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     */
    public void extractTooltip(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        int tab = tabAt(mouseX, mouseY);
        if (tab >= 0) {
            graphics.setTooltipForNextFrame(font, tabs.get(tab).title(), mouseX, mouseY);
        }
    }

    /**
     * @param mouseX the mouse x position
     * @param mouseY the mouse y position
     * @return the index of the tab under the mouse, or {@code -1}
     */
    public int tabAt(double mouseX, double mouseY) {
        for (int i = 0; i < tabs.size(); i++) {
            if (isOver(i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isOver(int tab, double mouseX, double mouseY) {
        int x = tabX(tab);
        int y = panelY - TAB_OFFSET;
        return mouseX >= x && mouseX < x + TAB_WIDTH && mouseY >= y && mouseY < panelY;
    }

    private int tabX(int tab) {
        return panelX + tab * TAB_SPACING;
    }

    private void extractTab(GuiGraphicsExtractor graphics, int tab, boolean selected) {
        int x = tabX(tab);
        int y = panelY - TAB_OFFSET;
        Identifier[] sprites = selected ? SELECTED : UNSELECTED;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprites[Math.min(tab, sprites.length - 1)], x, y, TAB_WIDTH, TAB_HEIGHT);
        graphics.item(tabs.get(tab).icon(), x + TAB_WIDTH / 2 - 8, y + TAB_HEIGHT / 2 - 7);
    }
}
