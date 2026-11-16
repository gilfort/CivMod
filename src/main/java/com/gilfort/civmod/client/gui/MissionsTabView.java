package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.gilfort.civmod.menu.MissionsTabData;
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
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Mission Board's "Missions" tab of the {@link JobBlockScreen}: the daily mission offer (a click on a row marks or
 * unmarks it) and the assignment view of a marked entry (a right click opens it, a click on a settler assigns); the
 * client side of {@link MissionsTab}.
 */
final class MissionsTabView implements JobBlockTabView {

    private static final int MISSION_ROWS = 5;
    /** Settler rows of the assignment view (the buttons take the place of the last row). */
    private static final int ASSIGN_ROWS = 4;
    /** Panel y of the line above the list (its title or count). */
    static final int TITLE_Y = 22;
    private static final int AUTO_BUTTON_WIDTH = 36;
    private static final int AUTO_BUTTON_HEIGHT = 13;
    /** Panel y of the Auto button, on the line above the list. */
    private static final int AUTO_BUTTON_Y = 19;
    /** Gap between two neighbouring buttons or a button and the text next to it. */
    private static final int GAP = 4;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final MissionsTab missions;
    private final MissionsAutoView auto;
    private int missionOffset;
    /** The offer entry whose assignment view is open, {@code -1} while the offer is shown. */
    private int assigning = -1;
    /** The mission of that entry when the view opened. */
    private Identifier assigningMission;
    private int settlerOffset;
    /** Whether the Auto view (the missions ticked for auto-enable) is shown instead of the offer. */
    private boolean autoView;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    MissionsTabView(JobBlockScreen screen, MissionsTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.missions = tab;
        this.auto = new MissionsAutoView(screen, tab);
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
        int left = screen.left();
        if (autoView) {
            screen.addWidget(missions.index(), Button.builder(Component.translatable("gui.back"), button -> showOffer())
                    .bounds(left + CivGui.PADDING, screen.top() + screen.panelHeight() - 28, screen.panelWidth() - 2 * CivGui.PADDING, 20).build());
            return;
        }
        if (assigning < 0) {
            Button auto = screen.addWidget(missions.index(), Button.builder(Component.translatable("civmod.gui.mission.auto"), button -> showAuto())
                    .bounds(left + screen.panelWidth() - CivGui.PADDING - AUTO_BUTTON_WIDTH, screen.top() + AUTO_BUTTON_Y, AUTO_BUTTON_WIDTH, AUTO_BUTTON_HEIGHT).build());
            auto.setTooltip(Tooltip.create(Component.translatable("civmod.gui.mission.auto.tooltip")));
            return;
        }
        int buttonWidth = (screen.panelWidth() - 2 * CivGui.PADDING - GAP) / 2;
        int y = screen.top() + screen.panelHeight() - 28;
        Button unlock = screen.addWidget(missions.index(), Button.builder(Component.translatable("civmod.gui.mission.assign.unlock"),
                button -> assign(Optional.empty())).bounds(left + CivGui.PADDING, y, buttonWidth, 20).build());
        unlock.active = assigning < board().offer().size() && board().offer().get(assigning).locked();
        unlock.setTooltip(Tooltip.create(Component.translatable("civmod.gui.mission.assign.unlock.tooltip")));
        screen.addWidget(missions.index(), Button.builder(Component.translatable("gui.back"), button -> showOffer())
                .bounds(left + CivGui.PADDING + buttonWidth + GAP, y, buttonWidth, 20).build());
    }

    /** The missions the server sent last. */
    private MissionsTabData board() {
        return missions.data().get();
    }

    /**
     * The server sent a new offer: the widgets follow it, and an assignment view whose entry is no longer the mission it
     * was opened for (a refill, another player) closes.
     */
    @Override
    public void dataChanged() {
        if (assigning >= 0 && (assigning >= board().offer().size() || !board().offer().get(assigning).mission().equals(assigningMission))) {
            assigning = -1;
        }
        screen.rebuild();
    }

    /** Another tab was selected: the assignment view closes. */
    @Override
    public void onTabChanged() {
        if (assigning >= 0 || autoView) {
            showOffer();
        }
    }

