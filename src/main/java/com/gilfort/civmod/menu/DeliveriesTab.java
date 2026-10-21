package com.gilfort.civmod.menu;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Clerk's "Deliveries" tab (vault {@code Jobs/Deliveryman.md}): the open Delivery tasks, from the Clerk's data
 * ({@link ClerkTabData#deliveries()}) shared with the Stock tab.
 */
public final class DeliveriesTab extends ClerkListTab {

    /**
     * @param data the Clerk's data, shared with its Stock and Requests tabs
     */
    DeliveriesTab(TabData<ClerkTabData> data) {
        super(data, "civmod.gui.clerk.tab.deliveries");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.BUNDLE);
    }
}
