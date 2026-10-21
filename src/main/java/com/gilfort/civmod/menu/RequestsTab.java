package com.gilfort.civmod.menu;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Clerk's "Requests" tab (vault {@code Jobs/Clerk.md}): the requests of its stock rules, from the Clerk's data
 * ({@link ClerkTabData#requests()}) shared with the Stock tab.
 */
public final class RequestsTab extends ClerkListTab {

    /**
     * @param data the Clerk's data, shared with its Stock and Deliveries tabs
     */
    RequestsTab(TabData<ClerkTabData> data) {
        super(data, "civmod.gui.clerk.tab.requests");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.PAPER);
    }
}
