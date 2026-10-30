package com.gilfort.civmod.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The row offset of a list or grid that scrolls like the Found tab: one row per wheel step, or a vanilla scrollbar
 * ({@link CivGui#scrollbar}) whose scroller follows the mouse while it is dragged. The owner gives the scrollbar's place
 * (panel coordinates) and the largest offset on every call, so the scroller never keeps stale layout.
 */
final class ListScroller {

    private final JobBlockScreen screen;
    private int offset;
    private boolean dragging;

    /**
     * @param screen the screen the list is drawn on
     */
    ListScroller(JobBlockScreen screen) {
        this.screen = screen;
    }

    /**
     * @param maxOffset the largest offset
     * @return the first row shown, kept within the list
     */
    int offset(int maxOffset) {
        offset = Math.clamp(offset, 0, Math.max(0, maxOffset));
        return offset;
    }

    /** Shows the list from its first row. */
    void reset() {
        offset = 0;
        dragging = false;
    }

    /**
     * Draws the scrollbar if the list scrolls.
     *
     * @param graphics  the graphics extractor (panel coordinates)
     * @param x         the scrollbar's left edge
     * @param y         its top edge
     * @param height    its height
     * @param maxOffset the largest offset
     */
    void extract(GuiGraphicsExtractor graphics, int x, int y, int height, int maxOffset) {
        if (maxOffset > 0) {
            CivGui.scrollbar(graphics, x, y, height, offset(maxOffset), maxOffset);
        }
    }

    /**
     * @param scrollY   the wheel movement
     * @param maxOffset the largest offset
     * @return whether the list scrolls (the wheel was taken)
     */
    boolean wheel(double scrollY, int maxOffset) {
        if (maxOffset <= 0) {
            return false;
        }
        offset = Math.clamp(offset - (int) Math.signum(scrollY), 0, maxOffset);
        return true;
    }

    /**
     * A click on the scrollbar starts dragging it.
     *
     * @param mouseX    the mouse x position (absolute)
     * @param mouseY    the mouse y position (absolute)
     * @param x         the scrollbar's left edge (panel coordinates)
     * @param y         its top edge
     * @param height    its height
     * @param maxOffset the largest offset
     * @return whether the click was on the scrollbar
     */
    boolean press(double mouseX, double mouseY, int x, int y, int height, int maxOffset) {
        double relX = mouseX - screen.left();
        double relY = mouseY - screen.top();
        if (maxOffset <= 0 || relX < x || relX >= x + CivGui.SCROLLBAR_WIDTH || relY < y || relY >= y + height) {
            return false;
        }
        dragging = true;
        drag(mouseY, y, height, maxOffset);
        return true;
    }

    /**
     * Moves the list so the scroller's centre follows the mouse while it is dragged.
     *
     * @param mouseY    the mouse y position (absolute)
     * @param y         the scrollbar's top edge (panel coordinates)
     * @param height    its height
     * @param maxOffset the largest offset
     * @return whether the scrollbar is being dragged
     */
    boolean drag(double mouseY, int y, int height, int maxOffset) {
        if (!dragging) {
            return false;
        }
        int scroller = CivGui.scrollerHeight(height);
        int track = height - scroller;
        if (track > 0 && maxOffset > 0) {
            double at = mouseY - screen.top() - y - scroller / 2.0;
            offset = Math.clamp((int) Math.round(at / track * maxOffset), 0, maxOffset);
        }
        return true;
    }

    /**
     * @return whether the scrollbar was being dragged
     */
    boolean release() {
        boolean was = dragging;
        dragging = false;
        return was;
    }
}
