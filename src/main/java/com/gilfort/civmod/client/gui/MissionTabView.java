package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.MissionsTab;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The Mission Board's "Missions" tab of the {@link JobBlockScreen}: the daily mission offer (a click on a row marks or
 * unmarks it) and the assignment view of a marked entry (a right click opens it, a click on a settler assigns); the
 * client side of {@link MissionsTab}.
 */
final class MissionTabView implements JobBlockTabView {

    private static final int MISSION_LIST_Y = 34;
    private static final int MISSION_ROWS = 5;
    /** Settler rows of the assignment view (the buttons take the place of the last row). */
    private static final int ASSIGN_ROWS = 4;
    private static final int TOOLTIP_WIDTH = 200;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final MissionsTab missions;
    private int missionOffset;
    /** The offer entry whose assignment view is open, {@code -1} while the offer is shown. */
    private int assigning = -1;
    private int settlerOffset;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    MissionTabView(JobBlockScreen screen, MissionsTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.missions = tab;
    }

    private Font font() {
        return screen.getFont();
    }

    private static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    /** Adds the buttons of the assignment view while it is open (called on every widget rebuild). */
    @Override
    public void init() {
        if (assigning < 0) {
            return;
        }
        int buttonWidth = (screen.panelWidth() - 2 * CivGui.PADDING - 4) / 2;
        int y = screen.top() + screen.panelHeight() - 28;
        int left = screen.left();
        Button unlock = screen.addWidget(missions.index(), Button.builder(Component.translatable("civmod.gui.mission.assign.unlock"),
                button -> assign(-1)).bounds(left + CivGui.PADDING, y, buttonWidth, 20).build());
        unlock.active = assigning < menu.getInfo().missions().size() && menu.getInfo().missions().get(assigning).locked();
        unlock.setTooltip(Tooltip.create(Component.translatable("civmod.gui.mission.assign.unlock.tooltip")));
        screen.addWidget(missions.index(), Button.builder(Component.translatable("gui.back"), button -> showOffer())
                .bounds(left + CivGui.PADDING + buttonWidth + 4, y, buttonWidth, 20).build());
    }

    /** The Mission Board's Info tab has no Free button. */
    @Override
    public boolean offersUnassign() {
        return false;
    }

    /** Another tab was selected: the assignment view closes. */
    @Override
    public void onTabChanged() {
        if (assigning >= 0) {
            showOffer();
        }
    }

