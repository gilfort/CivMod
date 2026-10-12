package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerJobs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Screen of a settler: Overview (name, status, settlement, dismiss when homeless), Jobs (priority list) and
 * Inventory (tool belt, offhand, pockets).
 */
public class SettlerScreen extends CivContainerScreen<SettlerMenu> {

    private static final int ROW_HEIGHT = 30;
    private static final int SMALL_BUTTON = 16;

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public SettlerScreen(SettlerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, SettlerMenu.IMAGE_HEIGHT, List.of(
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.overview"), new ItemStack(Items.PLAYER_HEAD)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.jobs"), new ItemStack(Items.IRON_PICKAXE)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.inventory"), new ItemStack(Items.CHEST))));
    }

    @Override
    protected void initContent() {
        SettlerInfo info = menu.getInfo();
        if (info.homeless()) {
            addTabWidget(SettlerMenu.TAB_OVERVIEW, Button.builder(Component.translatable("civmod.gui.settler.dismiss"), button -> confirmDismiss())
                    .bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 28, imageWidth - 2 * CivGui.PADDING, 20).build());
        }
        List<SettlerJob> jobs = info.jobs();
        int right = leftPos + imageWidth - CivGui.PADDING;
        for (int i = 0; i < jobs.size(); i++) {
            int y = topPos + 22 + i * ROW_HEIGHT;
            Button up = jobButton("▲", "civmod.gui.settler.job.up", SettlerJobs.MOVE_UP + i, right - 3 * SMALL_BUTTON - 4, y);
            up.active = i > 0;
            Button down = jobButton("▼", "civmod.gui.settler.job.down", SettlerJobs.MOVE_DOWN + i, right - 2 * SMALL_BUTTON - 2, y);
            down.active = i < jobs.size() - 1;
            jobButton("✕", "civmod.gui.settler.job.remove", SettlerJobs.REMOVE + i, right - SMALL_BUTTON, y);
        }
    }

    private Button jobButton(String label, String tooltip, int buttonId, int x, int y) {
        Button button = addTabWidget(SettlerMenu.TAB_JOBS, Button.builder(Component.literal(label), b -> clickButton(buttonId))
                .bounds(x, y, SMALL_BUTTON, SMALL_BUTTON).build());
        button.setTooltip(Tooltip.create(Component.translatable(tooltip)));
        return button;
    }

    private void clickButton(int buttonId) {
        menu.clickMenuButton(minecraft.player, buttonId);
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        rebuildWidgets();
    }

    private void confirmDismiss() {
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, SettlerMenu.BUTTON_DISMISS);
            } else {
                minecraft.gui.setScreen(this);
            }
        }, Component.translatable("civmod.gui.settler.dismiss.title", menu.getInfo().name()),
                Component.translatable("civmod.gui.settler.dismiss.message")));
    }

    @Override
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        SettlerInfo info = menu.getInfo();
        switch (tab) {
            case SettlerMenu.TAB_OVERVIEW -> {
                graphics.text(font, Component.translatable("civmod.gui.settler.name", info.name()), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.status", info.status()), CivGui.PADDING, 34, CivGui.LABEL_COLOR, false);
                Component settlement = info.homeless() ? Component.translatable("civmod.gui.settler.no_settlement") : info.settlementName();
                graphics.text(font, Component.translatable("civmod.gui.settler.settlement", settlement), CivGui.PADDING, 46, CivGui.LABEL_COLOR, false);
            }
            case SettlerMenu.TAB_JOBS -> {
                int y = 24;
                for (int i = 0; i < info.jobs().size(); i++) {
                    SettlerJob job = info.jobs().get(i);
                    SettlerInfo.JobState state = i < info.jobStates().size() ? info.jobStates().get(i) : null;
                    graphics.text(font, job.jobType().displayName(), CivGui.PADDING, y, CivGui.LABEL_COLOR, false);
                    if (state != null) {
                        graphics.text(font, state.status().displayName(), CivGui.PADDING, y + 10, CivGui.HINT_COLOR, false);
                        int x = 80;
                        for (ItemStack stack : state.preview()) {
                            graphics.item(stack, x, y + 2);
                            graphics.itemDecorations(font, stack, x, y + 2);
                            x += 17;
                        }
                    }
                    y += ROW_HEIGHT;
                }
                graphics.text(font, Component.translatable("civmod.gui.settler.laborer"), CivGui.PADDING, y, CivGui.HINT_COLOR, false);
            }
            case SettlerMenu.TAB_INVENTORY -> {
                graphics.text(font, Component.translatable("civmod.gui.settler.tools"), CivGui.PADDING, SettlerMenu.BELT_Y - 10, CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.offhand"), SettlerMenu.OFFHAND_X, SettlerMenu.BELT_Y - 10,
                        CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.pockets"), CivGui.PADDING, SettlerMenu.POCKETS_Y - 10,
                        CivGui.LABEL_COLOR, false);
            }
            default -> {
            }
        }
    }
}
