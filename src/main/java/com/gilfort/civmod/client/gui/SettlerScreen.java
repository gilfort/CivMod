package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerJobs;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.settlement.JobType;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Screen of a settler: Overview (name, status, settlement, dismiss when homeless), Jobs (priority list), Inventory
 * (tool belt, offhand, pockets) and one tab per job below the panel (status and buffer).
 */
public class SettlerScreen extends CivContainerScreen<SettlerMenu> {

    private static final int ROW_HEIGHT = 22;
    private static final int BUFFER_Y = 56;
    private static final int BUFFER_COLUMNS = 9;
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
                    int rowY = y;
                    graphics.text(font, job.jobType().displayName(), CivGui.PADDING, rowY, CivGui.LABEL_COLOR, false);
                    state(info, i).ifPresent(state -> graphics.text(font, state.status().displayName(), CivGui.PADDING + 70, rowY, CivGui.HINT_COLOR, false));
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
            default -> extractJobTab(graphics, info, tab - SettlerMenu.FIRST_JOB_TAB, mouseX, mouseY);
        }
    }

    private static java.util.Optional<SettlerInfo.JobState> state(SettlerInfo info, int index) {
        return index >= 0 && index < info.jobStates().size() ? java.util.Optional.of(info.jobStates().get(index)) : java.util.Optional.empty();
    }

    @Override
    protected List<TabBar.Tab> bottomTabs() {
        return menu.getInfo().jobs().stream().limit(SettlerMenu.MAX_JOB_TABS)
                .map(job -> new TabBar.Tab(job.jobType().displayName(), jobIcon(job.jobType()))).toList();
    }

    private static ItemStack jobIcon(JobType jobType) {
        return switch (jobType) {
            case LUMBERJACK -> new ItemStack(ModItems.LUMBERJACK_BLOCK.get());
            case MINER -> new ItemStack(ModItems.MINER_BLOCK.get());
            case FISHERMAN -> new ItemStack(ModItems.FISHERMAN_BLOCK.get());
        };
    }

    @Override
    protected void extractTabBackground(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (tab >= SettlerMenu.FIRST_JOB_TAB) {
            for (int i = 0; i < 18; i++) {
                CivGui.slot(graphics, leftPos + CivGui.PADDING + (i % BUFFER_COLUMNS) * 18, topPos + BUFFER_Y + (i / BUFFER_COLUMNS) * 18);
            }
        }
    }

    private void extractJobTab(GuiGraphicsExtractor graphics, SettlerInfo info, int index, int mouseX, int mouseY) {
        if (index < 0 || index >= info.jobs().size()) {
            return;
        }
        SettlerJob job = info.jobs().get(index);
        graphics.text(font, job.jobType().displayName(), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        state(info, index).ifPresent(state -> {
            graphics.text(font, Component.translatable("civmod.gui.settler.status", state.status().displayName()), CivGui.PADDING, 34,
                    CivGui.LABEL_COLOR, false);
            graphics.text(font, Component.translatable("civmod.gui.settler.buffer", state.buffered(), JobBuffer.CAPACITY), CivGui.PADDING,
                    BUFFER_Y - 10, CivGui.LABEL_COLOR, false);
            for (int i = 0; i < state.preview().size(); i++) {
                ItemStack stack = state.preview().get(i);
                int x = CivGui.PADDING + (i % BUFFER_COLUMNS) * 18;
                int y = BUFFER_Y + (i / BUFFER_COLUMNS) * 18;
                graphics.item(stack, x, y);
                graphics.itemDecorations(font, stack, x, y);
                if (mouseX >= leftPos + x && mouseX < leftPos + x + 16 && mouseY >= topPos + y && mouseY < topPos + y + 16) {
                    graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                }
            }
        });
    }
}