    /** Draws the list inset (absolute coordinates). */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab shown) {
        int height = assigning >= 0 ? ASSIGN_ROWS * JobBlockScreen.ROW_HEIGHT + 2 * CivGui.INSET_PADDING
                : screen.panelHeight() - MISSION_LIST_Y - CivGui.PADDING;
        CivGui.inset(graphics, screen.left() + CivGui.PADDING, screen.top() + MISSION_LIST_Y, screen.panelWidth() - 2 * CivGui.PADDING, height);
    }

    /** Draws the offer or the assignment view (coordinates relative to the panel). */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        if (assigning >= 0) {
            extractAssignment(graphics, mouseX, mouseY);
        } else {
            extractMissions(graphics, mouseX, mouseY);
        }
    }

    /**
     * Draws the missions tab: the daily offer, one row per entry (icon, title, recommended level, duration, rolls and a
     * mark checkbox), the description in the row tooltip; below it the missions settlers of this board took, with who
     * took them (not clickable). More than {@value #MISSION_ROWS} rows scroll with the mouse wheel.
     */
    private void extractMissions(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = font();
        List<MissionOffer.Entry> offer = menu.getInfo().missions();
        List<JobBlockInfo.TakenMission> taken = menu.getInfo().taken();
        int rows = offer.size() + taken.size();
        long marked = offer.stream().filter(MissionOffer.Entry::marked).count();
        graphics.text(font, Component.translatable("civmod.gui.job_block.missions"), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        Component count = Component.translatable("civmod.gui.job_block.missions.marked", marked, offer.size());
        graphics.text(font, count, screen.panelWidth() - CivGui.PADDING - font.width(count), 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = screen.panelWidth() - 2 * left;
        if (rows == 0) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.job_block.missions.none"), left,
                    MISSION_LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        missionOffset = Math.clamp(missionOffset, 0, Math.max(0, rows - MISSION_ROWS));
        if (rows > MISSION_ROWS) {
            // Scroll arrows in a column on the right.
            width -= JobBlockScreen.SCROLL_COLUMN;
            int arrowX = left + width + 1;
            int color = CivGui.LABEL_COLOR;
            graphics.text(font, Component.literal("▲"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING, missionOffset > 0 ? color : CivGui.HINT_COLOR, false);
            graphics.text(font, Component.literal("▼"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING + MISSION_ROWS * JobBlockScreen.ROW_HEIGHT - font.lineHeight,
                    missionOffset + MISSION_ROWS < rows ? color : CivGui.HINT_COLOR, false);
        }
        int box = Checkbox.getBoxSize(font);
        int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < Math.min(MISSION_ROWS, rows - missionOffset); row++) {
            int index = missionOffset + row;
            int y = MISSION_LIST_Y + CivGui.INSET_PADDING + row * JobBlockScreen.ROW_HEIGHT;
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
        int box = Checkbox.getBoxSize(font());
        CivGui.checkbox(graphics, selected, hovered, x, rowY + (JobBlockScreen.ROW_HEIGHT - box) / 2 - 1, box);
    }

    /**
     * @param id a mission id
     * @return the mission, if the client knows it
     */
    static Optional<Mission> mission(Identifier id) {
        return minecraft().level.registryAccess().lookup(Missions.KEY).flatMap(registry -> registry.getOptional(id));
    }

    /**
     * @param id      a mission id
     * @param mission the mission, if known
     * @return the mission title, or its id if the client does not know the mission
     */
    static Component title(Identifier id, Optional<Mission> mission) {
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
                lines.addAll(font().split(found.description().copy().withStyle(ChatFormatting.GRAY), TOOLTIP_WIDTH));
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
        lines.add(Component.translatable(entry.marked() ? "civmod.gui.mission.marked" : "civmod.gui.mission.unmarked")
                .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        return lines;
    }

    /**
     * @return the help lines of the Missions tab: the offer's or the assignment's usage
     */
    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        if (assigning >= 0) {
            return List.of(Component.translatable("civmod.gui.mission.help.assign"));
        }
        return List.of(Component.translatable("civmod.gui.mission.help.mark"), Component.translatable("civmod.gui.mission.help.lock"),
                Component.translatable("civmod.gui.mission.help.marked"));
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
        OptionalInt picker = MissionRules.picker(board.offer(), Missions.all(minecraft().level.registryAccess()), index, board.pickers());
        if (picker.isEmpty()) {
            return List.of(Component.translatable("civmod.gui.mission.chances.nobody").withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        JobBlockInfo.MissionSettler settler = board.settlers().get(picker.getAsInt());
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.translatable("civmod.gui.mission.chances.picker", settler.name(), settler.skill()).getVisualOrderText());
        chancesLines(chances(mission.get(), settler)).forEach(line -> lines.add(line.getVisualOrderText()));
        return lines;
    }

    /** The chances of a settler of the board on a mission, from the outcome constants the server sent. */
    private MissionRules.Chances chances(Mission mission, JobBlockInfo.MissionSettler settler) {
        return MissionRules.chances(menu.getInfo().missionTab().rules(), mission.recommendedLevel(), settler.skill(), settler.job());
    }

    /** "Success: 75%", "Failed: 22%", "Death: 3%" on one line each, the death chance in red when it is high. */
    private static List<Component> chancesLines(MissionRules.Chances chances) {
        return List.of(Component.translatable("civmod.gui.mission.chance.success", percent(chances.success())),
                Component.translatable("civmod.gui.mission.chance.failed", percent(chances.failed())),
                Component.translatable("civmod.gui.mission.chance.death", death(chances)));
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
        screen.rebuild();
    }

    /** Back to the offer. */
    private void showOffer() {
        assigning = -1;
        screen.rebuild();
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
        int button = missions.assignButtonId(assigning, settler);
        screen.clickMenuButton(button);
        showOffer();
    }

    /**
     * Draws the assignment view: the settlers of the board whose Mission Job may take the mission (name, chances,
     * Mission Job, skill), the current assignee checked; a click assigns. More than {@value #ASSIGN_ROWS} rows scroll.
     */
    private void extractAssignment(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = font();
        JobBlockInfo.MissionTab board = menu.getInfo().missionTab();
        if (assigning >= board.offer().size()) {
            return;
        }
        MissionOffer.Entry entry = board.offer().get(assigning);
        Optional<Mission> mission = mission(entry.mission());
        Component title = Component.translatable("civmod.gui.mission.assign.title", title(entry.mission(), mission));
        graphics.text(font, CivGui.fit(font, title, screen.panelWidth() - 2 * CivGui.PADDING), CivGui.PADDING, 22, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = screen.panelWidth() - 2 * left;
        List<Integer> eligible = eligible();
        if (eligible.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.mission.assign.none"), left,
                    MISSION_LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        settlerOffset = Math.clamp(settlerOffset, 0, Math.max(0, eligible.size() - ASSIGN_ROWS));
        if (eligible.size() > ASSIGN_ROWS) {
            width -= JobBlockScreen.SCROLL_COLUMN;
            int arrowX = left + width + 1;
            graphics.text(font, Component.literal("▲"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING,
                    settlerOffset > 0 ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
            graphics.text(font, Component.literal("▼"), arrowX, MISSION_LIST_Y + CivGui.INSET_PADDING + ASSIGN_ROWS * JobBlockScreen.ROW_HEIGHT - font.lineHeight,
                    settlerOffset + ASSIGN_ROWS < eligible.size() ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
        }
        int box = Checkbox.getBoxSize(font);
        int hovered = settlerRowAt(mouseX, mouseY);
        for (int row = 0; row < Math.min(ASSIGN_ROWS, eligible.size() - settlerOffset); row++) {
            int index = settlerOffset + row;
            JobBlockInfo.MissionSettler settler = board.settlers().get(eligible.get(index));
            int y = MISSION_LIST_Y + CivGui.INSET_PADDING + row * JobBlockScreen.ROW_HEIGHT;
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
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal(settler.name()));
            tooltip.addAll(chancesLines(chances(mission.get(), settler)));
            graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }
    }

    /**
     * @return the index (in the eligible settlers) of the settler row under the mouse, {@code -1} if none
     */
    private int settlerRowAt(double mouseX, double mouseY) {
        if (assigning < 0) {
            return -1;
        }
        int rows = eligible().size();
        int left = screen.left() + CivGui.PADDING + CivGui.INSET_PADDING;
        int top = screen.top() + MISSION_LIST_Y + CivGui.INSET_PADDING;
        int right = screen.left() + screen.panelWidth() - CivGui.PADDING - CivGui.INSET_PADDING - (rows > ASSIGN_ROWS ? JobBlockScreen.SCROLL_COLUMN : 0);
        if (mouseX < left || mouseX >= right || mouseY < top) {
            return -1;
        }
        int row = (int) ((mouseY - top) / JobBlockScreen.ROW_HEIGHT);
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
        if (assigning >= 0) {
            return -1;
        }
        int rows = menu.getInfo().missions().size() + menu.getInfo().taken().size();
        int left = screen.left() + CivGui.PADDING + CivGui.INSET_PADDING;
        int top = screen.top() + MISSION_LIST_Y + CivGui.INSET_PADDING;
        int right = screen.left() + screen.panelWidth() - CivGui.PADDING - CivGui.INSET_PADDING - (rows > MISSION_ROWS ? JobBlockScreen.SCROLL_COLUMN : 0);
        if (mouseX < left || mouseX >= right || mouseY < top) {
            return -1;
        }
        int row = (int) ((mouseY - top) / JobBlockScreen.ROW_HEIGHT);
        int index = missionOffset + row;
        return row < MISSION_ROWS && index < rows ? index : -1;
    }

    /**
     * A click on the missions tab: a settler row assigns, a right click on a marked entry opens its assignment view, a
     * left click marks or unmarks an entry.
     *
     * @return whether the click was taken
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        int settler = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? settlerRowAt(event.x(), event.y()) : -1;
        if (settler >= 0) {
            screen.clickSound();
            assign(eligible().get(settler));
            return true;
        }
        int row = rowAt(event.x(), event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && row >= 0 && row < menu.getInfo().missions().size()
                && menu.getInfo().missions().get(row).marked()) {
            screen.clickSound();
            showAssignment(row);
            return true;
        }
        int index = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? row : -1;
        if (index >= 0 && index < menu.getInfo().missions().size()) {
            screen.clickMenuButton(missions.markButtonId(index, !menu.getInfo().missions().get(index).marked()));
            screen.clickSound();
            return true;
        }
        return false;
    }

    /**
     * Scrolls the assignment view or the offer.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (assigning >= 0) {
            settlerOffset = Math.clamp(settlerOffset - (int) Math.signum(scrollY), 0, Math.max(0, eligible().size() - ASSIGN_ROWS));
            return true;
        }
        int offered = menu.getInfo().missions().size() + menu.getInfo().taken().size();
        if (offered > MISSION_ROWS) {
            missionOffset = Math.clamp(missionOffset - (int) Math.signum(scrollY), 0, offered - MISSION_ROWS);
            return true;
        }
        return false;
    }
}
