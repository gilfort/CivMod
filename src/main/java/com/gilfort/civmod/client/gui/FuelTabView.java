package com.gilfort.civmod.client.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.menu.FuelTab;
import com.gilfort.civmod.menu.FuelTabData;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.settlement.Fuels;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The "Fuel" tab of a JobBlock in the {@link JobBlockScreen} (vault {@code Jobs/Smelter.md}); the client side of
 * {@link FuelTab}. Six ghost slots in a row, the order is the priority; a click on an item selects it and the ◀ ▶
 * buttons below swap it with its neighbour; a right click empties a slot (the following ones move up). An item is set
 * by a click with it (not consumed), a JEI / REI drag or Shift + right-click in the inventory.
 */
final class FuelTabView implements JobBlockTabView {

    /** The 6 slots, centred in the panel. */
    private static final int SLOTS_X = 35;
    private static final int SLOTS_Y = 30;
    private static final int BUTTONS_Y = 50;
    private static final int BUTTON_WIDTH = 20;
    private static final int BUTTON_HEIGHT = 14;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final FuelTab fuel;
    /** The selected slot, {@code -1} if none. */
    private int selected = -1;
    private @Nullable Button left;
    private @Nullable Button right;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    FuelTabView(JobBlockScreen screen, FuelTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.fuel = tab;
    }

    private FuelTabData data() {
        return fuel.data().get();
    }

    @Override
    public void init() {
        int x = screen.left() + SLOTS_X + Fuels.SLOTS * 9 - BUTTON_WIDTH - 1;
        int y = screen.top() + BUTTONS_Y;
        left = screen.addWidget(fuel.index(), Button.builder(Component.literal("◀"), button -> move(FuelTabData.Op.LEFT))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        left.setTooltip(Tooltip.create(Component.translatable("civmod.gui.fuel.left")));
        right = screen.addWidget(fuel.index(), Button.builder(Component.literal("▶"), button -> move(FuelTabData.Op.RIGHT))
                .bounds(x + BUTTON_WIDTH + 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        right.setTooltip(Tooltip.create(Component.translatable("civmod.gui.fuel.right")));
        update();
    }

    @Override
    public void dataChanged() {
        update();
    }

    @Override
    public void onTabChanged() {
        selected = -1;
        update();
    }

    /** The arrows work on a selected item that has a neighbour on their side. */
    private void update() {
        if (left == null || right == null) {
            return;
        }
        int size = data().fuel().size();
        if (selected >= size) {
            selected = -1;
        }
        left.active = selected > 0;
        right.active = selected >= 0 && selected < size - 1;
    }

    /** Swaps the selected item with its neighbour; the selection follows the item. */
    private void move(FuelTabData.Op op) {
        if (selected < 0) {
            return;
        }
        send(op, selected, Items.AIR);
        selected += op == FuelTabData.Op.LEFT ? -1 : 1;
        update();
    }

    private void send(FuelTabData.Op op, int slot, Item item) {
        ClientPacketDistributor.sendToServer(new FuelTabData.Edit(menu.containerId, op, slot, item));
    }

    private static int slotX(int slot) {
        return SLOTS_X + slot * 18;
    }

    // ---------------------------------------------------------------- ghost slots

    /**
     * Sets a slot's item, as a ghost: the item is not taken; only fuel is taken, and a slot past the list's end adds the
     * item at the end.
     */
    @Override
    public boolean acceptGhost(int slot, ItemStack stack) {
        if (stack.isEmpty() || slot < 0 || slot >= Fuels.SLOTS || !Fuels.isFuel(stack)) {
            return false;
        }
        send(FuelTabData.Op.SET, slot, stack.getItem());
        selected = Math.min(slot, data().fuel().size());
        screen.clickSound();
        return true;
    }

    /** Shift + right-click: the first empty slot. */
    @Override
    public int nextEmptyGhost() {
        return data().nextEmptyGhostSlot();
    }

    @Override
    public Map<Integer, Rect2i> ghostTargets() {
        Map<Integer, Rect2i> targets = new LinkedHashMap<>();
        for (int slot = 0; slot < Fuels.SLOTS; slot++) {
            targets.put(slot, new Rect2i(screen.left() + slotX(slot), screen.top() + SLOTS_Y, 16, 16));
        }
        return targets;
    }

    @Override
    public int ghostTargetAt(double x, double y) {
        int relativeX = (int) Math.floor(x - screen.left());
        int relativeY = (int) Math.floor(y - screen.top());
        for (int slot = 0; slot < Fuels.SLOTS; slot++) {
            if (relativeX >= slotX(slot) && relativeX < slotX(slot) + 16 && relativeY >= SLOTS_Y && relativeY < SLOTS_Y + 16) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * A click on a slot: with an item on the cursor it is set (not consumed); otherwise a left click selects the item, a
     * right click empties the slot.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        int slot = ghostTargetAt(event.x(), event.y());
        if (slot < 0) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            acceptGhost(slot, carried);
            return true;
        }
        if (data().item(slot) == Items.AIR) {
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            send(FuelTabData.Op.CLEAR, slot, Items.AIR);
            selected = -1;
        } else {
            selected = slot;
        }
        update();
        screen.clickSound();
        return true;
    }

    @Override
    public List<Component> helpLines(JobBlockTab tab) {
        return List.of(Component.translatable("civmod.gui.fuel.help.set"), Component.translatable("civmod.gui.fuel.help.move"),
                Component.translatable("civmod.gui.fuel.help.clear"));
    }

    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab tab, int mouseX, int mouseY) {
        Font font = screen.getFont();
        graphics.text(font, Component.translatable("civmod.gui.fuel.title"), CivGui.PADDING, 18, CivGui.LABEL_COLOR, false);
        int hovered = ghostTargetAt(mouseX, mouseY);
        FuelTabData data = data();
        for (int slot = 0; slot < Fuels.SLOTS; slot++) {
            int x = slotX(slot);
            CivGui.slot(graphics, x, SLOTS_Y);
            Item item = data.item(slot);
            if (item != Items.AIR) {
                graphics.item(new ItemStack(item), x, SLOTS_Y);
            }
            if (slot == selected) {
                CivGui.slotSelected(graphics, x, SLOTS_Y);
            }
            if (slot == hovered) {
                CivGui.slotHover(graphics, x, SLOTS_Y);
            }
        }
        if (data.fuel().isEmpty()) {
            int width = screen.panelWidth() - 2 * CivGui.PADDING;
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.fuel.none"), width), CivGui.PADDING, BUTTONS_Y + BUTTON_HEIGHT + 4,
                    CivGui.ERROR_COLOR, false);
        }
        if (hovered >= 0 && data.item(hovered) != Items.AIR) {
            graphics.setComponentTooltipForNextFrame(font, List.of(new ItemStack(data.item(hovered)).getHoverName(),
                    Component.translatable("civmod.gui.fuel.priority", hovered + 1)), mouseX, mouseY);
        }
    }
}
