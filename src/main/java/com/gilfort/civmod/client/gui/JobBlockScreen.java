package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.JobProfile;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTab;
import com.gilfort.civmod.menu.PatternTab;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.settlement.SettlementRules;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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

    /** Rules grid of the Clerk: 5 rows of 9 (9 rules per JobBlock level). */
    private static final int RULES_X = CivGui.PADDING;
    private static final int RULES_Y = 18;
    private static final int RULE_ROWS = 5;
    /** The rule editor (amount, priority, delete) below the grid. */
    private static final int RULE_EDIT_Y = RULES_Y + RULE_ROWS * 18 + 16;
    private static final int REQUEST_LIST_Y = 34;
    private static final int INACTIVE_TINT = 0xA08B8B8B;
    private static final int HOVER_TINT = 0x80FFFFFF;
    private static final int SELECTED_COLOR = 0xFFFFFFFF;

    /** The selected rule slot of the Clerk's Rules tab, {@code -1} if none. */
    private int selectedSlot = -1;
    private int requestOffset;
    private @Nullable Button amountMinus;
    private @Nullable Button amountPlus;
    private @Nullable Button priorityMinus;
    private @Nullable Button priorityPlus;
    private @Nullable Button delete;
    private @Nullable EditBox amountBox;
    private @Nullable EditBox priorityBox;

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
        super(menu, inventory, title, 176, menu.isTall() ? JobBlockMenu.CLERK_HEIGHT : 166, tabs(menu));
    }

    private static List<TabBar.Tab> tabs(JobBlockMenu menu) {
        List<TabBar.Tab> tabs = new java.util.ArrayList<>(List.of(
                new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.storage"), new ItemStack(Items.CHEST)),
                new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.info"), new ItemStack(Items.BOOK))));
        if (JobBlockMenu.isMissionBoard(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.missions"), new ItemStack(Items.MAP)));
        }
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.clerk.tab.rules"), new ItemStack(Items.WRITABLE_BOOK)));
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.clerk.tab.requests"), new ItemStack(Items.PAPER)));
        }
        if (JobBlockMenu.isCraftsperson(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.patterns.tab"), new ItemStack(Items.CRAFTING_TABLE)));
        }
        return tabs;
    }

    @Override
    protected void initContent() {
        JobBlockInfo current = menu.getInfo();
        if (JobBlockMenu.isClerk(current)) {
            initRuleEditor();
        }
        if (JobBlockMenu.isCraftsperson(current)) {
            initPatternEditor();
        }
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
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            if (tab == JobBlockMenu.TAB_REQUESTS) {
                CivGui.inset(graphics, leftPos + CivGui.PADDING, topPos + REQUEST_LIST_Y, imageWidth - 2 * CivGui.PADDING,
                        imageHeight - REQUEST_LIST_Y - CivGui.PADDING);
            }
            return;
        }
        if (tab == JobBlockMenu.TAB_MISSIONS && JobBlockMenu.isMissionBoard(menu.getInfo())) {
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
        if (JobBlockMenu.isClerk(menu.getInfo()) && tab == JobBlockMenu.TAB_RULES) {
            extractRules(graphics, mouseX, mouseY);
            return;
        }
        if (JobBlockMenu.isClerk(menu.getInfo()) && tab == JobBlockMenu.TAB_REQUESTS) {
            extractRequests(graphics, mouseX, mouseY);
            return;
        }
        if (JobBlockMenu.isCraftsperson(menu.getInfo()) && tab == JobBlockMenu.TAB_PATTERNS) {
            extractPatterns(graphics, mouseX, mouseY);
            return;
        }
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

    /** "Success 75% · Failed 22% · Death 3%". */
    private static Component chancesLine(MissionRules.Chances chances) {
        return chancesText("civmod.gui.mission.chances", chances);
    }

    /** "75% / 22% / 3%" (success / failed / death). */
    private static Component chancesShort(MissionRules.Chances chances) {
        return chancesText("civmod.gui.mission.chances.short", chances);
    }

    /** Success, failed and death chance in a lang text, the death chance in red when it is high. */
    private static Component chancesText(String key, MissionRules.Chances chances) {
        return Component.translatable(key, percent(chances.success()), percent(chances.failed()), death(chances));
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
        if (clickRule(event) || clickPattern(event)) {
            return true;
        }
        int settler =event.button() == InputConstants.MOUSE_BUTTON_LEFT ? settlerRowAt(event.x(), event.y()) : -1;
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
        int requests = menu.getClerkTab().requests().size();
        if (JobBlockMenu.isClerk(menu.getInfo()) && menu.getActiveTab() == JobBlockMenu.TAB_REQUESTS && requests > requestRows()) {
            requestOffset = Math.clamp(requestOffset - (int) Math.signum(scrollY), 0, requests - requestRows());
            return true;
        }
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

    // ---------------------------------------------------------------- Clerk: rules and requests

    /**
     * Client handler of {@link ClerkTab.Sync}: updates the open Clerk menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleClerkTab(ClerkTab.Sync payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof JobBlockMenu open && open.containerId == payload.containerId()) {
            open.setClerkTab(payload.tab());
            if (minecraft.gui.screen() instanceof JobBlockScreen screen) {
                screen.updateRuleEditor();
            }
        }
    }

    private void initRuleEditor() {
        int y = topPos + RULE_EDIT_Y;
        int x = leftPos + CivGui.PADDING;
        amountMinus = addTabWidget(JobBlockMenu.TAB_RULES, Button.builder(Component.literal("-"), button -> changeAmount(-1))
                .bounds(x, y, 14, 16).build());
        amountBox = addTabWidget(JobBlockMenu.TAB_RULES, new EditBox(font, x + 16, y, 34, 16, Component.translatable("civmod.gui.clerk.rule.amount")));
        amountBox.setMaxLength(4);
        amountBox.setResponder(text -> parse(text).ifPresent(amount -> editSelected(rule -> withAmount(rule, amount))));
        amountPlus = addTabWidget(JobBlockMenu.TAB_RULES, Button.builder(Component.literal("+"), button -> changeAmount(1))
                .bounds(x + 52, y, 14, 16).build());
        priorityMinus = addTabWidget(JobBlockMenu.TAB_RULES, Button.builder(Component.literal("-"), button -> changePriority(-1))
                .bounds(x + 80, y, 14, 16).build());
        priorityBox = addTabWidget(JobBlockMenu.TAB_RULES, new EditBox(font, x + 96, y, 26, 16, Component.translatable("civmod.gui.clerk.rule.priority")));
        priorityBox.setMaxLength(4);
        priorityBox.setResponder(text -> parse(text).ifPresent(priority -> editSelected(rule -> withPriority(rule, priority))));
        priorityPlus = addTabWidget(JobBlockMenu.TAB_RULES, Button.builder(Component.literal("+"), button -> changePriority(1))
                .bounds(x + 124, y, 14, 16).build());
        delete = addTabWidget(JobBlockMenu.TAB_RULES, Button.builder(Component.literal("✕"), button -> deleteSelected())
                .bounds(x + 142, y, 18, 16).build());
        delete.setTooltip(Tooltip.create(Component.translatable("civmod.gui.clerk.rule.delete")));
        Tooltip shift = Tooltip.create(Component.translatable("civmod.gui.clerk.rule.shift"));
        amountMinus.setTooltip(shift);
        amountPlus.setTooltip(shift);
        priorityMinus.setTooltip(shift);
        priorityPlus.setTooltip(shift);
        updateRuleEditor();
    }

    /** Shows the selected rule in the editor (a focused box keeps what the player types). */
    private void updateRuleEditor() {
        if (amountBox == null) {
            return;
        }
        Optional<StockRule> rule = selectedRule();
        boolean editable = rule.isPresent() && rule.get().activeAt(menu.getClerkTab().level());
        for (Button button : List.of(amountMinus, amountPlus, priorityMinus, priorityPlus)) {
            button.active = editable;
        }
        delete.active = rule.isPresent();
        amountBox.setEditable(editable);
        priorityBox.setEditable(editable);
        if (!amountBox.isFocused()) {
            amountBox.setValue(rule.map(found -> Integer.toString(found.amount())).orElse(""));
        }
        if (!priorityBox.isFocused()) {
            priorityBox.setValue(rule.map(found -> Integer.toString(found.priority())).orElse(""));
        }
    }

    private Optional<StockRule> selectedRule() {
        return selectedSlot < 0 ? Optional.empty() : menu.getClerkTab().rule(selectedSlot);
    }

    private static Optional<Integer> parse(String text) {
        try {
            return Optional.of(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void changeAmount(int sign) {
        int step = minecraft.hasShiftDown() ? 10 : 1;
        editSelected(rule -> withAmount(rule, rule.amount() + sign * step));
    }

    private void changePriority(int sign) {
        int step = minecraft.hasShiftDown() ? 10 : 1;
        editSelected(rule -> withPriority(rule, rule.priority() + sign * step));
    }

    private static StockRule withAmount(StockRule rule, int amount) {
        return new StockRule(rule.slot(), rule.item(), amount, rule.priority(), rule.mode());
    }

    private static StockRule withPriority(StockRule rule, int priority) {
        return new StockRule(rule.slot(), rule.item(), rule.amount(), priority, rule.mode());
    }

    /** Changes the selected rule (active slots only) and sends it to the server. */
    private void editSelected(java.util.function.UnaryOperator<StockRule> change) {
        selectedRule().filter(rule -> rule.activeAt(menu.getClerkTab().level())).ifPresent(rule -> {
            StockRule changed = change.apply(rule);
            if (!changed.equals(rule)) {
                sendRule(rule.slot(), Optional.of(changed));
            }
        });
    }

    private void deleteSelected() {
        if (selectedRule().isPresent()) {
            sendRule(selectedSlot, Optional.empty());
        }
    }

    private void sendRule(int slot, Optional<StockRule> rule) {
        ClientPacketDistributor.sendToServer(new ClerkTab.Edit(menu.containerId, slot, rule));
    }

    /**
     * Sets the item of a rule slot, as a ghost: the item is not taken. A new rule keeps the stack's count (at least 1)
     * with priority 0; an existing rule keeps its amount and priority.
     *
     * @param slot  the rule slot
     * @param stack the item
     * @return whether the slot took it (an active slot on the Rules tab)
     */
    public boolean setRuleItem(int slot, ItemStack stack) {
        if (!JobBlockMenu.isClerk(menu.getInfo()) || stack.isEmpty() || slot < 0 || slot >= StockRule.slots(menu.getClerkTab().level())) {
            return false;
        }
        StockRule rule = menu.getClerkTab().rule(slot)
                .map(found -> new StockRule(slot, stack.getItem(), found.amount(), found.priority(), found.mode()))
                .orElseGet(() -> StockRule.minimum(slot, stack.getItem(), Math.max(1, stack.getCount()), 0));
        selectedSlot = slot;
        sendRule(slot, Optional.of(rule));
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }

    /**
     * The screen areas that take a dragged item as a rule item (for JEI / REI ghost drag): the active rule slots while
     * the Rules tab is shown.
     *
     * @return rule slot → absolute area
     */
    public java.util.Map<Integer, Rect2i> ruleTargets() {
        java.util.Map<Integer, Rect2i> targets = new java.util.LinkedHashMap<>();
        if (!JobBlockMenu.isClerk(menu.getInfo()) || menu.getActiveTab() != JobBlockMenu.TAB_RULES) {
            return targets;
        }
        for (int slot = 0; slot < StockRule.slots(menu.getClerkTab().level()); slot++) {
            targets.put(slot, new Rect2i(leftPos + ruleX(slot), topPos + ruleY(slot), 16, 16));
        }
        return targets;
    }

    /**
     * @param x the mouse x position
     * @param y the mouse y position
     * @return the rule slot under the mouse on the Rules tab, {@code -1} if none
     */
    public int ruleSlotAt(double x, double y) {
        if (!JobBlockMenu.isClerk(menu.getInfo()) || menu.getActiveTab() != JobBlockMenu.TAB_RULES) {
            return -1;
        }
        int column = (int) Math.floor((x - leftPos - RULES_X + 1) / 18.0);
        int row = (int) Math.floor((y - topPos - RULES_Y + 1) / 18.0);
        return column >= 0 && column < 9 && row >= 0 && row < RULE_ROWS ? row * 9 + column : -1;
    }

    private static int ruleX(int slot) {
        return RULES_X + (slot % 9) * 18;
    }

    private static int ruleY(int slot) {
        return RULES_Y + (slot / 9) * 18;
    }

    /**
     * A click on a rule slot: with an item on the cursor it becomes the rule's item (not consumed); otherwise a left
     * click selects the rule, a right click deletes it.
     */
    private boolean clickRule(MouseButtonEvent event) {
        int slot = ruleSlotAt(event.x(), event.y());
        if (slot < 0) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            setRuleItem(slot, carried);
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (menu.getClerkTab().rule(slot).isPresent()) {
                sendRule(slot, Optional.empty());
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
            return true;
        }
        selectedSlot = slot;
        amountBox.setFocused(false);
        priorityBox.setFocused(false);
        updateRuleEditor();
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Typing into a number box must not trigger the inventory key or hotbar swaps.
        for (EditBox box : amountBox == null ? List.<EditBox>of() : List.of(amountBox, priorityBox)) {
            if (box.isFocused() && box.visible && !event.isEscape()) {
                box.keyPressed(event);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /**
     * Draws the Rules tab: rule slots in rows of 9 (9 per JobBlock level active, the rest greyed), the selected rule
     * framed, and the editor labels.
     */
    private void extractRules(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ClerkTab tab = menu.getClerkTab();
        int active = StockRule.slots(tab.level());
        int hovered = ruleSlotAt(mouseX, mouseY);
        for (int slot = 0; slot < RULE_ROWS * 9; slot++) {
            int x = ruleX(slot);
            int y = ruleY(slot);
            CivGui.slot(graphics, x, y);
            Optional<StockRule> rule = tab.rule(slot);
            if (rule.isPresent()) {
                ItemStack icon = new ItemStack(rule.get().item());
                graphics.item(icon, x, y);
                graphics.itemDecorations(font, icon, x, y, Integer.toString(rule.get().amount()));
            }
            if (slot >= active) {
                graphics.fill(x, y, x + 16, y + 16, INACTIVE_TINT);
            }
            if (slot == selectedSlot) {
                graphics.outline(x - 1, y - 1, 18, 18, SELECTED_COLOR);
            } else if (slot == hovered) {
                graphics.fill(x, y, x + 16, y + 16, HOVER_TINT);
            }
        }
        Optional<StockRule> selected = selectedRule();
        Component label = selected.<Component>map(rule -> Component.translatable("civmod.gui.clerk.rule.selected", new ItemStack(rule.item()).getHoverName()))
                .orElseGet(() -> Component.translatable(selectedSlot >= 0 ? "civmod.gui.clerk.rule.empty" : "civmod.gui.clerk.rule.hint"));
        graphics.text(font, CivGui.fit(font, label, imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, RULE_EDIT_Y - 11, CivGui.LABEL_COLOR, false);
        if (hovered >= 0) {
            graphics.setComponentTooltipForNextFrame(font, ruleTooltip(hovered, active), mouseX, mouseY);
        }
    }

    private List<Component> ruleTooltip(int slot, int active) {
        List<Component> lines = new ArrayList<>();
        Optional<StockRule> rule = menu.getClerkTab().rule(slot);
        if (rule.isPresent()) {
            lines.add(new ItemStack(rule.get().item()).getHoverName());
            lines.add(Component.translatable("civmod.gui.clerk.rule.keep", rule.get().amount()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("civmod.gui.clerk.rule.priority_value", rule.get().priority()).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("civmod.gui.clerk.rule.empty_slot"));
        }
        if (slot >= active) {
            lines.add(Component.translatable("civmod.gui.clerk.rule.inactive", slot / StockRule.RULES_PER_LEVEL + 1).withStyle(ChatFormatting.RED));
        } else {
            lines.add(Component.translatable(rule.isPresent() ? "civmod.gui.clerk.rule.click" : "civmod.gui.clerk.rule.set")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private int requestRows() {
        return (imageHeight - REQUEST_LIST_Y - CivGui.PADDING - 2 * CivGui.INSET_PADDING) / MISSION_ROW_HEIGHT;
    }

    /**
     * Draws the Requests tab: one row per request of this Clerk's rules (item, missing amount, priority, status);
     * scrolls with the mouse wheel.
     */
    private void extractRequests(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<ClerkTab.RequestRow> requests = menu.getClerkTab().requests();
        graphics.text(font, Component.translatable("civmod.gui.clerk.requests", requests.size()), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = imageWidth - 2 * left;
        if (requests.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.clerk.requests.none"), left,
                    REQUEST_LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        int rows = requestRows();
        requestOffset = Math.clamp(requestOffset, 0, Math.max(0, requests.size() - rows));
        if (requests.size() > rows) {
            width -= SCROLL_COLUMN;
            int arrowX = left + width + 1;
            graphics.text(font, Component.literal("▲"), arrowX, REQUEST_LIST_Y + CivGui.INSET_PADDING,
                    requestOffset > 0 ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
            graphics.text(font, Component.literal("▼"), arrowX, REQUEST_LIST_Y + CivGui.INSET_PADDING + rows * MISSION_ROW_HEIGHT - font.lineHeight,
                    requestOffset + rows < requests.size() ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
        }
        for (int row = 0; row < Math.min(rows, requests.size() - requestOffset); row++) {
            ClerkTab.RequestRow request = requests.get(requestOffset + row);
            int y = REQUEST_LIST_Y + CivGui.INSET_PADDING + row * MISSION_ROW_HEIGHT;
            graphics.item(new ItemStack(request.item()), left, y + 2);
            Component priority = Component.translatable("civmod.gui.clerk.request.priority", request.priority());
            int priorityWidth = font.width(priority);
            graphics.text(font, priority, left + width - priorityWidth, y + 1, CivGui.HINT_COLOR, false);
            Component title = Component.translatable("civmod.gui.clerk.request.amount", request.amount(), new ItemStack(request.item()).getHoverName());
            graphics.text(font, CivGui.fit(font, title, width - 24 - priorityWidth), left + 20, y + 1, CivGui.LABEL_COLOR, false);
            graphics.text(font, CivGui.fit(font, request.status(), width - 20), left + 20, y + 11, CivGui.HINT_COLOR, false);
            if (mouseX >= leftPos + left && mouseX < leftPos + left + width && mouseY >= topPos + y && mouseY < topPos + y + MISSION_ROW_HEIGHT) {
                graphics.setComponentTooltipForNextFrame(font, List.of(title, request.status(), priority), mouseX, mouseY);
            }
        }
    }

    // ---------------------------------------------------------------- Craftsperson: patterns

    /** The library: 6 rows of 9 pattern slots. */
    private static final int LIBRARY_X = CivGui.PADDING;
    private static final int LIBRARY_Y = 18;
    private static final int LIBRARY_ROWS = 6;
    /** The pattern editor: 3×3 grid and output slot. */
    private static final int GRID_X = 30;
    private static final int GRID_Y = 30;
    private static final int OUTPUT_X = 116;
    private static final int OUTPUT_Y = 48;
    private static final int PATTERN_BUTTONS_Y = 94;
    /** Ghost target ids of the editor (library slots are 0–53). */
    private static final int TARGET_CELL = 100;
    private static final int TARGET_OUTPUT = 200;
    private static final Identifier CHECKMARK = Identifier.withDefaultNamespace("container/beacon/confirm");

    /** The pattern open in the editor, {@code -1} while the library is shown. */
    private int editing = -1;
    private @Nullable Button checkButton;
    private @Nullable Button previousButton;
    private @Nullable Button nextButton;

    /**
     * Client handler of {@link PatternTab.Sync}: updates the open Craftsperson menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handlePatternTab(PatternTab.Sync payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof JobBlockMenu open && open.containerId == payload.containerId()) {
            open.setPatternTab(payload.tab());
            if (minecraft.gui.screen() instanceof JobBlockScreen screen) {
                screen.updatePatternEditor();
            }
        }
    }

    private void initPatternEditor() {
        if (editing < 0) {
            return;
        }
        int y = topPos + PATTERN_BUTTONS_Y;
        int width = (imageWidth - 2 * CivGui.PADDING - 4) / 3;
        checkButton = addTabWidget(JobBlockMenu.TAB_PATTERNS, Button.builder(Component.empty(), button -> sendPattern(
                PatternTab.Edit.toggleCheck(menu.containerId, editing))).bounds(leftPos + CivGui.PADDING, y, width, 20).build());
        addTabWidget(JobBlockMenu.TAB_PATTERNS, Button.builder(Component.translatable("civmod.gui.patterns.clear"), button -> sendPattern(
                PatternTab.Edit.clear(menu.containerId, editing))).bounds(leftPos + CivGui.PADDING + width + 2, y, width, 20).build());
        addTabWidget(JobBlockMenu.TAB_PATTERNS, Button.builder(Component.translatable("gui.back"), button -> showLibrary())
                .bounds(leftPos + CivGui.PADDING + 2 * (width + 2), y, width, 20).build());
        previousButton = addTabWidget(JobBlockMenu.TAB_PATTERNS, Button.builder(Component.literal("<"), button -> sendPattern(
                PatternTab.Edit.cycle(menu.containerId, editing, -1))).bounds(leftPos + OUTPUT_X - 9, topPos + OUTPUT_Y + 20, 16, 16).build());
        nextButton = addTabWidget(JobBlockMenu.TAB_PATTERNS, Button.builder(Component.literal(">"), button -> sendPattern(
                PatternTab.Edit.cycle(menu.containerId, editing, 1))).bounds(leftPos + OUTPUT_X + 9, topPos + OUTPUT_Y + 20, 16, 16).build());
        Tooltip cycle = Tooltip.create(Component.translatable("civmod.gui.patterns.cycle"));
        previousButton.setTooltip(cycle);
        nextButton.setTooltip(cycle);
        updatePatternEditor();
    }

    /** Shows the edited pattern's state on the editor buttons. */
    private void updatePatternEditor() {
        if (checkButton == null || previousButton == null || nextButton == null || editing < 0) {
            return;
        }
        Optional<Pattern> pattern = menu.getPatternTab().pattern(editing);
        boolean checked = pattern.map(Pattern::checked).orElse(false);
        checkButton.setMessage(Component.translatable(checked ? "civmod.gui.patterns.uncheck" : "civmod.gui.patterns.check"));
        checkButton.active = pattern.filter(Pattern::valid).isPresent()
                && (checked || menu.getPatternTab().checked() < menu.getPatternTab().limit());
        boolean recipe = pattern.filter(found -> found.valid() && !found.exact()).isPresent();
        previousButton.active = recipe;
        nextButton.active = recipe;
    }

    private void showEditor(int slot) {
        editing = slot;
        rebuildWidgets();
    }

    private void showLibrary() {
        editing = -1;
        checkButton = null;
        previousButton = null;
        nextButton = null;
        rebuildWidgets();
    }

    private void sendPattern(PatternTab.Edit edit) {
        ClientPacketDistributor.sendToServer(edit);
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static int librarySlotX(int slot) {
        return LIBRARY_X + (slot % 9) * 18;
    }

    private static int librarySlotY(int slot) {
        return LIBRARY_Y + (slot / 9) * 18;
    }

    private static int cellX(int cell) {
        return GRID_X + (cell % 3) * 18;
    }

    private static int cellY(int cell) {
        return GRID_Y + (cell / 3) * 18;
    }

    /**
     * The ghost targets of the Patterns tab, relative to the panel: the library slots (output), or the grid cells and the
     * output of the open pattern.
     */
    private java.util.Map<Integer, Rect2i> patternAreas() {
        java.util.Map<Integer, Rect2i> areas = new java.util.LinkedHashMap<>();
        if (!JobBlockMenu.isCraftsperson(menu.getInfo()) || menu.getActiveTab() != JobBlockMenu.TAB_PATTERNS) {
            return areas;
        }
        if (editing < 0) {
            for (int slot = 0; slot < Pattern.SLOTS; slot++) {
                areas.put(slot, new Rect2i(librarySlotX(slot), librarySlotY(slot), 16, 16));
            }
            return areas;
        }
        for (int cell = 0; cell < Pattern.GRID; cell++) {
            areas.put(TARGET_CELL + cell, new Rect2i(cellX(cell), cellY(cell), 16, 16));
        }
        areas.put(TARGET_OUTPUT, new Rect2i(OUTPUT_X, OUTPUT_Y, 16, 16));
        return areas;
    }

    private int patternTargetAt(double x, double y) {
        for (java.util.Map.Entry<Integer, Rect2i> entry : patternAreas().entrySet()) {
            Rect2i area = entry.getValue();
            if (area.contains((int) Math.floor(x - leftPos), (int) Math.floor(y - topPos))) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /** Puts an item into a pattern target as a ghost (the item is not taken). */
    private boolean setPatternItem(int target, ItemStack stack) {
        if (stack.isEmpty() || target < 0) {
            return false;
        }
        if (target < Pattern.SLOTS) {
            sendPattern(PatternTab.Edit.setOutput(menu.containerId, target, stack.getItem()));
        } else if (target == TARGET_OUTPUT) {
            sendPattern(PatternTab.Edit.setOutput(menu.containerId, editing, stack.getItem()));
        } else {
            sendPattern(PatternTab.Edit.setInput(menu.containerId, editing, target - TARGET_CELL, stack.getItem()));
        }
        return true;
    }

    /**
     * A click on the Patterns tab. Library: with an item on the cursor the slot's pattern is made from that output, a
     * left click opens the editor, a right click checks or unchecks. Editor: with an item a cell (or the output) is set,
     * a right click empties a cell.
     */
    private boolean clickPattern(MouseButtonEvent event) {
        int target = patternTargetAt(event.x(), event.y());
        if (target < 0) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            return setPatternItem(target, carried);
        }
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        if (target < Pattern.SLOTS) {
            if (right) {
                if (menu.getPatternTab().pattern(target).isPresent()) {
                    sendPattern(PatternTab.Edit.toggleCheck(menu.containerId, target));
                }
            } else {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                showEditor(target);
            }
            return true;
        }
        if (right && target != TARGET_OUTPUT) {
            sendPattern(PatternTab.Edit.setInput(menu.containerId, editing, target - TARGET_CELL, Items.AIR));
        }
        return true;
    }

    /** Draws the Patterns tab: the library with checkmarks, or the editor of one pattern. */
    private void extractPatterns(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        PatternTab tab = menu.getPatternTab();
        int hovered = patternTargetAt(mouseX, mouseY);
        if (editing >= 0) {
            extractPatternEditor(graphics, tab, hovered, mouseX, mouseY);
            return;
        }
        for (int slot = 0; slot < LIBRARY_ROWS * 9; slot++) {
            int x = librarySlotX(slot);
            int y = librarySlotY(slot);
            CivGui.slot(graphics, x, y);
            Optional<Pattern> pattern = tab.pattern(slot);
            pattern.filter(Pattern::valid).ifPresent(found -> {
                ItemStack icon = new ItemStack(found.output(), found.count());
                graphics.item(icon, x, y);
                graphics.itemDecorations(font, icon, x, y);
            });
            if (pattern.filter(Pattern::checked).isPresent()) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CHECKMARK, x - 1, y - 1, 9, 9);
            }
            if (slot == hovered) {
                graphics.fill(x, y, x + 16, y + 16, HOVER_TINT);
            }
        }
        int y = LIBRARY_Y + LIBRARY_ROWS * 18 + 3;
        graphics.text(font, Component.translatable("civmod.gui.patterns.checkmarks", tab.checked(), tab.limit()), CivGui.PADDING, y,
                CivGui.LABEL_COLOR, false);
        tab.trim().ifPresent(trim -> graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.patterns.trimmed", trim.removed(),
                trim.limit()), imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, y + 10, ERROR_COLOR, false));
        if (hovered >= 0) {
            graphics.setComponentTooltipForNextFrame(font, patternTooltip(tab.pattern(hovered)), mouseX, mouseY);
        }
    }

    private List<Component> patternTooltip(Optional<Pattern> pattern) {
        List<Component> lines = new ArrayList<>();
        if (pattern.filter(Pattern::valid).isPresent()) {
            Pattern found = pattern.get();
            lines.add(Component.translatable("civmod.gui.clerk.request.amount", found.count(), new ItemStack(found.output()).getHoverName()));
            lines.add(Component.translatable(found.exact() ? "civmod.gui.patterns.exact" : "civmod.gui.patterns.recipe").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable(found.checked() ? "civmod.gui.patterns.checked" : "civmod.gui.patterns.unchecked")
                    .withStyle(found.checked() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            lines.add(Component.translatable("civmod.gui.patterns.slot_hint").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.translatable(pattern.isPresent() ? "civmod.gui.patterns.no_recipe" : "civmod.gui.patterns.empty_slot"));
            lines.add(Component.translatable("civmod.gui.patterns.set_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private void extractPatternEditor(GuiGraphicsExtractor graphics, PatternTab tab, int hovered, int mouseX, int mouseY) {
        Optional<Pattern> pattern = tab.pattern(editing);
        graphics.text(font, Component.translatable("civmod.gui.patterns.editing", editing + 1), CivGui.PADDING, 18, CivGui.LABEL_COLOR, false);
        for (int cell = 0; cell < Pattern.GRID; cell++) {
            int x = cellX(cell);
            int y = cellY(cell);
            CivGui.slot(graphics, x, y);
            int index = cell;
            Item item = pattern.map(found -> found.grid().get(index)).orElse(Items.AIR);
            if (item != Items.AIR) {
                graphics.item(new ItemStack(item), x, y);
            }
            if (TARGET_CELL + cell == hovered) {
                graphics.fill(x, y, x + 16, y + 16, HOVER_TINT);
            }
        }
        graphics.text(font, Component.literal("→"), GRID_X + 3 * 18 + 6, OUTPUT_Y + 4, CivGui.LABEL_COLOR, false);
        CivGui.slot(graphics, OUTPUT_X, OUTPUT_Y);
        pattern.filter(Pattern::valid).ifPresent(found -> {
            ItemStack icon = new ItemStack(found.output(), found.count());
            graphics.item(icon, OUTPUT_X, OUTPUT_Y);
            graphics.itemDecorations(font, icon, OUTPUT_X, OUTPUT_Y);
        });
        if (hovered == TARGET_OUTPUT) {
            graphics.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + 16, OUTPUT_Y + 16, HOVER_TINT);
        }
        Component mode = pattern.map(found -> Component.translatable(!found.valid() ? "civmod.gui.patterns.no_recipe"
                : found.exact() ? "civmod.gui.patterns.exact" : "civmod.gui.patterns.recipe")).orElse(Component.translatable("civmod.gui.patterns.empty_slot"));
        int y = PATTERN_BUTTONS_Y + 24;
        graphics.text(font, CivGui.fit(font, mode, imageWidth - 2 * CivGui.PADDING), CivGui.PADDING, y, CivGui.HINT_COLOR, false);
        graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.patterns.editor_hint"), imageWidth - 2 * CivGui.PADDING),
                CivGui.PADDING, y + 10, CivGui.HINT_COLOR, false);
        if (hovered >= TARGET_CELL && hovered < TARGET_CELL + Pattern.GRID) {
            Item item = pattern.map(found -> found.grid().get(hovered - TARGET_CELL)).orElse(Items.AIR);
            if (item != Items.AIR) {
                graphics.setComponentTooltipForNextFrame(font, List.of(new ItemStack(item).getHoverName()), mouseX, mouseY);
            }
        }
    }

    /**
     * The screen areas that take a dragged item (for JEI / REI ghost drag): the Clerk's active rule slots on the Rules
     * tab, or the Craftsperson's pattern slots on the Patterns tab.
     *
     * @return target id → absolute area
     */
    public java.util.Map<Integer, Rect2i> ghostTargets() {
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            return ruleTargets();
        }
        java.util.Map<Integer, Rect2i> targets = new java.util.LinkedHashMap<>();
        patternAreas().forEach((id, area) -> targets.put(id, new Rect2i(leftPos + area.getX(), topPos + area.getY(), area.getWidth(), area.getHeight())));
        return targets;
    }

    /**
     * @param x the mouse x position
     * @param y the mouse y position
     * @return the ghost target under the mouse, {@code -1} if none
     */
    public int ghostTargetAt(double x, double y) {
        return JobBlockMenu.isClerk(menu.getInfo()) ? ruleSlotAt(x, y) : patternTargetAt(x, y);
    }

    /**
     * Puts a dragged item into a ghost target (not consumed).
     *
     * @param target the target id from {@link #ghostTargets()}
     * @param stack  the item
     * @return whether the target took it
     */
    public boolean acceptGhost(int target, ItemStack stack) {
        return JobBlockMenu.isClerk(menu.getInfo()) ? setRuleItem(target, stack) : setPatternItem(target, stack);
    }

    /**
     * Fills a pattern from a recipe viewer's transfer button: the open pattern, else the first empty library slot.
     *
     * @param recipe     the recipe the viewer shows
     * @param grid       the items it shows, row by row
     * @param doTransfer whether to transfer (false: only check)
     * @return the reason it cannot, empty if it can
     */
    public Optional<Component> transferPattern(Optional<net.minecraft.resources.ResourceKey<net.minecraft.world.item.crafting.Recipe<?>>> recipe,
            List<Item> grid, boolean doTransfer) {
        if (!JobBlockMenu.isCraftsperson(menu.getInfo())) {
            return Optional.of(Component.translatable("civmod.gui.patterns.not_here"));
        }
        int slot = editing;
        for (int candidate = 0; slot < 0 && candidate < Pattern.SLOTS; candidate++) {
            if (menu.getPatternTab().pattern(candidate).isEmpty()) {
                slot = candidate;
            }
        }
        if (slot < 0) {
            return Optional.of(Component.translatable("civmod.gui.patterns.full"));
        }
        if (doTransfer) {
            sendPattern(PatternTab.Edit.transfer(menu.containerId, slot, recipe, grid));
            selectTab(JobBlockMenu.TAB_PATTERNS);
            showEditor(slot);
        }
        return Optional.empty();
    }
}
