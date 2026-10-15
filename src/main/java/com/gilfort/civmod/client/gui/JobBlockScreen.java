package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.job.JobProfile;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Screen of a JobBlock: storage tab and info tab (job, level, skill cap, settlement status, upgrade button); the
 * Mission Board adds a missions tab (the mission list, empty until missions exist).
 * <p>
 * Unconnected JobBlocks show the info greyed out with a red hint; storage keeps working.
 */
public class JobBlockScreen extends CivContainerScreen<JobBlockMenu> {

    private static final int ERROR_COLOR = 0xFFAA0000;
    private static final int COST_SLOTS = 7;
    private static final int COST_X = CivGui.PADDING + 9;
    private static final int MISSING_TINT = 0x80FF2020;

    private int costOffset;
    private int costRowY = -1;
    private static final int LINE_HEIGHT = 11;
    private static final int MISSION_LIST_Y = 34;

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public JobBlockScreen(JobBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166, tabs(menu));
    }

    private static List<TabBar.Tab> tabs(JobBlockMenu menu) {
        List<TabBar.Tab> tabs = new java.util.ArrayList<>(List.of(
                new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.storage"), new ItemStack(Items.CHEST)),
                new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.info"), new ItemStack(Items.BOOK))));
        if (JobBlockMenu.isMissionBoard(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.missions"), new ItemStack(Items.MAP)));
        }
        return tabs;
    }

    @Override
    protected void initContent() {
        JobBlockInfo current = menu.getInfo();
        boolean max = current.level() >= SettlementRules.MAX_LEVEL;
        Component upgradeLabel = max ? Component.translatable("civmod.gui.job_block.upgrade.max")
                : Component.translatable("civmod.gui.job_block.upgrade.to", current.level() + 1);
        Button upgrade = addTabWidget(JobBlockMenu.TAB_INFO, Button.builder(upgradeLabel, button -> {
            menu.clickMenuButton(minecraft.player, JobBlockMenu.BUTTON_UPGRADE);
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, JobBlockMenu.BUTTON_UPGRADE);
            rebuildWidgets();
        }).bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 28, imageWidth - 2 * CivGui.PADDING, 20).build());
        upgrade.active = !max && (minecraft.player.isCreative() || current.canAffordNext(minecraft.player.getInventory()));
        if (menu.getInfo().jobType().profile().map(JobProfile::hasLeavesOption).orElse(false) && menu.getInfo().inSettlement()) {
            Component label = Component.translatable(menu.getInfo().collectLeaves() ? "civmod.gui.job_block.leaves.on" : "civmod.gui.job_block.leaves.off");
            Button leaves = addTabWidget(JobBlockMenu.TAB_INFO, Button.builder(label, button -> {
                menu.clickMenuButton(minecraft.player, JobBlockMenu.BUTTON_TOGGLE_LEAVES);
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, JobBlockMenu.BUTTON_TOGGLE_LEAVES);
                rebuildWidgets();
            }).bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 72, imageWidth - 2 * CivGui.PADDING, 20).build());
            leaves.setTooltip(Tooltip.create(Component.translatable("civmod.gui.job_block.leaves.tooltip")));
        }
        if (!JobBlockMenu.isMissionBoard(current) && !current.assignees().isEmpty()) {
            addTabWidget(JobBlockMenu.TAB_INFO, Button.builder(Component.translatable("civmod.gui.job_block.unassign"), button -> {
                menu.clickMenuButton(minecraft.player, JobBlockMenu.BUTTON_UNASSIGN);
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, JobBlockMenu.BUTTON_UNASSIGN);
                rebuildWidgets();
            }).bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 50, imageWidth - 2 * CivGui.PADDING, 20).build());
        }
    }

    @Override
    protected void extractTabBackground(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (tab == JobBlockMenu.TAB_MISSIONS) {
            CivGui.inset(graphics, leftPos + CivGui.PADDING, topPos + MISSION_LIST_Y, imageWidth - 2 * CivGui.PADDING,
                    imageHeight - MISSION_LIST_Y - CivGui.PADDING);
        }
    }

    @Override
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (tab == JobBlockMenu.TAB_MISSIONS) {
            extractMissions(graphics);
            return;
        }
        if (tab != JobBlockMenu.TAB_INFO) {
            return;
        }
        JobBlockInfo info = menu.getInfo();
        boolean active = info.inSettlement() && info.connected();
        int color = active ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR;
        int y = 22;
        graphics.text(font, Component.translatable("civmod.gui.job_block.job", info.jobType().displayName()), CivGui.PADDING, y, color, false);
        y += LINE_HEIGHT;
        graphics.text(font, Component.translatable("civmod.gui.job_block.level_cap", info.level(), SettlementRules.MAX_LEVEL,
                SettlementRules.skillCap(info.level())), CivGui.PADDING, y, color, false);
        y += LINE_HEIGHT;
        if (info.inSettlement()) {
            graphics.text(font, Component.translatable("civmod.gui.job_block.settlement", info.settlementName()), CivGui.PADDING, y, color, false);
        }
        y += LINE_HEIGHT;
        if (!JobBlockMenu.isMissionBoard(info)) {
            Component worker = info.assignees().isEmpty() ? Component.translatable("civmod.gui.job_block.no_worker")
                    : Component.literal(info.assignees().get(0));
            graphics.text(font, Component.translatable("civmod.gui.job_block.worker", worker), CivGui.PADDING, y, color, false);
        } else {
            Component settlers = Component.translatable("civmod.gui.job_block.settlers", info.assignees().size(), info.capacity());
            graphics.text(font, settlers, CivGui.PADDING, y, color, false);
            if (!info.assignees().isEmpty() && mouseX >= leftPos + CivGui.PADDING && mouseX < leftPos + CivGui.PADDING + font.width(settlers)
                    && mouseY >= topPos + y && mouseY < topPos + y + font.lineHeight) {
                graphics.setComponentTooltipForNextFrame(font, info.assignees().stream().<Component>map(Component::literal).toList(), mouseX, mouseY);
            }
        }
        y += LINE_HEIGHT;
        y = extractCosts(graphics, info, y, mouseX, mouseY);
        if (!info.inSettlement()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.job_block.not_registered"),
                    CivGui.PADDING, y, imageWidth - 2 * CivGui.PADDING, ERROR_COLOR, false);
        } else if (!info.connected()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.job_block.unconnected", info.required(), info.distance()),
                    CivGui.PADDING, y, imageWidth - 2 * CivGui.PADDING, ERROR_COLOR, false);
        }
    }

    /**
     * Draws the missions tab: the mission list (empty until missions exist).
     */
    private void extractMissions(GuiGraphicsExtractor graphics) {
        graphics.text(font, Component.translatable("civmod.gui.job_block.missions"), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        graphics.textWithWordWrap(font, Component.translatable("civmod.gui.job_block.missions.none"), CivGui.PADDING + CivGui.INSET_PADDING,
                MISSION_LIST_Y + CivGui.INSET_PADDING, imageWidth - 2 * (CivGui.PADDING + CivGui.INSET_PADDING), CivGui.HINT_COLOR, false);
    }

    /**
     * Draws the costs of the next level as a row of slots (icon, needed count, red tint when missing); more than
     * {@value #COST_SLOTS} entries scroll with the mouse wheel.
     */
    private int extractCosts(GuiGraphicsExtractor graphics, JobBlockInfo info, int y, int mouseX, int mouseY) {
        List<JobBlockInfo.CostEntry> costs = info.nextCosts();
        if (costs.isEmpty() || info.level() >= SettlementRules.MAX_LEVEL) {
            return y;
        }
        graphics.text(font, Component.translatable("civmod.gui.job_block.upgrade.costs"), CivGui.PADDING, y, CivGui.LABEL_COLOR, false);
        int rowY = y + 10;
        costRowY = rowY;
        costOffset = Math.clamp(costOffset, 0, Math.max(0, costs.size() - COST_SLOTS));
        boolean creative = minecraft.player.isCreative();
        if (costOffset > 0) {
            graphics.text(font, Component.literal("«"), CivGui.PADDING, rowY + 4, CivGui.LABEL_COLOR, false);
        }
        if (costOffset + COST_SLOTS < costs.size()) {
            graphics.text(font, Component.literal("»"), COST_X + COST_SLOTS * 18 + 2, rowY + 4, CivGui.LABEL_COLOR, false);
        }
        for (int i = 0; i < Math.min(COST_SLOTS, costs.size() - costOffset); i++) {
            JobBlockInfo.CostEntry cost = costs.get(costOffset + i);
            int x = COST_X + i * 18;
            CivGui.slot(graphics, x, rowY);
            graphics.item(cost.icon(), x, rowY);
            graphics.itemDecorations(font, cost.icon(), x, rowY, Integer.toString(cost.count()));
            int have = cost.count(minecraft.player.getInventory());
            if (!creative && have < cost.count()) {
                graphics.fill(x, rowY, x + 16, rowY + 16, MISSING_TINT);
            }
            if (mouseX >= leftPos + x && mouseX < leftPos + x + 16 && mouseY >= topPos + rowY && mouseY < topPos + rowY + 16) {
                graphics.setComponentTooltipForNextFrame(font, List.of(cost.icon().getHoverName(),
                        Component.translatable("civmod.gui.job_block.upgrade.have", have, cost.count())
                                .withStyle(creative || have >= cost.count() ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED)),
                        mouseX, mouseY);
            }
        }
        return rowY + 20;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int size = menu.getInfo().nextCosts().size();
        if (menu.getActiveTab() == JobBlockMenu.TAB_INFO && size > COST_SLOTS && costRowY >= 0
                && mouseY >= topPos + costRowY - 2 && mouseY < topPos + costRowY + 18) {
            costOffset = Math.clamp(costOffset - (int) Math.signum(scrollY), 0, size - COST_SLOTS);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
