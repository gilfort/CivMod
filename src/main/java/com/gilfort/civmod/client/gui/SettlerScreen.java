package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerJobs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Screen of a settler: Overview (name, status, settlement, dismiss when homeless), Jobs (priority list; "⇄" opens the
 * job switcher listing the other Jobs unlocked at that job's JobBlock), Inventory
 * (tool belt, offhand, pockets), Needs (per need tier, the tier the settler works in highlighted), Mission (the one Mission Job, chosen from those the Mission Board offers; only for
 * settlers with a Mission Job) and one tab per job below the panel (status and buffer).
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
    /** Y of the job switcher's first Job button. */
    private static final int SWITCH_TOP = 34;
    private static final int SWITCH_ICON = 16;
    /** Y of the Needs tab's first line. */
    private static final int NEEDS_TOP = 22;
    private static final int NEEDS_LINE = 10;
    /** Space between two tiers in the Needs tab. */
    private static final int NEEDS_GAP = 4;
    private static final int NEED_MET_COLOR = 0xFF2E7D32;

    /** The job whose switcher the Jobs tab shows, -1 for the priority list. */
    private int switching = -1;

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
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.needs"), new ItemStack(Items.BREAD)),
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
        if (switching >= 0 && switching < jobs.size()) {
            initSwitcher(info, switching);
            return;
        }
        switching = -1;
        for (int i = 0; i < jobs.size(); i++) {
            int y = topPos + 22 + i * ROW_HEIGHT;
            int index = i;
            if (state(info, i).filter(state -> !state.switchTo().isEmpty()).isPresent()) {
                Button change = addTabWidget(SettlerMenu.TAB_JOBS, Button.builder(Component.literal("⇄"), b -> {
                    switching = index;
                    rebuildWidgets();
                }).bounds(right - 4 * SMALL_BUTTON - 6, y, SMALL_BUTTON, SMALL_BUTTON).build());
                change.setTooltip(Tooltip.create(Component.translatable("civmod.gui.settler.job.switch")));
            }
            Button up = jobButton("▲", "civmod.gui.settler.job.up", SettlerJobs.MOVE_UP + i, right - 3 * SMALL_BUTTON - 4, y);
            up.active = i > 0;
            Button down = jobButton("▼", "civmod.gui.settler.job.down", SettlerJobs.MOVE_DOWN + i, right - 2 * SMALL_BUTTON - 2, y);
            down.active = i < jobs.size() - 1;
            jobButton("✕", "civmod.gui.settler.job.remove", SettlerJobs.REMOVE + i, right - SMALL_BUTTON, y);
        }
    }

    /** The job switcher in the Jobs tab: one button per Job unlocked at the job's JobBlock, and Cancel. */
    private void initSwitcher(SettlerInfo info, int index) {
        List<Job> options = state(info, index).map(SettlerInfo.JobState::switchTo).orElse(List.of());
        int x = leftPos + CivGui.PADDING + SWITCH_ICON + 4;
        int width = imageWidth - 2 * CivGui.PADDING - SWITCH_ICON - 4;
        for (int i = 0; i < options.size(); i++) {
            Job job = options.get(i);
            addTabWidget(SettlerMenu.TAB_JOBS, Button.builder(job.displayName(), b -> switchTo(index, job))
                    .bounds(x, topPos + SWITCH_TOP + i * ROW_HEIGHT, width, 20).build());
        }
        addTabWidget(SettlerMenu.TAB_JOBS, Button.builder(CommonComponents.GUI_CANCEL, b -> {
            switching = -1;
            rebuildWidgets();
        }).bounds(x, topPos + SWITCH_TOP + options.size() * ROW_HEIGHT, width, 20).build());
    }

    private void switchTo(int index, Job job) {
        if (menu.switchJob(minecraft.player, index, job)) {
            ClientPacketDistributor.sendToServer(new SettlerMenu.SwitchJob(menu.containerId, index, job));
        }
        switching = -1;
        rebuildWidgets();
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
                if (switching >= 0 && switching < info.jobs().size()) {
                    graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.settler.switch.title",
                            info.jobs().get(switching).jobType().displayName()), imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, 22,
                            CivGui.LABEL_COLOR, false);
                    List<Job> options = state(info, switching).map(SettlerInfo.JobState::switchTo).orElse(List.of());
                    for (int i = 0; i < options.size(); i++) {
                        graphics.item(options.get(i).icon(), CivGui.PADDING, SWITCH_TOP + i * ROW_HEIGHT + 2);
                    }
                    return;
                }
                int y = 24;
                for (int i = 0; i < info.jobs().size(); i++) {
                    SettlerJob job = info.jobs().get(i);
                    int rowY = y - 2;
                    graphics.text(font, job.jobType().displayName(), CivGui.PADDING, rowY, CivGui.LABEL_COLOR, false);
                    int levelX = CivGui.PADDING + font.width(job.jobType().displayName()) + 4;
                    state(info, i).ifPresent(state -> {
                        graphics.text(font, Component.translatable("civmod.gui.settler.level_short", state.skill().level()), levelX, rowY,
                                state.skill().limited() ? LIMITED_COLOR : CivGui.HINT_COLOR, false);
                        int buttons = state.switchTo().isEmpty() ? 3 * SMALL_BUTTON + 6 : 4 * SMALL_BUTTON + 8;
                        graphics.text(font, CivGui.fit(font, state.text(), imageWidth - 2 * CivGui.PADDING - buttons), CivGui.PADDING,
                                rowY + 10, CivGui.HINT_COLOR, false);
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
            case SettlerMenu.TAB_NEEDS -> extractNeedsTab(graphics, info.needs());
            case SettlerMenu.TAB_MISSION -> extractMissionTab(graphics, info);
            default -> extractJobTab(graphics, info, tab - SettlerMenu.FIRST_JOB_TAB, mouseX, mouseY);
        }
    }

    /** One section per tier with visible needs: the tier the settler works in highlighted, higher tiers dimmed as preview. */
    private void extractNeedsTab(GuiGraphicsExtractor graphics, NeedsReport needs) {
        int y = NEEDS_TOP;
        int width = imageWidth - 2 * CivGui.PADDING;
        for (int tier = 1; tier <= NeedState.MAX_TIER; tier++) {
            List<NeedsReport.Row> rows = needs.rows(tier);
            if (rows.isEmpty()) {
                continue;
            }
            boolean preview = tier > needs.tier();
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.settler.needs.tier." + tier), width), CivGui.PADDING, y,
                    preview ? CivGui.HINT_COLOR : CivGui.LABEL_COLOR, false);
            y += NEEDS_LINE;
            for (NeedsReport.Row row : rows) {
                Component mark = Component.literal(row.met() ? "✓" : "✗");
                int nameX = CivGui.PADDING + font.width(mark) + 3;
                graphics.text(font, mark, CivGui.PADDING, y, preview ? CivGui.HINT_COLOR : row.met() ? NEED_MET_COLOR : CivGui.ERROR_COLOR, false);
                graphics.text(font, row.name(), nameX, y, preview ? CivGui.HINT_COLOR : CivGui.LABEL_COLOR, false);
                graphics.text(font, CivGui.fit(font, row.detail(), width - nameX + CivGui.PADDING), nameX, y + NEEDS_LINE, CivGui.HINT_COLOR, false);
                y += 2 * NEEDS_LINE;
            }
            y += NEEDS_GAP;
        }
    }

    /** The height of a tier's section in the Needs tab. */
    private static int needsSection(int rows) {
        return NEEDS_LINE + rows * 2 * NEEDS_LINE;
    }

    private void extractMissionTab(GuiGraphicsExtractor graphics, SettlerInfo info) {
        java.util.Optional<SettlerJob> missionJob = info.missionJob();
        if (missionJob.isEmpty()) {
            // The tab is hidden without a Mission Job.
            return;
        }
        graphics.text(font, Component.translatable("civmod.gui.settler.mission.job", missionJob.get().jobType().displayName()), CivGui.PADDING, 22,
                CivGui.LABEL_COLOR, false);
        graphics.text(font, Component.translatable("civmod.gui.settler.mission.board", missionJob.get().jobBlock().toShortString()), CivGui.PADDING,
                34, CivGui.LABEL_COLOR, false);
        Component mission = info.mission().getString().isEmpty() ? Component.translatable("civmod.gui.settler.mission.no_mission") : info.mission();
        graphics.text(font, CivGui.fit(font, mission, imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, 46, CivGui.HINT_COLOR, false);
    }

    private static java.util.Optional<SettlerInfo.JobState> state(SettlerInfo info, int index) {
        return index >= 0 && index < info.jobStates().size() ? java.util.Optional.of(info.jobStates().get(index)) : java.util.Optional.empty();
    }

    @Override
    protected List<Component> helpLines(int tab) {
        if (tab >= SettlerMenu.FIRST_JOB_TAB) {
            return List.of(Component.translatable("civmod.gui.settler.help.cap"));
        }
        if (tab == SettlerMenu.TAB_JOBS) {
            return List.of(Component.translatable("civmod.gui.settler.help.switch"));
        }
        if (tab == SettlerMenu.TAB_NEEDS) {
            return List.of(Component.translatable("civmod.gui.settler.help.needs"));
        }
        return List.of();
    }

    /** The Mission tab only for settlers with a Mission Job (linked to a Mission Board). */
    @Override
    protected boolean isTabShown(int tab) {
        return tab != SettlerMenu.TAB_MISSION || menu.getInfo().missionJob().isPresent();
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
        if (tab == SettlerMenu.TAB_NEEDS) {
            NeedsReport needs = menu.getInfo().needs();
            int y = topPos + NEEDS_TOP;
            for (int tier = 1; tier <= NeedState.MAX_TIER; tier++) {
                int rows = needs.rows(tier).size();
                if (rows == 0) {
                    continue;
                }
                if (tier == needs.tier()) {
                    CivGui.inset(graphics, leftPos + CivGui.PADDING - 2, y - 2, imageWidth - 2 * CivGui.PADDING + 4, needsSection(rows) + 2);
                }
                y += needsSection(rows) + NEEDS_GAP;
            }
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
            int slots = state(menu.getInfo(), tab - SettlerMenu.FIRST_JOB_TAB).map(state -> state.carry() > 0 ? state.carry() : 18).orElse(18);
            for (int i = 0; i < slots; i++) {
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
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.settler.status", state.text()),
                    imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, 34, CivGui.LABEL_COLOR, false);
            SettlerInfo.SkillInfo skill = state.skill();
            if (skill.limited()) {
                graphics.text(font, CivGui.fit(font, Component.translatable(skill.limitedByNeeds() ? "civmod.gui.settler.limited_needs" : "civmod.gui.settler.limited", skill.level(), skill.effective()),
                        imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, 45, LIMITED_COLOR, false);
            } else if (skill.atCap()) {
                graphics.text(font, Component.translatable("civmod.gui.settler.level", skill.level()), CivGui.PADDING, 45, CivGui.LABEL_COLOR, false);
                graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.settler.max_level"), imageWidth - 2 * CivGui.PADDING),
                        CivGui.PADDING, EXP_BAR_Y - 1, LIMITED_COLOR, false);
            } else {
                graphics.text(font, Component.translatable("civmod.gui.settler.level", skill.level()), CivGui.PADDING, 45, CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.exp", skill.exp(), skill.needed()), CivGui.PADDING + EXP_BAR_WIDTH + 4,
                        EXP_BAR_Y - 1, CivGui.HINT_COLOR, false);
            }
            // A Deliveryman's carry inventory counts stacks, like a player's.
            Component load = state.carry() > 0 ? Component.translatable("civmod.gui.settler.carrying", state.preview().size(), state.carry())
                    : Component.translatable("civmod.gui.settler.buffer", state.buffered(), JobBuffer.CAPACITY);
            graphics.text(font, load, CivGui.PADDING, BUFFER_Y - 10, CivGui.LABEL_COLOR, false);
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
