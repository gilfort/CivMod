package com.gilfort.civmod.menu;

import com.gilfort.civmod.registry.ModMenuTypes;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;

/**
 * Development-only menu showcasing the GUI building blocks (tabs, tab-bound slots, player inventory).
 * <p>
 * Opened via {@code /civmod debug gui}. Items placed in the debug slots are returned to the player on close.
 */
public class DebugMenu extends CivMenu {

    /** Tab with the button and progress bar. */
    public static final int TAB_WIDGETS = 0;
    /** Tab with the debug slots and the player inventory. */
    public static final int TAB_SLOTS = 1;
    /** Tab with wrapped text and the popup button. */
    public static final int TAB_INFO = 2;

    /** Number of debug slots. */
    public static final int DEBUG_SLOTS = 9;

    private final SimpleContainer container = new SimpleContainer(DEBUG_SLOTS);

    /**
     * @param containerId the container id
     * @param inventory   the player inventory
     */
    public DebugMenu(int containerId, Inventory inventory) {
        super(ModMenuTypes.DEBUG.get(), containerId, 3);
        for (int i = 0; i < DEBUG_SLOTS; i++) {
            addTabSlot(container, i, 8 + i * 18, 18, TAB_SLOTS);
        }
        addPlayerInventory(inventory, 8, 84, TAB_SLOTS);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = slotIndex < DEBUG_SLOTS
                ? moveItemStackTo(stack, DEBUG_SLOTS, slots.size(), true)
                : moveItemStackTo(stack, 0, DEBUG_SLOTS, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, container);
    }
}
