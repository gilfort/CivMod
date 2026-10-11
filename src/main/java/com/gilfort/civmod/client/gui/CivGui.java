package com.gilfort.civmod.client.gui;

import com.gilfort.civmod.CivMod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Shared constants and drawing helpers for the vanilla-style CivMod GUIs.
 */
public final class CivGui {

    /** Default label colour of vanilla container screens (dark grey). */
    public static final int LABEL_COLOR = 0xFF404040;

    /** Colour for hint texts on disabled / unavailable content. */
    public static final int HINT_COLOR = 0xFF707070;

    /** Nine-slice panel sprite in the vanilla container style; scales to any size. */
    public static final Identifier PANEL = CivMod.id("container/background");

    /** Vanilla 18×18 slot sprite. */
    public static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");

    /** Inner padding between the panel edge and its content. */
    public static final int PADDING = 8;

    private CivGui() {
    }

    /**
     * Draws a vanilla-style panel.
     *
     * @param graphics the graphics extractor
     * @param x        the left edge
     * @param y        the top edge
     * @param width    the width
     * @param height   the height
     */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, width, height);
    }

    /**
     * Draws a vanilla slot background for an item at the given position.
     *
     * @param graphics the graphics extractor
     * @param itemX    the x position of the item (slot position)
     * @param itemY    the y position of the item (slot position)
     */
    public static void slot(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, itemX - 1, itemY - 1, 18, 18);
    }
}
