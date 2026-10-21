package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.RequestsTab;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The Clerk's "Requests" tab of the {@link JobBlockScreen} (vault {@code Jobs/Clerk.md}); the client side of
 * {@link RequestsTab}: one row per request of this Clerk's rules (item, missing amount, priority, status) in an inset
 * list that scrolls with the mouse wheel. The Deliveries tab draws its list the same way ({@link #LIST_Y},
 * {@link #rows}, {@link #extractList}).
 */
final class RequestsTabView implements JobBlockTabView {

    /** Top of the list inset (panel coordinates). */
    static final int LIST_Y = 34;

    private final JobBlockScreen screen;
    private final RequestsTab requests;
    private int offset;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    RequestsTabView(JobBlockScreen screen, RequestsTab tab) {
        this.screen = screen;
        this.requests = tab;
    }

    /**
     * @param screen the screen
     * @return how many rows of a Clerk list fit the panel
     */
    static int rows(JobBlockScreen screen) {
        return (screen.panelHeight() - LIST_Y - CivGui.PADDING - 2 * CivGui.INSET_PADDING) / JobBlockScreen.ROW_HEIGHT;
    }

    /**
     * Draws the inset of a Clerk list (absolute coordinates).
     *
     * @param screen   the screen
     * @param graphics the graphics extractor
     */
    static void extractList(JobBlockScreen screen, GuiGraphicsExtractor graphics) {
        CivGui.inset(graphics, screen.left() + CivGui.PADDING, screen.top() + LIST_Y, screen.panelWidth() - 2 * CivGui.PADDING,
                screen.panelHeight() - LIST_Y - CivGui.PADDING);
    }

    /**
     * Scrolls a Clerk list by one row per wheel step.
     *
     * @param screen  the screen
     * @param offset  the first row shown
     * @param size    the number of rows
     * @param scrollY the wheel movement
     * @return the new first row, or {@code -1} if the list does not scroll
     */
    static int scrolled(JobBlockScreen screen, int offset, int size, double scrollY) {
        int rows = rows(screen);
        return size > rows ? Math.clamp(offset - (int) Math.signum(scrollY), 0, size - rows) : -1;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab shown) {
        extractList(screen, graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int scrolled = scrolled(screen, offset, requests.data().get().requests().size(), scrollY);
        if (scrolled < 0) {
            return false;
        }
        offset = scrolled;
        return true;
    }

    /**
     * Draws the tab: one row per request of this Clerk's rules (item, missing amount, priority, status); scrolls with
     * the mouse wheel.
     */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        List<ClerkTabData.RequestRow> rows = requests.data().get().requests();
        graphics.text(font, Component.translatable("civmod.gui.clerk.requests", rows.size()), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = screen.panelWidth() - 2 * left;
        if (rows.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.clerk.requests.none"), left,
                    LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        int visible = rows(screen);
        offset = Math.clamp(offset, 0, Math.max(0, rows.size() - visible));
        if (rows.size() > visible) {
            width -= JobBlockScreen.SCROLL_COLUMN;
            int arrowX = left + width + 1;
            graphics.text(font, Component.literal("▲"), arrowX, LIST_Y + CivGui.INSET_PADDING,
                    offset > 0 ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
            graphics.text(font, Component.literal("▼"), arrowX, LIST_Y + CivGui.INSET_PADDING + visible * JobBlockScreen.ROW_HEIGHT - font.lineHeight,
                    offset + visible < rows.size() ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
        }
        for (int row = 0; row < Math.min(visible, rows.size() - offset); row++) {
            ClerkTabData.RequestRow request = rows.get(offset + row);
            int y = LIST_Y + CivGui.INSET_PADDING + row * JobBlockScreen.ROW_HEIGHT;
            graphics.item(new ItemStack(request.item()), left, y + 2);
            Component priority = Component.translatable("civmod.gui.clerk.request.priority", request.priority());
            int priorityWidth = font.width(priority);
            graphics.text(font, priority, left + width - priorityWidth, y + 1, CivGui.HINT_COLOR, false);
            Component title = Component.translatable("civmod.gui.clerk.request.amount", request.amount(), new ItemStack(request.item()).getHoverName());
            graphics.text(font, CivGui.fit(font, title, width - 24 - priorityWidth), left + 20, y + 1, CivGui.LABEL_COLOR, false);
            graphics.text(font, CivGui.fit(font, request.status(), width - 20), left + 20, y + 11, CivGui.HINT_COLOR, false);
            if (mouseX >= screen.left() + left && mouseX < screen.left() + left + width && mouseY >= screen.top() + y
                    && mouseY < screen.top() + y + JobBlockScreen.ROW_HEIGHT) {
                graphics.setComponentTooltipForNextFrame(font, List.of(title, request.status(), priority), mouseX, mouseY);
            }
        }
    }
}
