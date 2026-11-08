package com.gilfort.civmod.client.gui;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.ToolTexts;
import com.gilfort.civmod.menu.NeedsLayout;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.menu.StorageTerminal;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerJobs;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Screen of a settler: Overview (name, status, settlement, dismiss when homeless), Jobs (priority list; "⇄" opens the
 * job switcher listing the other Jobs unlocked at that job's JobBlock), Inventory (tool belt, offhand, pockets), Needs
 * (per need tier, the tier the settler works in highlighted; scrolls when the list is longer than the window), Mission
 * (the one Mission Job, chosen from those the Mission Board offers; only for settlers with a Mission Job) and one tab
 * per job below the panel (status and buffer).
 */
public class SettlerScreen extends CivContainerScreen<SettlerMenu> {

    private static final int ROW_HEIGHT = 22;
    /** Extra height of a Jobs tab row that shows the tool line. */
    private static final int TOOL_LINE = 10;
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
    /** Top edge of the Needs list's window: where the highlight of the first tier starts. */
    private static final int NEEDS_VIEW_TOP = NEEDS_TOP - 2;
    /** Gap between the Needs list and its scrollbar. */
    private static final int NEEDS_SCROLLBAR_GAP = 4;

    /** The job whose switcher the Jobs tab shows, -1 for the priority list. */
    private int switching = -1;
    private final StorageTabView storage;
    /** The Needs tab's scroll position in pixels. */
    private final ListScroller needsScroller = new ListScroller(this);

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public SettlerScreen(SettlerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SettlerMenu.IMAGE_WIDTH, SettlerMenu.IMAGE_HEIGHT, List.of(
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.storage"), new ItemStack(Items.BARREL)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.overview"), new ItemStack(Items.PLAYER_HEAD)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.jobs"), new ItemStack(Items.IRON_PICKAXE)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.inventory"), new ItemStack(Items.CHEST)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.needs"), new ItemStack(Items.BREAD)),
                new TabBar.Tab(Component.translatable("civmod.gui.settler.tab.mission"), new ItemStack(Items.MAP))));
        this.storage = new StorageTabView(this, menu);
    }

    /**
     * Client handler of {@link StorageTerminal.Sync}: updates the open settler window's Storage tab.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleTerminal(StorageTerminal.Sync payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof SettlerMenu open)) {
            return;
        }
        boolean shownChanged = payload.applyTo(open);
        if (minecraft.gui.screen() instanceof SettlerScreen screen && screen.menu == open) {
            screen.storage.dataChanged();
            if (shownChanged) {
                // The settler lost or got the Clerk job: the Storage tab goes or comes.
                screen.rebuildWidgets();
            }
        }
    }

    @Override
    protected void initContent() {
        storage.init(imageWidth);
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
            int y = topPos + 22 + rowOffset(info, i);
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

    /** The height of a Jobs tab row: a Job with tool progression has one more line. */
    private static int rowHeight(SettlerInfo info, int index) {
        return ROW_HEIGHT + (toolBand(info, index) > 0 ? TOOL_LINE : 0);
    }

    /** The distance of a Jobs tab row from the first one. */
    private static int rowOffset(SettlerInfo info, int index) {
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset += rowHeight(info, i);
        }
        return offset;
    }

    private static int toolBand(SettlerInfo info, int index) {
        return index < info.jobStates().size() ? info.jobStates().get(index).toolBand() : 0;
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
            case SettlerMenu.TAB_STORAGE -> storage.extractLabels(graphics, mouseX, mouseY);
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
                Component xp = Component.translatable("civmod.gui.settler.xp", info.xp());
                int xpX = imageWidth - CivGui.PADDING - font.width(xp);
                graphics.text(font, xp, xpX, FOOD_Y, CivGui.NEED_MET_COLOR, false);
                if (mouseX >= leftPos + xpX && mouseX < leftPos + xpX + font.width(xp) && mouseY >= topPos + FOOD_Y && mouseY < topPos + FOOD_Y + font.lineHeight) {
                    graphics.setTooltipForNextFrame(font, Component.translatable("civmod.gui.settler.xp.tooltip"), mouseX, mouseY);
                }
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
                        if (state.toolBand() > 0) {
                            int width = imageWidth - 2 * CivGui.PADDING;
                            graphics.text(font, CivGui.fit(font, ToolTexts.line(state.toolBand()), width), CivGui.PADDING, rowY + 20,
                                    CivGui.HINT_COLOR, false);
                            if (mouseX >= leftPos + CivGui.PADDING && mouseX < leftPos + CivGui.PADDING + width && mouseY >= topPos + rowY + 20
                                    && mouseY < topPos + rowY + 20 + font.lineHeight) {
                                graphics.setTooltipForNextFrame(font, ToolTexts.lineTooltip(), mouseX, mouseY);
                            }
                        }
                    });
                    y += rowHeight(info, i);
                }
                Component laborer = info.helping().map(job -> Component.translatable("civmod.gui.settler.laborer.helping", job.displayName()))
                        .orElseGet(() -> Component.translatable("civmod.gui.settler.laborer"));
                graphics.text(font, CivGui.fit(font, laborer, imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, y, CivGui.HINT_COLOR, false);
            }
            case SettlerMenu.TAB_INVENTORY -> {
                graphics.text(font, Component.translatable("civmod.gui.settler.tools"), CivGui.PADDING, SettlerMenu.BELT_Y - 10, CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.offhand"), SettlerMenu.OFFHAND_X, SettlerMenu.BELT_Y - 10,
                        CivGui.LABEL_COLOR, false);
                graphics.text(font, Component.translatable("civmod.gui.settler.pockets"), CivGui.PADDING, SettlerMenu.POCKETS_Y - 10,
                        CivGui.LABEL_COLOR, false);
            }
            case SettlerMenu.TAB_NEEDS -> extractNeedsTab(graphics, info.needs(), mouseX, mouseY);
            case SettlerMenu.TAB_MISSION -> extractMissionTab(graphics, info);
            default -> extractJobTab(graphics, info, tab - SettlerMenu.FIRST_JOB_TAB, mouseX, mouseY);
        }
    }

    /** Bottom edge of the Needs list's window (panel coordinates): the panel's border. */
    private int needsViewBottom() {
        return imageHeight - CivGui.PADDING;
    }

    /** Height of the Needs list's window (panel coordinates), the scrollbar's height. */
    private int needsViewHeight() {
        return needsViewBottom() - NEEDS_VIEW_TOP;
    }

    /** How far the Needs list scrolls, in pixels: 0 while it fits the window. */
    private int needsMaxOffset() {
        return NeedsLayout.maxOffset(NeedsLayout.contentHeight(menu.getInfo().needs()), needsViewBottom() - NEEDS_TOP);
    }

    /** Right edge of the Needs list's content (panel coordinates): left of the scrollbar while the list scrolls. */
    private int needsRight(int maxOffset) {
        return imageWidth - CivGui.PADDING - (maxOffset > 0 ? CivGui.SCROLLBAR_WIDTH + NEEDS_SCROLLBAR_GAP : 0);
    }

    /** Left edge of the Needs list's scrollbar (panel coordinates). */
    private int needsScrollbarX() {
        return imageWidth - CivGui.PADDING - CivGui.SCROLLBAR_WIDTH;
    }

    /**
     * One section per tier with visible needs: the tier the settler works in highlighted, higher tiers dimmed as preview.
     * The list scrolls when it is longer than the window; what is scrolled out is clipped.
     */
    private void extractNeedsTab(GuiGraphicsExtractor graphics, NeedsReport needs, int mouseX, int mouseY) {
        int maxOffset = needsMaxOffset();
        int offset = needsScroller.offset(maxOffset);
        int right = needsRight(maxOffset);
        int width = right - CivGui.PADDING;
        boolean mouseInView = mouseY >= topPos + NEEDS_VIEW_TOP && mouseY < topPos + needsViewBottom();
        graphics.enableScissor(CivGui.PADDING - 2, NEEDS_VIEW_TOP, right + 2, needsViewBottom());
        for (int tier = 1; tier <= NeedState.MAX_TIER; tier++) {
            List<NeedsReport.Row> rows = needs.rows(tier);
            if (rows.isEmpty()) {
                continue;
            }
            boolean preview = tier > needs.tier();
            int y = NEEDS_TOP - offset + NeedsLayout.sectionTop(needs, tier);
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.settler.needs.tier." + tier), width), CivGui.PADDING, y,
                    preview ? CivGui.HINT_COLOR : CivGui.LABEL_COLOR, false);
            y += NeedsLayout.LINE;
            for (NeedsReport.Row row : rows) {
                Component mark = Component.literal(row.met() ? "✓" : "✗");
                int nameX = CivGui.PADDING + font.width(mark) + 3;
                graphics.text(font, mark, CivGui.PADDING, y, preview ? CivGui.HINT_COLOR : row.met() ? CivGui.NEED_MET_COLOR : CivGui.ERROR_COLOR, false);
                graphics.text(font, row.name(), nameX, y, preview ? CivGui.HINT_COLOR : CivGui.LABEL_COLOR, false);
                graphics.text(font, CivGui.fit(font, row.detail(), width - nameX + CivGui.PADDING), nameX, y + NeedsLayout.LINE, CivGui.HINT_COLOR, false);
                int iconX = nameX;
                for (ItemStack icon : row.icons()) {
                    if (iconX + NeedsLayout.ICON > right) {
                        break;
                    }
                    graphics.item(icon, iconX, y + 2 * NeedsLayout.LINE);
                    iconX += NeedsLayout.ICON;
                }
                int rowHeight = NeedsLayout.rowHeight(row);
                // Only a row that is shown has a tooltip: the mouse is inside the window and over the row.
                if (!row.tooltip().getString().isEmpty() && mouseInView && mouseX >= leftPos + CivGui.PADDING && mouseX < leftPos + right
                        && mouseY >= topPos + y && mouseY < topPos + y + rowHeight) {
                    graphics.setTooltipForNextFrame(font, row.tooltip(), mouseX, mouseY);
                }
                y += rowHeight;
            }
        }
        graphics.disableScissor();
        needsScroller.extract(graphics, needsScrollbarX(), NEEDS_VIEW_TOP, needsViewHeight(), maxOffset);
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

    private boolean onStorageTab() {
        return menu.getActiveTab() == SettlerMenu.TAB_STORAGE;
    }

    private boolean onNeedsTab() {
        return menu.getActiveTab() == SettlerMenu.TAB_NEEDS;
    }

    /** Back to the top of the Needs list whenever a tab is opened. */
    @Override
    protected void onTabChanged(int tab) {
        needsScroller.reset();
    }

    /**
     * @return the strip with the sort button outside the panel's left edge (absolute), empty while another tab is shown;
     *         the recipe viewers keep their overlays off it
     */
    public Optional<Rect2i> sortStrip() {
        return onStorageTab() ? Optional.of(storage.strip()) : Optional.empty();
    }

    @Override
    protected void extractBehindPanel(GuiGraphicsExtractor graphics, int tab) {
        if (onStorageTab()) {
            storage.extractStrip(graphics);
        }
    }

    /** The strip is part of the window: a click on it never counts as a click outside, which would drop the cursor item. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return !(onStorageTab() && storage.overStrip(mouseX, mouseY)) && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    /** The Storage tab's search field takes the whole top row, so the settler's name stays on the other tabs. */
    @Override
    protected void extractTitle(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!onStorageTab()) {
            super.extractTitle(graphics, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return onStorageTab() && storage.mouseClicked(event)
                || onNeedsTab() && event.button() == InputConstants.MOUSE_BUTTON_LEFT
                        && needsScroller.press(event.x(), event.y(), needsScrollbarX(), NEEDS_VIEW_TOP, needsViewHeight(), needsMaxOffset())
                || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return onStorageTab() && storage.mouseScrolled(scrollY)
                || onNeedsTab() && needsScroller.wheel(scrollY, needsMaxOffset(), NeedsLayout.LINE)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return onStorageTab() && storage.mouseDragged(event)
                || onNeedsTab() && needsScroller.drag(event.y(), NEEDS_VIEW_TOP, needsViewHeight(), needsMaxOffset())
                || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return storage.mouseReleased() || needsScroller.release() || super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return onStorageTab() && storage.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    protected List<Component> helpLines(int tab) {
        if (tab == SettlerMenu.TAB_STORAGE) {
            return List.of(Component.translatable("civmod.gui.storage.help.take"), Component.translatable("civmod.gui.storage.help.take_half"),
                    Component.translatable("civmod.gui.storage.help.take_one"), Component.translatable("civmod.gui.storage.help.store"),
                    Component.translatable("civmod.gui.storage.help.store_one"),
                    Component.translatable("civmod.gui.storage.help.shift"));
        }
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

    /** The Storage tab only for Clerks, the Mission tab only for settlers with a Mission Job (linked to a Mission Board). */
    @Override
    protected boolean isTabShown(int tab) {
        if (tab == SettlerMenu.TAB_STORAGE) {
            return menu.terminal().available();
        }
        return tab != SettlerMenu.TAB_MISSION || menu.getInfo().missionJob().isPresent();
    }

    @Override
    protected List<TabBar.Tab> bottomTabs() {
        return menu.getInfo().jobs().stream().limit(SettlerMenu.MAX_JOB_TABS)
                .map(job -> new TabBar.Tab(job.jobType().displayName(), job.jobType().icon())).toList();
    }

    @Override
    protected void extractTabBackground(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (tab == SettlerMenu.TAB_STORAGE) {
            storage.extractBackground(graphics);
        }
        if (tab == SettlerMenu.TAB_OVERVIEW) {
            int x = leftPos + CivGui.PADDING + font.width(Component.translatable("civmod.gui.settler.food")) + 4;
            CivGui.foodBar(graphics, x, topPos + FOOD_Y - 1, menu.getInfo().food().level(), menu.getInfo().food().saturation());
        }
        if (tab == SettlerMenu.TAB_NEEDS) {
            NeedsReport needs = menu.getInfo().needs();
            List<NeedsReport.Row> rows = needs.rows(needs.tier());
            if (!rows.isEmpty()) {
                // The highlight scrolls with the list and is clipped with it.
                int maxOffset = needsMaxOffset();
                int right = needsRight(maxOffset);
                int y = topPos + NEEDS_TOP + NeedsLayout.sectionTop(needs, needs.tier()) - needsScroller.offset(maxOffset);
                graphics.enableScissor(leftPos + CivGui.PADDING - 2, topPos + NEEDS_VIEW_TOP, leftPos + right + 2, topPos + needsViewBottom());
                CivGui.inset(graphics, leftPos + CivGui.PADDING - 2, y - 2, right - CivGui.PADDING + 4, NeedsLayout.sectionHeight(rows) + 2);
                graphics.disableScissor();
            }
        }
        if (tab >= SettlerMenu.FIRST_JOB_TAB) {
            state(menu.getInfo(), tab - SettlerMenu.FIRST_JOB_TAB).ifPresent(state -> {
                SettlerInfo.SkillInfo skill = state.skill();
                if (!skill.atCap()) {
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
            }
            if (!skill.atCap()) {
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
