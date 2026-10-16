package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.gilfort.civmod.job.JobProfile;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.settlement.SettlementRules;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Screen of a JobBlock: storage tab and info tab (job, level, skill cap, settlement status, upgrade button); the
 * Mission Board adds a missions tab (the daily mission offer; a click on a row marks or unmarks it).
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
    private int missionOffset;
    /** The offer entry whose assignment view is open, {@code -1} while the offer is shown. */
    private int assigning = -1;
    private int settlerOffset;
    private static final int LINE_HEIGHT = 11;
    private static final int MISSION_LIST_Y = 34;
    private static final int MISSION_ROW_HEIGHT = 23;
    private static final int MISSION_ROWS = 5;
    /** Settler rows of the assignment view (the buttons take the place of the last row). */
    private static final int ASSIGN_ROWS = 4;
    private static final int TOOLTIP_WIDTH = 200;
    private static final int SCROLL_COLUMN = 8;
    private static final Identifier CHECKBOX = Identifier.withDefaultNamespace("widget/checkbox");
    private static final Identifier CHECKBOX_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/checkbox_highlighted");
    private static final Identifier CHECKBOX_SELECTED = Identifier.withDefaultNamespace("widget/checkbox_selected");
    private static final Identifier CHECKBOX_SELECTED_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/checkbox_selected_highlighted");

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
        if (assigning >= 0) {
            int buttonWidth = (imageWidth - 2 * CivGui.PADDING - 4) / 2;
            int y = topPos + imageHeight - 28;
            Button unlock = addTabWidget(JobBlockMenu.TAB_MISSIONS, Button.builder(Component.translatable("civmod.gui.mission.assign.unlock"),
                    button -> assign(-1)).bounds(leftPos + CivGui.PADDING, y, buttonWidth, 20).build());
            unlock.active = assigning < current.missions().size() && current.missions().get(assigning).locked();
            unlock.setTooltip(Tooltip.create(Component.translatable("civmod.gui.mission.assign.unlock.tooltip")));
            addTabWidget(JobBlockMenu.TAB_MISSIONS, Button.builder(Component.translatable("gui.back"), button -> showOffer())
                    .bounds(leftPos + CivGui.PADDING + buttonWidth + 4, y, buttonWidth, 20).build());
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
            int height = assigning >= 0 ? ASSIGN_ROWS * MISSION_ROW_HEIGHT + 2 * CivGui.INSET_PADDING : imageHeight - MISSION_LIST_Y - CivGui.PADDING;
            CivGui.inset(graphics, leftPos + CivGui.PADDING, topPos + MISSION_LIST_Y, imageWidth - 2 * CivGui.PADDING, height);
        }
    }

    @Override
    protected void onTabChanged(int tab) {
        if (assigning >= 0) {
            showOffer();
        }
    }

    @Override
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (tab == JobBlockMenu.TAB_MISSIONS) {
            if (assigning >= 0) {
                extractAssignment(graphics, mouseX, mouseY);
            } else {
                extractMissions(graphics, mouseX, mouseY);
            }
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
                graphics.setComponentTooltipForNextFrame(font, settlerLines(info), mouseX, mouseY);
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
     * @return one tooltip line per settler of the board: its name and, if any, the mission it took
     */
    private List<Component> settlerLines(JobBlockInfo info) {
        List<Component> lines = new ArrayList<>();
        for (String name : info.assignees()) {
            Optional<JobBlockInfo.TakenMission> taken = info.taken().stream().filter(entry -> entry.settler().equals(name)).findFirst();
            lines.add(taken.<Component>map(entry -> Component.translatable("civmod.gui.job_block.settler_mission", name,
                    Component.translatable(entry.departed() ? "civmod.settler.status.on_mission" : "civmod.settler.status.mission_reserved",
                            title(entry.mission(), mission(entry.mission()))))).orElseGet(() -> Component.literal(name)));
        }
        return lines;
    }

    /**
     * Draws the missions tab: the daily offer, one row per entry (icon, title, recommended level, duration, rolls and a
     * mark checkbox), the description in the row tooltip; below it the missions settlers of this board took, with who
     * took them (not clickable). More than {@value #MISSION_ROWS} rows scroll with the mouse wheel.
     */
    private void extractMissions(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<MissionOffer.Entry> offer = menu.getInfo().missions();
        List<JobBlockInfo.TakenMission> taken = menu.getInfo().taken();
        int rows = offer.size() + taken.size();
        long marked = offer.stream().filter(MissionOffer.Entry::marked).count();
        graphics.text(font, Component.translatable("civmod.gui.job_block.missions"), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        Component count = Component.translatable("civmod.gui.job_block.missions.marked", marked, offer.size());
        graphics.text(font, count, imageWidth - CivGui.PADDING - font.width(count), 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = imageWidth - 2 * left;
        if (rows == 0) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.job_block.missions.none"), left,
                    MISSION_LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        missionOffset = Math.clamp(missionOffset, 0, Math.max(0, rows - MISSION_ROWS));
        if (rows > MISSION_ROWS) {
            // Scroll arrows in a column on the right.
            width -= SCROLL_COLUMN;
            int arrowX = left + width + 1;
            int color = CivGui.LABEL_COLOR;
            graphics.text(font, Component.literal("▲"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING, missionOffset > 0 ? color : CivGui.HINT_COLOR, false);
            graphics.text(font, Component.literal("▼"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING + MISSION_ROWS * MISSION_ROW_HEIGHT - font.lineHeight,
                    missionOffset + MISSION_ROWS < rows ? color : CivGui.HINT_COLOR, false);
        }
        int box = Checkbox.getBoxSize(font);
        int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < Math.min(MISSION_ROWS, rows - missionOffset); row++) {
            int index = missionOffset + row;
            int y = MISSION_LIST_Y + CivGui.INSET_PADDING + row * MISSION_ROW_HEIGHT;
            if (index >= offer.size()) {
                JobBlockInfo.TakenMission entry = taken.get(index - offer.size());
                Optional<Mission> mission = mission(entry.mission());
                graphics.item(mission.map(Mission::iconStack).orElse(ItemStack.EMPTY), left, y + 2);
                graphics.text(font, CivGui.fit(font, title(entry.mission(), mission), width - 20), left + 20, y + 1, CivGui.HINT_COLOR, false);
                graphics.text(font, CivGui.fit(font, takenText(entry), width - 20), left + 20, y + 11, CivGui.HINT_COLOR, false);
                continue;
            }
            MissionOffer.Entry entry = offer.get(index);
            Optional<Mission> mission = mission(entry.mission());
            graphics.item(mission.map(Mission::iconStack).orElse(ItemStack.EMPTY), left, y + 2);
            int textWidth = width - 20 - box - 2;
            graphics.text(font, CivGui.fit(font, title(entry.mission(), mission), textWidth), left + 20, y + 1, CivGui.LABEL_COLOR, false);
            if (entry.locked()) {
                // An assigned or locked mission shows who takes it instead of the details (still in the tooltip).
                graphics.text(font, CivGui.fit(font, lockText(entry), textWidth), left + 20, y + 11, CivGui.HINT_COLOR, false);
            } else {
                mission.ifPresent(found -> graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.mission.details",
                        found.recommendedLevel(), found.days(), found.rolls()), textWidth), left + 20, y + 11, CivGui.HINT_COLOR, false));
            }
            checkbox(graphics, entry.marked(), index == hovered, left + width - box, y);
        }
        if (hovered >= 0) {
            graphics.setTooltipForNextFrame(font, hovered < offer.size() ? missionTooltip(hovered)
                    : takenTooltip(taken.get(hovered - offer.size())), mouseX, mouseY);
        }
    }

    /** Draws a vanilla checkbox at the right end of a list row. */
    private void checkbox(GuiGraphicsExtractor graphics, boolean selected, boolean hovered, int x, int rowY) {
        Identifier sprite = selected
                ? (hovered ? CHECKBOX_SELECTED_HIGHLIGHTED : CHECKBOX_SELECTED)
                : (hovered ? CHECKBOX_HIGHLIGHTED : CHECKBOX);
        int box = Checkbox.getBoxSize(font);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, rowY + (MISSION_ROW_HEIGHT - box) / 2 - 1, box, box);
    }

    private Optional<Mission> mission(Identifier id) {
        return minecraft.level.registryAccess().lookup(Missions.KEY).flatMap(registry -> registry.getOptional(id));
    }

    /** The mission title, or its id if the client does not know the mission. */
    private static Component title(Identifier id, Optional<Mission> mission) {
        return mission.map(Mission::title).orElseGet(() -> Component.literal(id.toString()));
    }

    private static Component takenText(JobBlockInfo.TakenMission entry) {
        return Component.translatable(entry.departed() ? "civmod.gui.mission.taken.departed" : "civmod.gui.mission.taken.reserved", entry.settler());
    }

    /** Title, description, recommended level, duration and rolls of a mission. */
    private List<FormattedCharSequence> missionLines(Identifier id) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        Optional<Mission> mission = mission(id);
        lines.add(title(id, mission).getVisualOrderText());
        mission.ifPresent(found -> {
            if (!found.description().getString().isEmpty()) {
                lines.addAll(font.split(found.description().copy().withStyle(ChatFormatting.GRAY), TOOLTIP_WIDTH));
            }
            lines.add(Component.translatable("civmod.gui.mission.recommended_level", found.recommendedLevel()).getVisualOrderText());
            lines.add(Component.translatable("civmod.gui.mission.duration", found.days()).getVisualOrderText());
            lines.add(Component.translatable("civmod.gui.mission.rolls", found.rolls()).getVisualOrderText());
        });
        return lines;
    }

    private List<FormattedCharSequence> missionTooltip(int index) {
        MissionOffer.Entry entry = menu.getInfo().missions().get(index);
        List<FormattedCharSequence> lines = missionLines(entry.mission());
        if (entry.locked()) {
            lines.add(lockText(entry).copy().withStyle(ChatFormatting.GOLD).getVisualOrderText());
        }
        lines.addAll(pickerLines(index));
        lines.add(Component.translatable(entry.marked() ? "civmod.gui.mission.unmark" : "civmod.gui.mission.mark")
                .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        if (entry.marked()) {
            lines.add(Component.translatable("civmod.gui.mission.assign.hint").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        }
        return lines;
    }

    /** "Assigned to <name>", or "Locked" when the assigned settler died or left the board (or is not loaded). */
    private Component lockText(MissionOffer.Entry entry) {
        Optional<String> name = entry.assignee().flatMap(id -> menu.getInfo().missionTab().settler(id)).map(JobBlockInfo.MissionSettler::name);
        if (name.isPresent()) {
            return Component.translatable("civmod.gui.mission.assigned", name.get());
        }
        return Component.translatable(entry.assignee().isPresent() ? "civmod.gui.mission.assigned.unknown" : "civmod.gui.mission.locked");
    }

    /**
     * The settler the automatic pick rule would send on an offer entry, with its chances, or that none would take it.
     */
    private List<FormattedCharSequence> pickerLines(int index) {
        JobBlockInfo.MissionTab board = menu.getInfo().missionTab();
        Optional<Mission> mission = mission(board.offer().get(index).mission());
        if (mission.isEmpty()) {
            return List.of();
        }
        OptionalInt picker = MissionRules.picker(board.offer(), Missions.all(minecraft.level.registryAccess()), index, board.pickers());
        if (picker.isEmpty()) {
            return List.of(Component.translatable("civmod.gui.mission.chances.nobody").withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        JobBlockInfo.MissionSettler settler = board.settlers().get(picker.getAsInt());
        return List.of(Component.translatable("civmod.gui.mission.chances.picker", settler.name(), settler.skill()).getVisualOrderText(),
                chancesLine(chances(mission.get(), settler)).getVisualOrderText());
    }

    /** The chances of a settler of the board on a mission, from the outcome constants the server sent. */
    private MissionRules.Chances chances(Mission mission, JobBlockInfo.MissionSettler settler) {
        return MissionRules.chances(menu.getInfo().missionTab().rules(), mission.recommendedLevel(), settler.skill());
    }

    /** "Success 75 % · Failed 22 % · Death 3 %", the death chance in red when it is high. */
    private static Component chancesLine(MissionRules.Chances chances) {
        return Component.translatable("civmod.gui.mission.chances", percent(chances.success()), percent(chances.failed()), death(chances));
    }

    /** "75% / 22% / 3%" (success / failed / death), the death chance in red when it is high. */
    private static Component chancesShort(MissionRules.Chances chances) {
        return Component.translatable("civmod.gui.mission.chances.short", percent(chances.success()), percent(chances.failed()), death(chances));
    }

    private static Component death(MissionRules.Chances chances) {
        MutableComponent death = percent(chances.death());
        return chances.highDeath() ? death.withStyle(ChatFormatting.RED) : death;
    }

    private static MutableComponent percent(double chance) {
        return Component.translatable("civmod.gui.mission.percent", Math.round(chance * 100));
    }

    // ---------------------------------------------------------------- assignment view

    /** Opens the assignment view of an offer entry. */
    private void showAssignment(int index) {
        assigning = index;
        settlerOffset = 0;
        rebuildWidgets();
    }

    /** Back to the offer. */
    private void showOffer() {
        assigning = -1;
        rebuildWidgets();
    }

    /**
     * @return the indices (in the board's settler list) of the settlers whose Mission Job may take the mission being
     *         assigned
     */
    private List<Integer> eligible() {
        JobBlockInfo.MissionTab board = menu.getInfo().missionTab();
        if (assigning < 0 || assigning >= board.offer().size()) {
            return List.of();
        }
        Optional<Mission> mission = mission(board.offer().get(assigning).mission());
        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < board.settlers().size(); i++) {
            JobBlockInfo.MissionSettler settler = board.settlers().get(i);
            if (mission.filter(found -> found.jobs().contains(settler.job())).isPresent()) {
                eligible.add(i);
            }
        }
        return eligible;
    }

    /** Assigns the mission being assigned to a settler (index in the board's settler list) or unlocks it ({@code -1}). */
    private void assign(int settler) {
        int button = JobBlockMenu.assignButtonId(assigning, settler);
        menu.clickMenuButton(minecraft.player, button);
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
        showOffer();
    }

    /**
     * Draws the assignment view: the settlers of the board whose Mission Job may take the mission (name, chances,
     * Mission Job, skill), the current assignee checked; a click assigns. More than {@value #ASSIGN_ROWS} rows scroll.
     */
    private void extractAssignment(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        JobBlockInfo.MissionTab board = menu.getInfo().missionTab();
        if (assigning >= board.offer().size()) {
            return;
        }
        MissionOffer.Entry entry = board.offer().get(assigning);
        Optional<Mission> mission = mission(entry.mission());
        Component title = Component.translatable("civmod.gui.mission.assign.title", title(entry.mission(), mission));
        graphics.text(font, CivGui.fit(font, title, imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = imageWidth - 2 * left;
        List<Integer> eligible = eligible();
        if (eligible.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.mission.assign.none"), left,
                    MISSION_LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        settlerOffset = Math.clamp(settlerOffset, 0, Math.max(0, eligible.size() - ASSIGN_ROWS));
        if (eligible.size() > ASSIGN_ROWS) {
            width -= SCROLL_COLUMN;
            int arrowX = left + width + 1;
            graphics.text(font, Component.literal("▲"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING,
                    settlerOffset > 0 ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
            graphics.text(font, Component.literal("▼"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING + ASSIGN_ROWS * MISSION_ROW_HEIGHT - font.lineHeight,
                    settlerOffset + ASSIGN_ROWS < eligible.size() ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
        }
        int box = Checkbox.getBoxSize(font);
        int hovered = settlerRowAt(mouseX, mouseY);
        for (int row = 0; row < Math.min(ASSIGN_ROWS, eligible.size() - settlerOffset); row++) {
            int index = settlerOffset + row;
            JobBlockInfo.MissionSettler settler = board.settlers().get(eligible.get(index));
            int y = MISSION_LIST_Y + CivGui.INSET_PADDING + row * MISSION_ROW_HEIGHT;
            int textWidth = width - box - 2;
            int nameWidth = textWidth;
            if (mission.isPresent()) {
                Component chances = chancesShort(chances(mission.get(), settler));
                int chancesWidth = font.width(chances);
                graphics.text(font, chances, left + textWidth - chancesWidth, y + 1, CivGui.LABEL_COLOR, false);
                nameWidth -= chancesWidth + 4;
            }
            graphics.text(font, CivGui.fit(font, Component.literal(settler.name()), nameWidth), left, y + 1, CivGui.LABEL_COLOR, false);
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.mission.assign.settler", settler.job().displayName(),
                    settler.skill()), textWidth), left, y + 11, CivGui.HINT_COLOR, false);
            checkbox(graphics, entry.assignee().filter(settler.id()::equals).isPresent(), index == hovered, left + width - box, y);
        }
        if (hovered >= 0 && mission.isPresent()) {
            JobBlockInfo.MissionSettler settler = board.settlers().get(eligible.get(hovered));
            graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal(settler.name()), chancesLine(chances(mission.get(), settler))),
                    mouseX, mouseY);
        }
    }

    /**
     * @return the index (in the eligible settlers) of the settler row under the mouse, {@code -1} if none
     */
    private int settlerRowAt(double mouseX, double mouseY) {
        if (menu.getActiveTab() != JobBlockMenu.TAB_MISSIONS || assigning < 0) {
            return -1;
        }
        int rows = eligible().size();
        int left = leftPos + CivGui.PADDING + CivGui.INSET_PADDING;
        int top = topPos + MISSION_LIST_Y + CivGui.INSET_PADDING;
        int right = leftPos + imageWidth - CivGui.PADDING - CivGui.INSET_PADDING - (rows > ASSIGN_ROWS ? SCROLL_COLUMN : 0);
        if (mouseX < left || mouseX >= right || mouseY < top) {
            return -1;
        }
        int row = (int) ((mouseY - top) / MISSION_ROW_HEIGHT);
        int index = settlerOffset + row;
        return row < ASSIGN_ROWS && index < rows ? index : -1;
    }

    private List<FormattedCharSequence> takenTooltip(JobBlockInfo.TakenMission entry) {
        List<FormattedCharSequence> lines = missionLines(entry.mission());
        lines.add(takenText(entry).copy().withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        return lines;
    }

    /**
     * @return the index of the mission row under the mouse (offer entries first, then taken missions), {@code -1} if none
     */
    private int rowAt(double mouseX, double mouseY) {
        if (menu.getActiveTab() != JobBlockMenu.TAB_MISSIONS || assigning >= 0) {
            return -1;
        }
        int rows = menu.getInfo().missions().size() + menu.getInfo().taken().size();
        int left = leftPos + CivGui.PADDING + CivGui.INSET_PADDING;
        int top = topPos + MISSION_LIST_Y + CivGui.INSET_PADDING;
        int right = leftPos + imageWidth - CivGui.PADDING - CivGui.INSET_PADDING - (rows > MISSION_ROWS ? SCROLL_COLUMN : 0);
        if (mouseX < left || mouseX >= right || mouseY < top) {
            return -1;
        }
        int row = (int) ((mouseY - top) / MISSION_ROW_HEIGHT);
        int index = missionOffset + row;
        return row < MISSION_ROWS && index < rows ? index : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int settler = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? settlerRowAt(event.x(), event.y()) : -1;
        if (settler >= 0) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            assign(eligible().get(settler));
            return true;
        }
        int row = rowAt(event.x(), event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && row >= 0 && row < menu.getInfo().missions().size()
                && menu.getInfo().missions().get(row).marked()) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            showAssignment(row);
            return true;
        }
        int index = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? row : -1;
        if (index >= 0 && index < menu.getInfo().missions().size()) {
            int button = JobBlockMenu.markButtonId(index, !menu.getInfo().missions().get(index).marked());
            menu.clickMenuButton(minecraft.player, button);
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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
        int eligible = eligible().size();
        if (menu.getActiveTab() == JobBlockMenu.TAB_MISSIONS && assigning >= 0) {
            settlerOffset = Math.clamp(settlerOffset - (int) Math.signum(scrollY), 0, Math.max(0, eligible - ASSIGN_ROWS));
            return true;
        }
        int offered = menu.getInfo().missions().size() + menu.getInfo().taken().size();
        if (menu.getActiveTab() == JobBlockMenu.TAB_MISSIONS && offered > MISSION_ROWS) {
            missionOffset = Math.clamp(missionOffset - (int) Math.signum(scrollY), 0, offered - MISSION_ROWS);
            return true;
        }
        int size = menu.getInfo().nextCosts().size();
        if (menu.getActiveTab() == JobBlockMenu.TAB_INFO && size > COST_SLOTS && costRowY >= 0
                && mouseY >= topPos + costRowY - 2 && mouseY < topPos + costRowY + 18) {
            costOffset = Math.clamp(costOffset - (int) Math.signum(scrollY), 0, size - COST_SLOTS);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
