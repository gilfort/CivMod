package com.gilfort.civmod.menu;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Clerk's "Deliveries" tab (vault {@code Jobs/Deliveryman.md}): the open Delivery tasks, from the Clerk's data
 * ({@link ClerkTabData#deliveries()}) shared with the Stock tab.
 */
public final class DeliveriesTab extends JobBlockTab {

    private final TabData<ClerkTabData> data;

    /**
     * @param data the Clerk's data, shared with its Stock and Requests tabs
     */
    DeliveriesTab(TabData<ClerkTabData> data) {
        this.data = data;
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.clerk.tab.deliveries");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.BUNDLE);
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
