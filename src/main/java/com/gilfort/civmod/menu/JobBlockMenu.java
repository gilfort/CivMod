package com.gilfort.civmod.menu;

import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.registry.ModMenuTypes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu of a JobBlock: tab "Storage" (27 slots + player inventory) and tab "Info".
 */
public class JobBlockMenu extends CivMenu {

    /** Storage tab. */
    public static final int TAB_STORAGE = 0;
    /** Info tab. */
    public static final int TAB_INFO = 1;

    private final Container container;
    private final JobBlockInfo info;

    /**
     * Client constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param buffer      extra data written by the server
     */
    public JobBlockMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, new SimpleContainer(JobBlockEntity.SLOTS), JobBlockInfo.STREAM_CODEC.decode(buffer));
    }

    /**
     * Server constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param container   the JobBlock storage
     * @param info        the info snapshot
     */
    public JobBlockMenu(int containerId, Inventory inventory, Container container, JobBlockInfo info) {
        super(ModMenuTypes.JOB_BLOCK.get(), containerId, 2);
        checkContainerSize(container, JobBlockEntity.SLOTS);
        this.container = container;
        this.info = info;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addTabSlot(container, column + row * 9, 8 + column * 18, 18 + row * 18, TAB_STORAGE);
            }
        }
        addPlayerInventory(inventory, 8, 84, TAB_STORAGE);
    }

    /**
     * @return the info snapshot
     */
    public JobBlockInfo getInfo() {
        return info;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = slotIndex < JobBlockEntity.SLOTS
                ? moveItemStackTo(stack, JobBlockEntity.SLOTS, slots.size(), true)
                : moveItemStackTo(stack, 0, JobBlockEntity.SLOTS, false);
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
        return container.stillValid(player);
    }
}
