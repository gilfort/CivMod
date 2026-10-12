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
 * Vanilla-style tab rows above and below a panel, using the creative inventory tab sprites.
 * <p>
 * Tab indices are global: first the top tabs, then the bottom tabs. Unselected tabs are drawn behind the panel, the
 * selected tab in front of it so it merges with the panel.
 */
public final class TabBar {

    /** Width of a tab sprite. */
    public static final int TAB_WIDTH = 26;
    /** Height of a tab sprite. */
    public static final int TAB_HEIGHT = 32;
    /** How far a tab sticks out of the panel. */
    public static final int TAB_OFFSET = 28;
    /** Horizontal distance between two tabs. */
    private static final int TAB_SPACING = 27;

    private static final Identifier[] TOP_SELECTED = sprites("top_selected");
    private static final Identifier[] TOP_UNSELECTED = sprites("top_unselected");
    private static final Identifier[] BOTTOM_SELECTED = sprites("bottom_selected");
    private static final Identifier[] BOTTOM_UNSELECTED = sprites("bottom_unselected");

    /**
     * A tab definition.
     *
     * @param title the title (shown as tooltip)
     * @param icon  the icon item
     */
    public record Tab(Component title, ItemStack icon) {
    }

    private final List<Tab> top;
    private final List<Tab> bottom;
    private final int panelX;
    private final int panelY;
    private final int panelHeight;

    /**
     * @param top         the tabs above the panel, left to right
     * @param bottom      the tabs below the panel, left to right (indices continue after the top tabs)
     * @param panelX      the left edge of the panel
     * @param panelY      the top edge of the panel
     * @param panelHeight the height of the panel
     */
    public TabBar(List<Tab> top, List<Tab> bottom, int panelX, int panelY, int panelHeight) {
        this.top = List.copyOf(top);
        this.bottom = List.copyOf(bottom);
        this.panelX = panelX;
        this.panelY = panelY;
        this.panelHeight = panelHeight;
    }

    private static Identifier[] sprites(String state) {
        Identifier[] sprites = new Identifier[7];
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = Identifier.withDefaultNamespace("container/creative_inventory/tab_" + state + "_" + (i + 1));
        }
        return sprites;
    }

    /**
     * @return the total number of tabs
     */
    public int size() {
        return top.size() + bottom.size();
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
        for (int i = 0; i < size(); i++) {
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
        if (selected >= 0 && selected < size()) {
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
            graphics.setTooltipForNextFrame(font, tab(tab).title(), mouseX, mouseY);
        }
    }

    /**
     * @param mouseX the mouse x position
     * @param mouseY the mouse y position
     * @return the index of the tab under the mouse, or {@code -1}
     */
    public int tabAt(double mouseX, double mouseY) {
        for (int i = 0; i < size(); i++) {
            if (isOver(i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    private Tab tab(int index) {
        return index < top.size() ? top.get(index) : bottom.get(index - top.size());
    }

    private boolean isTop(int index) {
        return index < top.size();
    }

    private int column(int index) {
        return isTop(index) ? index : index - top.size();
    }

    private boolean isOver(int index, double mouseX, double mouseY) {
        int x = panelX + column(index) * TAB_SPACING;
        int y0 = isTop(index) ? panelY - TAB_OFFSET : panelY + panelHeight;
        int y1 = isTop(index) ? panelY : panelY + panelHeight + TAB_OFFSET;
        return mouseX >= x && mouseX < x + TAB_WIDTH && mouseY >= y0 && mouseY < y1;
    }

    private void extractTab(GuiGraphicsExtractor graphics, int index, boolean selected) {
        boolean isTop = isTop(index);
        int column = column(index);
        int x = panelX + column * TAB_SPACING;
        int y = isTop ? panelY - TAB_OFFSET : panelY + panelHeight - (TAB_HEIGHT - TAB_OFFSET);
        Identifier[] sprites = isTop ? (selected ? TOP_SELECTED : TOP_UNSELECTED) : (selected ? BOTTOM_SELECTED : BOTTOM_UNSELECTED);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprites[Math.min(column, sprites.length - 1)], x, y, TAB_WIDTH, TAB_HEIGHT);
        graphics.item(tab(index).icon(), x + TAB_WIDTH / 2 - 8, y + TAB_HEIGHT / 2 - 8 + (isTop ? 1 : -1));
    }
}
