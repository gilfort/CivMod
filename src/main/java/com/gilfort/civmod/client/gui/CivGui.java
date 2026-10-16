package com.gilfort.civmod.client.gui;

import com.gilfort.civmod.CivMod;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
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

    /** Vanilla HUD food sprites. */
    private static final Identifier FOOD_EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
    private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
    /** Saturation outline sprites (AppleSkin style). */
    private static final Identifier SATURATION_HALF = CivMod.id("hud/saturation_half");
    private static final Identifier SATURATION_FULL = CivMod.id("hud/saturation_full");
    private static final int FOOD_ICON_STEP = 8;

    /** Inner padding between the panel edge and its content. */
    public static final int PADDING = 8;

    /** Padding of content inside an {@link #inset}. */
    public static final int INSET_PADDING = 4;

    /** Vanilla slot colors used by {@link #inset}. */
    private static final int INSET_DARK = 0xFF373737;
    private static final int INSET_LIGHT = 0xFFFFFFFF;
    private static final int INSET_FILL = 0xFF8B8B8B;

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

    /**
     * Draws a sunken area in the colors of a vanilla slot (dark top-left edge, light bottom-right edge), e.g. for
     * lists.
     *
     * @param graphics the graphics extractor
     * @param x        the left edge
     * @param y        the top edge
     * @param width    the width
     * @param height   the height
     */
    public static void inset(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, INSET_LIGHT);
        graphics.fill(x, y, x + width - 1, y + height - 1, INSET_DARK);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, INSET_FILL);
    }

    /**
     * Draws a food bar like the player HUD (10 drumsticks, halves included) plus an AppleSkin-style golden outline for
     * the saturation buffer.
     *
     * @param graphics   the graphics extractor
     * @param x          the left edge
     * @param y          the top edge
     * @param level      the food level (0–20)
     * @param saturation the saturation buffer (0–20)
     */
    public static void foodBar(GuiGraphicsExtractor graphics, int x, int y, int level, float saturation) {
        int saturationPoints = (int) Math.ceil(saturation);
        for (int i = 0; i < 10; i++) {
            // Like the player HUD: the first drumstick is the rightmost one, the bar empties from the left.
            int iconX = x + (9 - i) * FOOD_ICON_STEP;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_EMPTY, iconX, y, 9, 9);
            int food = level - i * 2;
            if (food >= 2) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_FULL, iconX, y, 9, 9);
            } else if (food == 1) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_HALF, iconX, y, 9, 9);
            }
            int buffer = saturationPoints - i * 2;
            if (buffer >= 2) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SATURATION_FULL, iconX, y, 9, 9);
            } else if (buffer == 1) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SATURATION_HALF, iconX, y, 9, 9);
            }
        }
    }

    /**
     * Shortens a text with an ellipsis so it fits into a width.
     *
     * @param font  the font
     * @param text  the text
     * @param width the available width
     * @return the text, or its shortened plain version
     */
    public static Component fit(Font font, Component text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        return Component.literal(font.plainSubstrByWidth(text.getString(), width - font.width("…")) + "…");
    }
}
