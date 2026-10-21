package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.DeliveriesTab;
import com.gilfort.civmod.menu.JobBlockTab;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The Clerk's "Deliveries" tab of the {@link JobBlockScreen} (vault {@code Jobs/Deliveryman.md}); the client side of
 * {@link DeliveriesTab}: one row per open Delivery task (source icon and name, items waiting, the Deliveryman on it or
 * "Waiting" / "No Deliveryman") in the inset list of the Requests tab's layout; scrolls with the mouse wheel.
 */
final class DeliveriesTabView implements JobBlockTabView {

    private final JobBlockScreen screen;
    private final DeliveriesTab deliveries;
    private int offset;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    DeliveriesTabView(JobBlockScreen screen, DeliveriesTab tab) {
        this.screen = screen;
        this.deliveries = tab;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab shown) {
        RequestsTabView.extractList(screen, graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int scrolled = RequestsTabView.scrolled(screen, offset, deliveries.data().get().deliveries().size(), scrollY);
        if (scrolled < 0) {
            return false;
        }
        offset = scrolled;
        return true;
    }

    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        return List.of(Component.translatable("civmod.gui.clerk.deliveries.help.what"),
                Component.translatable("civmod.gui.clerk.deliveries.help.results"),
                Component.translatable("civmod.gui.clerk.deliveries.help.warehouse"));
    }

    /** Draws the tab: one row per open Delivery task. */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        List<ClerkTabData.DeliveryRow> rows = deliveries.data().get().deliveries();
        graphics.text(font, Component.translatable("civmod.gui.clerk.deliveries", rows.size()), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = screen.panelWidth() - 2 * left;
        int listY = RequestsTabView.LIST_Y;
        if (rows.isEmpty()) {
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.clerk.deliveries.none"), width), left,
                    listY + CivGui.INSET_PADDING, CivGui.HINT_COLOR, false);
            return;
        }
        int visible = RequestsTabView.rows(screen);
        offset = Math.clamp(offset, 0, Math.max(0, rows.size() - visible));
        if (rows.size() > visible) {
            width -= JobBlockScreen.SCROLL_COLUMN;
            int arrowX = left + width + 1;
            graphics.text(font, Component.literal("▲"), arrowX, listY + CivGui.INSET_PADDING,
                    offset > 0 ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
            graphics.text(font, Component.literal("▼"), arrowX, listY + CivGui.INSET_PADDING + visible * JobBlockScreen.ROW_HEIGHT - font.lineHeight,
                    offset + visible < rows.size() ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
        }
        for (int row = 0; row < Math.min(visible, rows.size() - offset); row++) {
            ClerkTabData.DeliveryRow delivery = rows.get(offset + row);
            int y = listY + CivGui.INSET_PADDING + row * JobBlockScreen.ROW_HEIGHT;
            graphics.item(delivery.icon(), left, y + 2);
            Component items = Component.translatable("civmod.gui.clerk.delivery.items", delivery.items());
            int itemsWidth = font.width(items);
            graphics.text(font, items, left + width - itemsWidth, y + 1, CivGui.HINT_COLOR, false);
            graphics.text(font, CivGui.fit(font, delivery.source(), width - 24 - itemsWidth), left + 20, y + 1, CivGui.LABEL_COLOR, false);
            graphics.text(font, CivGui.fit(font, delivery.status(), width - 20), left + 20, y + 11, CivGui.HINT_COLOR, false);
            if (mouseX >= screen.left() + left && mouseX < screen.left() + left + width && mouseY >= screen.top() + y
                    && mouseY < screen.top() + y + JobBlockScreen.ROW_HEIGHT) {
                graphics.setComponentTooltipForNextFrame(font, List.of(delivery.source(), items, delivery.status()), mouseX, mouseY);
            }
        }
    }
}
