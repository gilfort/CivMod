package com.gilfort.civmod.client.gui;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.menu.DeliveryOrdersTab;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.registry.ModDataComponents;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Clerk's "Delivery Orders" tab of the {@link JobBlockScreen} (vault {@code Jobs/Deliveryman.md}); the client side of
 * {@link DeliveryOrdersTab}: the slot grid for filled Delivery Slips, a line with the slot count and the "Auto" switch of
 * the Deliverymen. A slip whose source or target is gone is marked in its slot (the details are in its tooltip).
 */
final class DeliveryOrdersTabView implements JobBlockTabView {

    private static final int BUTTON_WIDTH = 56;
    private static final int BUTTON_HEIGHT = 14;
    private static final int BUTTON_Y = 18;
    /** Colour of the marker on a slip that cannot be worked. */
    private static final int MISSING_COLOR = 0x80FF0000;

    private final JobBlockScreen screen;
    private final DeliveryOrdersTab orders;
    private @Nullable Button auto;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    DeliveryOrdersTabView(JobBlockScreen screen, DeliveryOrdersTab tab) {
        this.screen = screen;
        this.orders = tab;
    }

    @Override
    public void init() {
        int x = screen.left() + screen.panelWidth() - CivGui.PADDING - BUTTON_WIDTH;
        auto = screen.addWidget(orders.index(), Button.builder(Component.empty(), button -> screen.clickMenuButton(orders.autoButtonId()))
                .bounds(x, screen.top() + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab shown) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.isActive() && !(slot.container instanceof Inventory) && slot.hasItem()) {
                DeliverySlip.Status status = status(slot.getItem());
                if (status != DeliverySlip.Status.OK) {
                    graphics.fill(screen.left() + slot.x, screen.top() + slot.y, screen.left() + slot.x + 16, screen.top() + slot.y + 16, MISSING_COLOR);
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
        if (auto != null) {
            auto.setMessage(Component.translatable(orders.auto() ? "civmod.gui.orders.auto.on" : "civmod.gui.orders.auto.off"));
        }
        graphics.text(font, Component.translatable("civmod.gui.orders.slots", orders.slots()), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
    }

    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        return List.of(Component.translatable("civmod.gui.orders.help.what"), Component.translatable("civmod.gui.orders.help.priority"),
                Component.translatable("civmod.gui.orders.help.keep"), Component.translatable("civmod.gui.orders.help.missing"),
                Component.translatable("civmod.gui.orders.help.auto"));
    }
}
