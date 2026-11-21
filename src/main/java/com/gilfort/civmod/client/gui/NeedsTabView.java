package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.menu.NeedsLayout;
import com.gilfort.civmod.menu.SettlerMenu;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The Needs tab of the settler window: per need tier a section, the tier the settler works in highlighted, higher tiers dimmed as
 * preview. The list scrolls when it is longer than the window.
 */
final class NeedsTabView {

    /** Y of the first line (panel coordinates). */
    private static final int TOP = 22;
    /** Top edge of the list's window: where the highlight of the first tier starts. */
    private static final int VIEW_TOP = TOP - 2;
    /** Gap between the list and its scrollbar. */
    private static final int SCROLLBAR_GAP = 4;

    private final SettlerMenu menu;
    private final ListScroller scroller;
    private int panelWidth;
    private int panelHeight;

    /**
     * @param screen the settler screen the tab is drawn on
     * @param menu   the settler menu
     */
    NeedsTabView(SettlerScreen screen, SettlerMenu menu) {
        this.menu = menu;
        this.scroller = new ListScroller(screen);
    }

    /**
     * @param width  the panel width
     * @param height the panel height
     */
    void init(int width, int height) {
        panelWidth = width;
        panelHeight = height;
    }

    /** Back to the top of the list. */
    void reset() {
        scroller.reset();
    }

    private boolean isActive() {
        return menu.getActiveTab() == SettlerMenu.TAB_NEEDS;
    }

    /** Bottom edge of the list's window (panel coordinates): the panel's border. */
    private int viewBottom() {
        return panelHeight - CivGui.PADDING;
    }

    /** Height of the list's window (panel coordinates), the scrollbar's height. */
    private int viewHeight() {
        return viewBottom() - VIEW_TOP;
    }

    /** How far the list scrolls, in pixels: 0 while it fits the window. */
    private int maxOffset() {
        return NeedsLayout.maxOffset(NeedsLayout.contentHeight(menu.getInfo().needs()), viewBottom() - TOP);
    }

    /** Right edge of the list's content (panel coordinates): left of the scrollbar while the list scrolls. */
    private int right(int maxOffset) {
        return panelWidth - CivGui.PADDING - (maxOffset > 0 ? CivGui.SCROLLBAR_WIDTH + SCROLLBAR_GAP : 0);
    }

    /** Left edge of the list's scrollbar (panel coordinates). */
    private int scrollbarX() {
        return panelWidth - CivGui.PADDING - CivGui.SCROLLBAR_WIDTH;
    }

    /**
     * The highlight of the tier the settler works in; it scrolls with the list and is clipped with it.
     *
     * @param graphics the graphics extractor
     * @param leftPos  the window's left edge on the screen
     * @param topPos   the window's top edge on the screen
     */
    void extractBackground(GuiGraphicsExtractor graphics, int leftPos, int topPos) {
        NeedsReport needs = menu.getInfo().needs();
        List<NeedsReport.Row> rows = needs.rows(needs.tier());
        if (!rows.isEmpty()) {
            int maxOffset = maxOffset();
            int right = right(maxOffset);
            int y = topPos + TOP + NeedsLayout.sectionTop(needs, needs.tier()) - scroller.offset(maxOffset);
            graphics.enableScissor(leftPos + CivGui.PADDING - 2, topPos + VIEW_TOP, leftPos + right + 2, topPos + viewBottom());
            CivGui.inset(graphics, leftPos + CivGui.PADDING - 2, y - 2, right - CivGui.PADDING + 4, NeedsLayout.sectionHeight(rows) + 2);
            graphics.disableScissor();
        }
    }

    /**
     * One section per tier with visible needs: the tier the settler works in highlighted, higher tiers dimmed as preview.
     * The list scrolls when it is longer than the window; what is scrolled out is clipped.
     *
     * @param graphics the graphics extractor
     * @param font     the font
     * @param leftPos  the window's left edge on the screen
     * @param topPos   the window's top edge on the screen
     * @param mouseX   the mouse's x on the screen
     * @param mouseY   the mouse's y on the screen
     */
    void extractLabels(GuiGraphicsExtractor graphics, Font font, int leftPos, int topPos, int mouseX, int mouseY) {
        NeedsReport needs = menu.getInfo().needs();
        int maxOffset = maxOffset();
        int offset = scroller.offset(maxOffset);
        int right = right(maxOffset);
        int width = right - CivGui.PADDING;
        boolean mouseInView = mouseY >= topPos + VIEW_TOP && mouseY < topPos + viewBottom();
        graphics.enableScissor(CivGui.PADDING - 2, VIEW_TOP, right + 2, viewBottom());
        for (int tier = 1; tier <= NeedState.MAX_TIER; tier++) {
            List<NeedsReport.Row> rows = needs.rows(tier);
            if (rows.isEmpty()) {
                continue;
            }
            boolean preview = tier > needs.tier();
            int y = TOP - offset + NeedsLayout.sectionTop(needs, tier);
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.settler.needs.tier." + tier), width), CivGui.PADDING, y,
                    preview ? CivGui.HINT_COLOR : CivGui.LABEL_COLOR, false);
            y += NeedsLayout.LINE;
            for (NeedsReport.Row row : rows) {
                Component mark = Component.literal(row.met() ? "✓" : "✗");
                int nameX = CivGui.PADDING + font.width(mark) + 3;
                graphics.text(font, mark, CivGui.PADDING, y, preview ? CivGui.HINT_COLOR : row.met() ? CivGui.NEED_MET_COLOR : CivGui.ERROR_COLOR, false);
                graphics.text(font, row.name(), nameX, y, preview ? CivGui.HINT_COLOR : CivGui.LABEL_COLOR, false);
                graphics.text(font, CivGui.fit(font, row.detail(), width - nameX + CivGui.PADDING), nameX, y + NeedsLayout.LINE, CivGui.HINT_COLOR, false);
                int iconX = nameX;
                for (ItemStack icon : row.icons()) {
                    if (iconX + NeedsLayout.ICON > right) {
                        break;
                    }
                    graphics.item(icon, iconX, y + 2 * NeedsLayout.LINE);
                    iconX += NeedsLayout.ICON;
                }
                int rowHeight = NeedsLayout.rowHeight(row);
                // Only a row that is shown has a tooltip: the mouse is inside the window and over the row.
                if (!row.tooltip().getString().isEmpty() && mouseInView && mouseX >= leftPos + CivGui.PADDING && mouseX < leftPos + right
                        && mouseY >= topPos + y && mouseY < topPos + y + rowHeight) {
                    CivGui.tooltip(graphics, font, row.tooltip(), mouseX, mouseY);
                }
                y += rowHeight;
            }
        }
        graphics.disableScissor();
        scroller.extract(graphics, scrollbarX(), VIEW_TOP, viewHeight(), maxOffset);
    }

    boolean mouseClicked(MouseButtonEvent event) {
        return isActive() && event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && scroller.press(event.x(), event.y(), scrollbarX(), VIEW_TOP, viewHeight(), maxOffset());
    }

    boolean mouseScrolled(double scrollY) {
        return isActive() && scroller.wheel(scrollY, maxOffset(), NeedsLayout.LINE);
    }

    boolean mouseDragged(MouseButtonEvent event) {
        return isActive() && scroller.drag(event.y(), VIEW_TOP, viewHeight(), maxOffset());
    }

    boolean mouseReleased() {
        return scroller.release();
    }
}
