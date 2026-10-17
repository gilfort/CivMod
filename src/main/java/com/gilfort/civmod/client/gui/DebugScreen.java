package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.menu.DebugMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Development-only screen showcasing all GUI building blocks: tabs, tab-bound slots with player inventory, buttons,
 * progress bar, wrapped text and a {@link CivScreen} popup.
 */
public class DebugScreen extends CivContainerScreen<DebugMenu> {

    private static final int PROGRESS_STEPS = 10;

    private ProgressBar progressBar;
    private int progressStep;

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public DebugScreen(DebugMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166, List.of(
                new TabBar.Tab(Component.translatable("civmod.gui.debug.tab.widgets"), new ItemStack(Items.REDSTONE)),
                new TabBar.Tab(Component.translatable("civmod.gui.debug.tab.slots"), new ItemStack(Items.CHEST)),
                new TabBar.Tab(Component.translatable("civmod.gui.debug.tab.info"), new ItemStack(Items.BOOK))));
    }

    @Override
    protected void initContent() {
        int x = leftPos + CivGui.PADDING;
        int contentWidth = imageWidth - 2 * CivGui.PADDING;

        progressBar = addTabWidget(DebugMenu.TAB_WIDGETS, new ProgressBar(x, topPos + 34, contentWidth,
                Component.translatable("civmod.gui.debug.progress.narration")));
        updateProgress();
        addTabWidget(DebugMenu.TAB_WIDGETS, Button.builder(Component.translatable("civmod.gui.debug.button.progress"), button -> {
            progressStep = (progressStep + 1) % (PROGRESS_STEPS + 1);
            updateProgress();
        }).bounds(x, topPos + 48, contentWidth, 20).build());
        Button disabled = addTabWidget(DebugMenu.TAB_WIDGETS, Button.builder(
                Component.translatable("civmod.gui.debug.button.disabled"), button -> { }).bounds(x, topPos + 72, contentWidth, 20).build());
        disabled.active = false;

        addTabWidget(DebugMenu.TAB_INFO, Button.builder(Component.translatable("civmod.gui.debug.button.popup"),
                button -> {
                    // Close the menu properly first so the server returns the debug slot items.
                    minecraft.player.closeContainer();
                    minecraft.gui.setScreen(new DebugPopupScreen());
                }).bounds(x, topPos + imageHeight - 28, contentWidth, 20).build());
    }

    private void updateProgress() {
        float progress = progressStep / (float) PROGRESS_STEPS;
        progressBar.setProgress(progress);
        progressBar.setTooltip(Tooltip.create(Component.translatable("civmod.gui.debug.progress", Math.round(progress * 100))));
    }

    @Override
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (tab == DebugMenu.TAB_WIDGETS) {
            graphics.text(font, Component.translatable("civmod.gui.debug.progress", Math.round(progressBar.getProgress() * 100)),
                    CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        }
    }

    @Override
    protected List<Component> helpLines(int tab) {
        return List.of(Component.translatable("civmod.gui.debug.help.blocks"), Component.translatable("civmod.gui.debug.help.dev"));
    }
}
