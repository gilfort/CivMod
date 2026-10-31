package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.TasksTab;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Clerk's "Tasks" tab of the {@link JobBlockScreen} (vault {@code Jobs/Clerk.md}); the client side of
 * {@link TasksTab}: one row per request and Delivery task (item icon, short text, status icon on the right; the details
 * on hover) in an inset list, blocked first, then in progress, then waiting. The list scrolls (mouse wheel, scrollbar).
 */
final class TasksTabView implements JobBlockTabView {

    /** Top of the list inset (panel coordinates). */
    private static final int LIST_Y = 34;
    /** Height of a row: an item and one line of text. */
    private static final int ROW_HEIGHT = 18;
    /** Gap between the rows and the scrollbar. */
    private static final int SCROLLBAR_GAP = 2;

    private final JobBlockScreen screen;
    private final TasksTab tasks;
    private final ListScroller scroller;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    TasksTabView(JobBlockScreen screen, TasksTab tab) {
        this.screen = screen;
        this.tasks = tab;
        this.scroller = new ListScroller(screen);
    }

    private List<ClerkTabData.TaskRow> rows() {
        return tasks.data().get().tasks();
    }

    /** Top of the first row (panel coordinates). */
    private static int rowsY() {
        return LIST_Y + CivGui.INSET_PADDING;
    }

    /** How many rows fit the inset. */
    private int visible() {
        return Math.max(1, (screen.panelHeight() - CivGui.PADDING - rowsY() - CivGui.INSET_PADDING) / ROW_HEIGHT);
    }

    private int maxOffset() {
        return Math.max(0, rows().size() - visible());
    }

    /** Left edge of the scrollbar (panel coordinates): inside the inset's right edge. */
    private int scrollbarX() {
        return screen.panelWidth() - CivGui.PADDING - CivGui.INSET_PADDING - CivGui.SCROLLBAR_WIDTH;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab shown) {
        CivGui.inset(graphics, screen.left() + CivGui.PADDING, screen.top() + LIST_Y, screen.panelWidth() - 2 * CivGui.PADDING,
                screen.panelHeight() - LIST_Y - CivGui.PADDING);
    }

    @Override
    public void onTabChanged() {
        scroller.release();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        return event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && scroller.press(event.x(), event.y(), scrollbarX(), rowsY(), visible() * ROW_HEIGHT, maxOffset());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return scroller.wheel(scrollY, maxOffset());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event) {
        return scroller.drag(event.y(), rowsY(), visible() * ROW_HEIGHT, maxOffset());
    }

    @Override
    public boolean mouseReleased() {
        return scroller.release();
    }

    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        return List.of(Component.translatable("civmod.gui.clerk.tasks.help.what"), Component.translatable("civmod.gui.clerk.tasks.help.in_progress"),
                Component.translatable("civmod.gui.clerk.tasks.help.waiting"), Component.translatable("civmod.gui.clerk.tasks.help.blocked"),
                Component.translatable("civmod.gui.clerk.deliveries.help.what"), Component.translatable("civmod.gui.clerk.deliveries.help.results"),
                Component.translatable("civmod.gui.clerk.deliveries.help.warehouse"));
    }

    /** Draws the tab: the task count, then one row per task; the details of the hovered row as its tooltip. */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        List<ClerkTabData.TaskRow> rows = rows();
        graphics.text(font, Component.translatable("civmod.gui.clerk.tasks", rows.size()), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int right = screen.panelWidth() - left;
        if (rows.isEmpty()) {
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.clerk.tasks.none"), right - left), left, rowsY() + 1,
                    CivGui.HINT_COLOR, false);
            return;
        }
        int max = maxOffset();
        int offset = scroller.offset(max);
        if (max > 0) {
            scroller.extract(graphics, scrollbarX(), rowsY(), visible() * ROW_HEIGHT, max);
            right = scrollbarX() - SCROLLBAR_GAP;
        }
        for (int row = 0; row < Math.min(visible(), rows.size() - offset); row++) {
            ClerkTabData.TaskRow task = rows.get(offset + row);
            int y = rowsY() + row * ROW_HEIGHT;
            graphics.item(task.icon(), left, y + 1);
            // A request's icon carries its amount.
            graphics.itemDecorations(font, task.icon(), left, y + 1);
            Component status = task.status().icon();
            int statusWidth = font.width(status);
            int textY = y + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
            graphics.text(font, status, right - statusWidth, textY, task.status().color(), false);
            graphics.text(font, CivGui.fit(font, task.text(), right - statusWidth - 4 - left - 20), left + 20, textY, CivGui.LABEL_COLOR, false);
            if (mouseX >= screen.left() + left && mouseX < screen.left() + right && mouseY >= screen.top() + y
                    && mouseY < screen.top() + y + ROW_HEIGHT) {
                graphics.setComponentTooltipForNextFrame(font, task.details(), mouseX, mouseY);
            }
        }
    }
}