    /** Draws the list inset (absolute coordinates). */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab shown) {
        int height = assigning >= 0 || autoView ? ASSIGN_ROWS * JobBlockScreen.ROW_HEIGHT + 2 * CivGui.INSET_PADDING
                : screen.panelHeight() - MissionRows.LIST_Y - CivGui.PADDING;
        CivGui.inset(graphics, screen.left() + CivGui.PADDING, screen.top() + MissionRows.LIST_Y, screen.panelWidth() - 2 * CivGui.PADDING, height);
    }

    /** Draws the offer or the assignment view (coordinates relative to the panel). */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        if (autoView) {
            auto.extract(graphics, mouseX, mouseY);
        } else if (assigning >= 0) {
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
        List<MissionOffer.Entry> offer = board().offer();
        List<MissionsTabData.TakenMission> taken = board().taken();
        int rows = offer.size() + taken.size();
        long marked = offer.stream().filter(MissionOffer.Entry::marked).count();
        graphics.text(font, Component.translatable("civmod.gui.job_block.missions"), CivGui.PADDING, TITLE_Y, CivGui.LABEL_COLOR, false);
        Component count = Component.translatable("civmod.gui.job_block.missions.marked", marked, offer.size());
        graphics.text(font, count, screen.panelWidth() - CivGui.PADDING - AUTO_BUTTON_WIDTH - GAP - font.width(count), TITLE_Y, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = screen.panelWidth() - 2 * left;
        if (rows == 0) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.job_block.missions.none"), left,
                    MissionRows.LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        missionOffset = Math.clamp(missionOffset, 0, Math.max(0, rows - MISSION_ROWS));
        if (rows > MISSION_ROWS) {
            // Scroll arrows in a column on the right.
            width -= JobBlockScreen.SCROLL_COLUMN;
            MissionRows.scrollArrows(graphics, font, left + width + 1, missionOffset, MISSION_ROWS, rows);
        }
        int box = Checkbox.getBoxSize(font);
        int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < Math.min(MISSION_ROWS, rows - missionOffset); row++) {
            int index = missionOffset + row;
            int y = MissionRows.LIST_Y + CivGui.INSET_PADDING + row * JobBlockScreen.ROW_HEIGHT;
            if (index >= offer.size()) {
                MissionsTabData.TakenMission entry = taken.get(index - offer.size());
                Optional<Mission> mission = MissionRows.mission(entry.mission());
                graphics.item(mission.map(Mission::iconStack).orElse(ItemStack.EMPTY), left, y + 2);
                graphics.text(font, CivGui.fit(font, MissionRows.title(entry.mission(), mission), width - 20), left + 20, y + 1, CivGui.HINT_COLOR, false);
                graphics.text(font, CivGui.fit(font, takenText(entry), width - 20), left + 20, y + 11, CivGui.HINT_COLOR, false);
                continue;
            }
            MissionOffer.Entry entry = offer.get(index);
            Optional<Mission> mission = MissionRows.mission(entry.mission());
            graphics.item(mission.map(Mission::iconStack).orElse(ItemStack.EMPTY), left, y + 2);
            int textWidth = width - 20 - box - 2;
            graphics.text(font, CivGui.fit(font, MissionRows.title(entry.mission(), mission), textWidth), left + 20, y + 1, CivGui.LABEL_COLOR, false);
            if (entry.locked()) {
                // An assigned or locked mission shows who takes it instead of the details (still in the tooltip).
                graphics.text(font, CivGui.fit(font, lockText(entry), textWidth), left + 20, y + 11, CivGui.HINT_COLOR, false);
            } else {
                mission.ifPresent(found -> graphics.text(font, CivGui.fit(font, MissionRows.details(found), textWidth), left + 20, y + 11,
                        CivGui.HINT_COLOR, false));
            }
            MissionRows.checkbox(graphics, font, entry.marked(), index == hovered, left + width - box, y);
        }
        if (hovered >= 0) {
            graphics.setTooltipForNextFrame(font, hovered < offer.size() ? missionTooltip(hovered)
                    : takenTooltip(taken.get(hovered - offer.size())), mouseX, mouseY);
        }
    }

    private static Component takenText(MissionsTabData.TakenMission entry) {
        return Component.translatable(entry.departed() ? "civmod.gui.mission.taken.departed" : "civmod.gui.mission.taken.reserved", entry.settler());
    }

    private List<FormattedCharSequence> missionTooltip(int index) {
        MissionOffer.Entry entry = board().offer().get(index);
        List<FormattedCharSequence> lines = MissionRows.missionLines(font(), entry.mission());
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
        if (autoView) {
            return List.of(Component.translatable("civmod.gui.mission.help.auto"));
        }
        if (assigning >= 0) {
            return List.of(Component.translatable("civmod.gui.mission.help.assign"));
        }
        return List.of(Component.translatable("civmod.gui.mission.help.mark"), Component.translatable("civmod.gui.mission.help.lock"));
    }

    /** "Assigned to <name>", or "Locked" when the assigned settler died or left the board (or is not loaded). */
    private Component lockText(MissionOffer.Entry entry) {
        Optional<String> name = entry.assignee().flatMap(id -> board().settler(id)).map(MissionsTabData.MissionSettler::name);
        if (name.isPresent()) {
            return Component.translatable("civmod.gui.mission.assigned", name.get());
        }
        return Component.translatable(entry.assignee().isPresent() ? "civmod.gui.mission.assigned.unknown" : "civmod.gui.mission.locked");
    }

    /**
     * The settler the automatic pick rule would send on an offer entry, with its chances, or that none would take it.
     */
    private List<FormattedCharSequence> pickerLines(int index) {
        MissionsTabData board = board();
        Optional<Mission> mission = MissionRows.mission(board.offer().get(index).mission());
        if (mission.isEmpty()) {
            return List.of();
        }
        OptionalInt picker = MissionRules.picker(board.offer(), Missions.all(minecraft().level.registryAccess()), index, board.pickers());
        if (picker.isEmpty()) {
            return List.of(Component.translatable("civmod.gui.mission.chances.nobody").withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        MissionsTabData.MissionSettler settler = board.settlers().get(picker.getAsInt());
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.translatable("civmod.gui.mission.chances.picker", settler.name(), settler.skill()).getVisualOrderText());
        chancesLines(chances(mission.get(), settler)).forEach(line -> lines.add(line.getVisualOrderText()));
        return lines;
    }

    /** The chances of a settler of the board on a mission, from the outcome constants the server sent. */
    private MissionRules.Chances chances(Mission mission, MissionsTabData.MissionSettler settler) {
        return MissionRules.chances(board().rules(), mission.recommendedLevel(), settler.skill(), settler.job());
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
        assigningMission = board().offer().get(index).mission();
        settlerOffset = 0;
        screen.rebuild();
    }

    /** Back to the offer. */
    private void showOffer() {
        assigning = -1;
        autoView = false;
        screen.rebuild();
    }

    // ---------------------------------------------------------------- Auto view

    /** Opens the Auto view. */
    private void showAuto() {
        autoView = true;
        auto.reset();
        screen.rebuild();
    }

    /**
     * @return the indices (in the board's settler list) of the settlers whose Mission Job may take the mission being
     *         assigned
     */
    private List<Integer> eligible() {
        MissionsTabData board = board();
        if (assigning < 0 || assigning >= board.offer().size()) {
            return List.of();
        }
        Optional<Mission> mission = MissionRows.mission(board.offer().get(assigning).mission());
        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < board.settlers().size(); i++) {
            MissionsTabData.MissionSettler settler = board.settlers().get(i);
            if (mission.filter(found -> found.jobs().contains(settler.job())).isPresent()) {
                eligible.add(i);
            }
        }
        return eligible;
    }

    /** Asks the server to assign the mission being assigned to a settler, named by its UUID, or to unlock it (empty). */
    private void assign(Optional<UUID> settler) {
        MissionOffer.Entry entry = board().offer().get(assigning);
        ClientPacketDistributor.sendToServer(new MissionsTabData.Assign(menu.containerId, assigning, entry.mission(), settler));
        showOffer();
    }

    /**
     * Draws the assignment view: the settlers of the board whose Mission Job may take the mission (name, chances,
     * Mission Job, skill), the current assignee checked; a click assigns. More than {@value #ASSIGN_ROWS} rows scroll.
     */
    private void extractAssignment(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = font();
        MissionsTabData board = board();
        if (assigning >= board.offer().size()) {
            return;
        }
        MissionOffer.Entry entry = board.offer().get(assigning);
        Optional<Mission> mission = MissionRows.mission(entry.mission());
        Component title = Component.translatable("civmod.gui.mission.assign.title", MissionRows.title(entry.mission(), mission));
        graphics.text(font, CivGui.fit(font, title, screen.panelWidth() - 2 * CivGui.PADDING), CivGui.PADDING, TITLE_Y, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = screen.panelWidth() - 2 * left;
        List<Integer> eligible = eligible();
        if (eligible.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.mission.assign.none"), left,
                    MissionRows.LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        settlerOffset = Math.clamp(settlerOffset, 0, Math.max(0, eligible.size() - ASSIGN_ROWS));
        if (eligible.size() > ASSIGN_ROWS) {
            width -= JobBlockScreen.SCROLL_COLUMN;
            int arrowX = left + width + 1;
            MissionRows.scrollArrows(graphics, font, arrowX, settlerOffset, ASSIGN_ROWS, eligible.size());
        }
        int box = Checkbox.getBoxSize(font);
        int hovered = settlerRowAt(mouseX, mouseY);
        for (int row = 0; row < Math.min(ASSIGN_ROWS, eligible.size() - settlerOffset); row++) {
            int index = settlerOffset + row;
            MissionsTabData.MissionSettler settler = board.settlers().get(eligible.get(index));
            int y = MissionRows.LIST_Y + CivGui.INSET_PADDING + row * JobBlockScreen.ROW_HEIGHT;
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
            MissionRows.checkbox(graphics, font, entry.assignee().filter(settler.id()::equals).isPresent(), index == hovered, left + width - box, y);
        }
        if (hovered >= 0 && mission.isPresent()) {
            MissionsTabData.MissionSettler settler = board.settlers().get(eligible.get(hovered));
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
        return assigning < 0 ? -1 : MissionRows.rowAt(screen, mouseX, mouseY, settlerOffset, ASSIGN_ROWS, eligible().size());
    }

    private List<FormattedCharSequence> takenTooltip(MissionsTabData.TakenMission entry) {
        List<FormattedCharSequence> lines = MissionRows.missionLines(font(), entry.mission());
        lines.add(takenText(entry).copy().withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        return lines;
    }

    /**
     * @return the index of the mission row under the mouse (offer entries first, then taken missions), {@code -1} if none
     */
    private int rowAt(double mouseX, double mouseY) {
        if (assigning >= 0 || autoView) {
            return -1;
        }
        return MissionRows.rowAt(screen, mouseX, mouseY, missionOffset, MISSION_ROWS, board().offer().size() + board().taken().size());
    }

    /**
     * A click on the missions tab: a settler row assigns, a right click on a marked entry opens its assignment view, a
     * left click marks or unmarks an entry.
     *
     * @return whether the click was taken
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        if (autoView) {
            return auto.mouseClicked(event);
        }
        int settler = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? settlerRowAt(event.x(), event.y()) : -1;
        if (settler >= 0) {
            screen.clickSound();
            assign(Optional.of(board().settlers().get(eligible().get(settler)).id()));
            return true;
        }
        int row = rowAt(event.x(), event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && row >= 0 && row < board().offer().size()
                && board().offer().get(row).marked()) {
            screen.clickSound();
            showAssignment(row);
            return true;
        }
        int index = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? row : -1;
        if (index >= 0 && index < board().offer().size()) {
            MissionOffer.Entry entry = board().offer().get(index);
            ClientPacketDistributor.sendToServer(new MissionsTabData.Mark(menu.containerId, index, entry.mission(), !entry.marked()));
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
        if (autoView) {
            auto.mouseScrolled(scrollY);
            return true;
        }
        if (assigning >= 0) {
            settlerOffset = Math.clamp(settlerOffset - (int) Math.signum(scrollY), 0, Math.max(0, eligible().size() - ASSIGN_ROWS));
            return true;
        }
        int offered = board().offer().size() + board().taken().size();
        if (offered > MISSION_ROWS) {
            missionOffset = Math.clamp(missionOffset - (int) Math.signum(scrollY), 0, offered - MISSION_ROWS);
            return true;
        }
        return false;
    }
}
