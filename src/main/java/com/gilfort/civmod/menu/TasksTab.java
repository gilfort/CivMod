package com.gilfort.civmod.menu;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Clerk's "Tasks" tab (vault {@code Jobs/Clerk.md}, {@code Jobs/Deliveryman.md}): the requests of its stock rules and
 * the open Delivery tasks to it in one list ({@link ClerkTabData#tasks()}), from the Clerk's data shared with the Stock
 * tab.
 */
public final class TasksTab extends JobBlockTab {

    private final TabData<ClerkTabData> data;

    /**
     * @param data the Clerk's data, shared with its Stock tab
     */
    TasksTab(TabData<ClerkTabData> data) {
        this.data = data;
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.clerk.tab.tasks");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.PAPER);
    }

    @Override
    boolean tall() {
        return true;
    }

    @Override
    public Optional<TabData<?>> synced() {
        return Optional.of(data);
    }

    /**
     * @return the Clerk's data as last synced
     */
    public TabData<ClerkTabData> data() {
        return data;
    }
}
