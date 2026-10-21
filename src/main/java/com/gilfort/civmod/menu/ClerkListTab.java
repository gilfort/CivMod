package com.gilfort.civmod.menu;

import java.util.Optional;

import net.minecraft.network.chat.Component;

/**
 * A tall Clerk tab listing part of the Clerk's data ({@link ClerkTabData}), shared with its Stock tab: the Requests and
 * the Deliveries tab differ only in label and icon.
 */
abstract sealed class ClerkListTab extends JobBlockTab permits RequestsTab, DeliveriesTab {

    private final TabData<ClerkTabData> data;
    private final String labelKey;

    /**
     * @param data     the Clerk's data, shared with its other tabs
     * @param labelKey the translation key of the tab's label
     */
    ClerkListTab(TabData<ClerkTabData> data, String labelKey) {
        this.data = data;
        this.labelKey = labelKey;
    }

    @Override
    public Component label() {
        return Component.translatable(labelKey);
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
