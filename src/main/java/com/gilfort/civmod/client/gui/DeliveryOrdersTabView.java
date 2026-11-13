package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.menu.DeliveryOrdersTab;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.registry.ModDataComponents;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Clerk's "Delivery Orders" tab of the {@link JobBlockScreen} (vault {@code Jobs/Deliveryman.md}); the client side of
 * {@link DeliveryOrdersTab}: the slot grid for filled Delivery Slips and a line with the slot count. A slip whose source
 * or target is gone is marked in its slot (the details are in its tooltip).
 */
final class DeliveryOrdersTabView implements JobBlockTabView {

    /** Size of an item in its slot, in pixels. */
    private static final int ITEM_SIZE = 16;
    /** Panel y of the line with the slot count. */
    private static final int COUNT_Y = 22;

    private final JobBlockScreen screen;
    private final DeliveryOrdersTab orders;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    DeliveryOrdersTabView(JobBlockScreen screen, DeliveryOrdersTab tab) {
        this.screen = screen;
        this.orders = tab;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab shown) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.isActive() && !(slot.container instanceof Inventory) && slot.hasItem()) {
                DeliverySlip.Status status = status(slot.getItem());
                if (status != DeliverySlip.Status.OK) {
                    graphics.fill(screen.left() + slot.x, screen.top() + slot.y, screen.left() + slot.x + ITEM_SIZE,
                            screen.top() + slot.y + ITEM_SIZE, CivGui.MISSING_TINT);
                }
            }
        }
    }

    private static DeliverySlip.Status status(ItemStack stack) {
        DeliverySlip slip = stack.get(ModDataComponents.DELIVERY_SLIP.get());
        return slip == null ? DeliverySlip.Status.OK : slip.status();
    }

    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        graphics.text(font, Component.translatable("civmod.gui.orders.slots", orders.slots()), CivGui.PADDING, COUNT_Y,
                CivGui.LABEL_COLOR, false);
    }

    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        return List.of(Component.translatable("civmod.gui.orders.help.what"), Component.translatable("civmod.gui.orders.help.priority"),
                Component.translatable("civmod.gui.orders.help.keep"), Component.translatable("civmod.gui.orders.help.missing"));
    }
}
