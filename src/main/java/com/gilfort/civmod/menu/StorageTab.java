package com.gilfort.civmod.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Storage" tab of every JobBlock: its 27 slots and the player inventory; shift-click moves items between them only
 * here.
 */
public final class StorageTab extends JobBlockTab {

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.job_block.tab.storage");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CHEST);
    }

    @Override
    public Group group() {
        return Group.STORAGE;
    }

    @Override
    boolean showsInventory() {
        return true;
    }
}
