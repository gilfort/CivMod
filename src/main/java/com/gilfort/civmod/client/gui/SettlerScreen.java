package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerJobs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Screen of a settler: Overview (name, status, settlement, dismiss when homeless), Jobs (priority list), Inventory
 * (tool belt, offhand, pockets), Mission (the one Mission Job, chosen from those the Mission Board offers) and one tab
 * per job below the panel (status and buffer).
 */
public class SettlerScreen extends CivContainerScreen<SettlerMenu> {

    private static final int ROW_HEIGHT = 22;
    private static final int BUFFER_Y = 78;
    private static final int LIMITED_COLOR = 0xFFD07000;
    private static final int EXP_BAR_Y = 56;
    private static final int EXP_BAR_WIDTH = 90;
    private static final Identifier EXP_BACKGROUND = Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier EXP_PROGRESS = Identifier.withDefaultNamespace("hud/experience_bar_progress");
    private static final int BUFFER_COLUMNS = 9;
    private static final int SMALL_BUTTON = 16;
    private static final int FOOD_Y = 72;
    private static final int FOUND_Y = BUFFER_Y + 2 * 18 + 14;
    private static final int MISSION_OPTIONS_Y = 70;

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public SettlerScreen(SettlerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, SettlerMenu.IMAGE_HEIGHT, List.of(
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.overview"), new ItemStack(Items.PLAYER_HEAD)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.jobs"), new ItemStack(Items.IRON_PICKAXE)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.inventory"), new ItemStack(Items.CHEST)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.mission"), new ItemStack(Items.MAP))));
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
        java.util.Optional<SettlerJob> missionJob = info.missionJob();
        for (int i = 0; i < info.missionJobs().size() && missionJob.isPresent(); i++) {
            com.gilfort.civmod.job.Job option = info.missionJobs().get(i);
            int buttonId = SettlerJobs.SET_MISSION_JOB + i;
            Button button = addTabWidget(SettlerMenu.TAB_MISSION, Button.builder(option.displayName(), b -> clickButton(buttonId))
                    .bounds(leftPos + CivGui.PADDING, topPos + MISSION_OPTIONS_Y + i * ROW_HEIGHT, imageWidth - 2 * CivGui.PADDING, 20).build());
            button.active = option != missionJob.get().jobType();
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
                graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.settler.status", info.status()), imageWidth - 2 * CivGui.PADDING),
                        CivGui.PADDING, 34, CivGui.LABEL_COLOR, false);
                Component settlement = info.homeless() ? Component.translatable("civmod.gui.settler.no_settlement") : info.settlementName();
                graphics.text(font, Component.translatable("civmod.gui.settler.settlement", settlement), CivGui.PADDING, 46, CivGui.LABEL_COLOR, false);
                if (!info.homeless()) {
                    graphics.text(font, Component.translatable("civmod.gui.settler.bed", info.bed()), CivGui.PADDING, 58, CivGui.LABEL_COLOR, false);
                }
                graphics.text(font, Component.translatable("civmod.gui.settler.food"), CivGui.PADDING, FOOD_Y, CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable(SettlerFood.appetiteKey(info.food().appetite())), CivGui.PADDING, FOOD_Y + 12,
                        CivGui.HINT_COLOR, false);
            }
            case SettlerMenu.TAB_JOBS -> {
                int y = 24;
                for (int i = 0; i < info.jobs().size(); i++) {
                    SettlerJob job = info.jobs().get(i);
                    int rowY = y - 2;
                    graphics.text(font, job.jobType().displayName(), CivGui.PADDING, rowY, CivGui.LABEL_COLOR, false);
                    int levelX = CivGui.PADDING + font.width(job.jobType().displayName()) + 4;
                    state(info, i).ifPresent(state -> {
                        graphics.text(font, Component.translatable("civmod.gui.settler.level_short", state.skill().level()), levelX, rowY,
                                state.skill().limited() ? LIMITED_COLOR : CivGui.HINT_COLOR, false);
                        graphics.text(font, state.status().displayName(), CivGui.PADDING, rowY + 10, CivGui.HINT_COLOR, false);
                    });
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
            case SettlerMenu.TAB_MISSION -> extractMissionTab(graphics, info);
            default -> extractJobTab(graphics, info, tab - SettlerMenu.FIRST_JOB_TAB, mouseX, mouseY);
        }
    }

    private void extractMissionTab(GuiGraphicsExtractor graphics, SettlerInfo info) {
        java.util.Optional<SettlerJob> missionJob = info.missionJob();
        if (missionJob.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.settler.mission.none"), CivGui.PADDING, 22,
                    imageWidth - 2 * CivGui.PADDING, CivGui.HINT_COLOR, false);
            return;
        }
        graphics.text(font, Component.translatable("civmod.gui.settler.mission.job", missionJob.get().jobType().displayName()), CivGui.PADDING, 22,
                CivGui.LABEL_COLOR, false);
        graphics.text(font, Component.translatable("civmod.gui.settler.mission.board", missionJob.get().jobBlock().toShortString()), CivGui.PADDING,
                34, CivGui.LABEL_COLOR, false);
        Component mission = info.mission().getString().isEmpty() ? Component.translatable("civmod.gui.settler.mission.no_mission") : info.mission();
        graphics.text(font, CivGui.fit(font, mission, imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, 46, CivGui.HINT_COLOR, false);
        graphics.text(font, Component.translatable("civmod.gui.settler.mission.offered"), CivGui.PADDING, MISSION_OPTIONS_Y - 11,
                CivGui.LABEL_COLOR, false);
    }

    private static java.util.Optional<SettlerInfo.JobState> state(SettlerInfo info, int index) {
        return index >= 0 && index < info.jobStates().size() ? java.util.Optional.of(info.jobStates().get(index)) : java.util.Optional.empty();
    }

    @Override
    protected List<TabBar.Tab> bottomTabs() {
        return menu.getInfo().jobs().stream().limit(SettlerMenu.MAX_JOB_TABS)
                .map(job -> new TabBar.Tab(job.jobType().displayName(), job.jobType().icon())).toList();
    }

    @Override
    protected void extractTabBackground(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (tab == SettlerMenu.TAB_OVERVIEW) {
            int x = leftPos + CivGui.PADDING + font.width(Component.translatable("civmod.gui.settler.food")) + 4;
            CivGui.foodBar(graphics, x, topPos + FOOD_Y - 1, menu.getInfo().food().level(), menu.getInfo().food().saturation());
        }
        if (tab >= SettlerMenu.FIRST_JOB_TAB) {
            state(menu.getInfo(), tab - SettlerMenu.FIRST_JOB_TAB).ifPresent(state -> {
                SettlerInfo.SkillInfo skill = state.skill();
                if (!skill.limited() && !skill.atCap()) {
                    int x = leftPos + CivGui.PADDING;
                    int y = topPos + EXP_BAR_Y;
                    graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, EXP_BACKGROUND, x, y, EXP_BAR_WIDTH, 5);
                    int filled = Math.round(EXP_BAR_WIDTH * Math.min(1.0F, skill.exp() / (float) Math.max(1, skill.needed())));
                    if (filled > 0) {
                        graphics.enableScissor(x, y, x + filled, y + 5);
                        graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, EXP_PROGRESS, x, y, EXP_BAR_WIDTH, 5);
                        graphics.disableScissor();
                    }
                }
            });
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
            SettlerInfo.SkillInfo skill = state.skill();
            if (skill.limited()) {
                graphics.text(font, Component.translatable("civmod.gui.settler.limited", skill.level(), skill.effective(), skill.blockLevel()),
                        CivGui.PADDING, 45, LIMITED_COLOR, false);
            } else if (skill.atCap()) {
                graphics.text(font, Component.translatable("civmod.gui.settler.level", skill.level()), CivGui.PADDING, 45, CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.max_level"), CivGui.PADDING, EXP_BAR_Y - 1,
                        LIMITED_COLOR, false);
            } else {
                graphics.text(font, Component.translatable("civmod.gui.settler.level", skill.level()), CivGui.PADDING, 45, CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.exp", skill.exp(), skill.needed()), CivGui.PADDING + EXP_BAR_WIDTH + 4,
                        EXP_BAR_Y - 1, CivGui.HINT_COLOR, false);
            }
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
            if (state.found().isEmpty()) {
                return;
            }
            graphics.text(font, Component.translatable("civmod.gui.settler.found"), CivGui.PADDING, FOUND_Y - 10, CivGui.LABEL_COLOR, false);
            for (int i = 0; i < state.found().size(); i++) {
                ItemStack stack = state.found().get(i);
                boolean missing = i < state.missing().size() && state.missing().get(i);
                int x = CivGui.PADDING + (i % BUFFER_COLUMNS) * 18;
                int y = FOUND_Y + (i / BUFFER_COLUMNS) * 18;
                graphics.item(stack, x, y);
                if (missing) {
                    // Greyed out: the current tool cannot obtain this type.
                    graphics.fill(x, y, x + 16, y + 16, 0xA0C6C6C6);
                }
                if (mouseX >= leftPos + x && mouseX < leftPos + x + 16 && mouseY >= topPos + y && mouseY < topPos + y + 16) {
                    List<Component> notes = i < state.notes().size() ? state.notes().get(i) : List.of();
                    List<Component> lines = new java.util.ArrayList<>(notes.isEmpty() ? List.of(stack.getHoverName()) : notes);
                    if (missing) {
                        lines.add(Component.translatable("civmod.gui.settler.found.needs_tool").withStyle(net.minecraft.ChatFormatting.RED));
                    }
                    graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
                }
            }
        });
    }
}
