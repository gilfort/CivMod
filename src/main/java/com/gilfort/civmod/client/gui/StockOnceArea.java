package com.gilfort.civmod.client.gui;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StockTab;
import com.gilfort.civmod.settlement.OneTimeOrders;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The "Once" area of the Clerk's Stock tab ({@link StockTabView}, grid view): a label, a ghost slot for the item, the amount
 * field and the "Order" button of a one-time order (vault {@code Jobs/Clerk.md}). It keeps the item and the amount the
 * player has typed so far.
 */
final class StockOnceArea {

    private static final int SLOT_X = 40;
    private static final int SLOT_SIZE = 18;
    private static final int ITEM_SIZE = 16;
    private static final int AMOUNT_X = 66;
    private static final int AMOUNT_WIDTH = 36;
    private static final int ORDER_X = 108;
    private static final int ORDER_WIDTH = 48;
    private static final int AMOUNT_MAX_LENGTH = 4;
    /** Distance of the label's text from the top of the area, so it sits level with the slot's centre. */
    private static final int LABEL_OFFSET_Y = 4;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final StockTab stock;
    /** Panel y of the area. */
    private final int top;
    /** The item in the ghost slot, null while it is empty. */
    private @Nullable ItemKey item;
    /** The amount typed into the field, 0 while it is empty or no number. */
    private int amount = 1;
    private @Nullable EditBox amountBox;
    private @Nullable Button orderButton;

    /**
     * @param screen the screen showing the tab
     * @param stock  the tab
     * @param top    the panel y of the area
     */
    StockOnceArea(JobBlockScreen screen, StockTab stock, int top) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.stock = stock;
        this.top = top;
    }

    private Font font() {
        return screen.getFont();
    }

    /** Drops the widgets (the screen removed them); the item and the amount stay. */
    void forgetWidgets() {
        amountBox = null;
        orderButton = null;
    }

    /** Adds the amount field and the "Order" button (called on every widget rebuild of the grid view). */
    void init() {
        int y = screen.top() + top;
        EditBox box = screen.addWidget(stock.index(),
                new EditBox(font(), screen.left() + AMOUNT_X, y, AMOUNT_WIDTH, SLOT_SIZE - 2, Component.translatable("civmod.gui.clerk.stock.once.amount")));
        box.setMaxLength(AMOUNT_MAX_LENGTH);
        box.setValue(amount > 0 ? Integer.toString(amount) : "");
        box.setResponder(text -> {
            amount = StockTabView.parse(text).orElse(0);
            updateOrderButton();
        });
        amountBox = box;
        orderButton = screen.addWidget(stock.index(), Button.builder(Component.translatable("civmod.gui.clerk.stock.once.order"), button -> placeOrder())
                .bounds(screen.left() + ORDER_X, y - 1, ORDER_WIDTH, SLOT_SIZE).build());
        updateOrderButton();
    }

    /** "Order" works with an item in the slot and an amount the server takes. */
    private void updateOrderButton() {
        if (orderButton != null) {
            orderButton.active = item != null && amount >= 1 && amount <= OneTimeOrders.MAX_AMOUNT;
        }
    }

    /** Sends the order, then empties the slot and sets the amount back to 1. */
    private void placeOrder() {
        if (item == null || orderButton == null || !orderButton.active) {
            return;
        }
        ClientPacketDistributor.sendToServer(new ClerkTabData.Order(menu.containerId, item, amount));
        screen.clickSound();
        item = null;
        amount = 1;
        screen.rebuild();
    }

    /**
     * Puts an item into the ghost slot, as a ghost: the item is not taken.
     *
     * @param stack the item
     * @return whether the slot took it
     */
    boolean setItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        item = ItemKey.of(stack);
        updateOrderButton();
        screen.clickSound();
        return true;
    }

    /** @return whether the ghost slot holds no item */
    boolean isEmpty() {
        return item == null;
    }

    /** The ghost slot's area, absolute. */
    Rect2i slotArea() {
        return new Rect2i(screen.left() + SLOT_X, screen.top() + top, ITEM_SIZE, ITEM_SIZE);
    }

    /** A right click on the slot empties it; every click on it is taken. */
    boolean mouseClicked(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && item != null) {
            item = null;
            updateOrderButton();
            screen.clickSound();
        }
        return true;
    }

    /** Typing into the amount field must not trigger the inventory key or hotbar swaps. */
    boolean keyPressed(KeyEvent event) {
        if (amountBox != null && amountBox.isFocused() && amountBox.visible && !event.isEscape()) {
            amountBox.keyPressed(event);
            return true;
        }
        return false;
    }

    /** Draws the label and the ghost slot; a hovered, filled slot shows the item's name as tooltip (coordinates relative to the panel). */
    void extract(GuiGraphicsExtractor graphics, boolean hovered, int mouseX, int mouseY) {
        graphics.text(font(), Component.translatable("civmod.gui.clerk.stock.once"), CivGui.PADDING, top + LABEL_OFFSET_Y, CivGui.LABEL_COLOR, false);
        CivGui.slot(graphics, SLOT_X, top);
        if (item != null) {
            graphics.item(item.toStack(), SLOT_X, top);
        }
        if (hovered) {
            CivGui.slotHover(graphics, SLOT_X, top);
            if (item != null) {
                graphics.setComponentTooltipForNextFrame(font(), List.of(item.toStack().getHoverName()), mouseX, mouseY);
            }
        }
    }
}
