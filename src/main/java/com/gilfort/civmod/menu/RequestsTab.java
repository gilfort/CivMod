package com.gilfort.civmod.menu;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Clerk's "Requests" tab (vault {@code Jobs/Clerk.md}): the requests of its stock rules, from the Clerk's data
 * ({@link ClerkTabData#requests()}) shared with the Stock tab.
 */
public final class RequestsTab extends JobBlockTab {

    private final TabData<ClerkTabData> data;

    /**
     * @param data the Clerk's data, shared with its Stock and Deliveries tabs
     */
    RequestsTab(TabData<ClerkTabData> data) {
        this.data = data;
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.clerk.tab.requests");
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
