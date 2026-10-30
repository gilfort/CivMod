package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.client.SettlerRenderer;
import com.gilfort.civmod.job.LumberjackProfile;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.WorkersTab;
import com.gilfort.civmod.settlement.JobPlaces;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Workers" tab of the {@link JobBlockScreen} (vault {@code Systems/GUI.md}, "Info becomes Workers"); the client
 * side of {@link WorkersTab}: per offered Job a line "Deliveryman 2/4" (the first one with today's biome, its effects on
 * hover) and its automatic assignment button on the right (coloured on, grey off); below it one row per settler with
 * head, name, "Lv 23" and ✕. A click on head or name opens the settler window; a settler on a mission is greyed with
 * "Away". The list scrolls (mouse wheel, scrollbar). Below it other tabs' views add their lines and the toggle row
 * ({@link JobBlockTabView#extractWorkers}, {@link JobBlockTabView#initWorkers}).
 */
final class WorkersTabView implements JobBlockTabView {

    /** Top of the list (below the header line). */
    private static final int TOP = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int LINE_HEIGHT = 11;
    /** Size of the automatic assignment icon and the ✕ button. */
    private static final int ICON = 16;
    /** Size of a settler's head. */
    private static final int HEAD = 12;
    /** Indent of the settler rows below their Job line. */
    private static final int INDENT = 4;
    private static final int BOTTOM_MARGIN = 6;
    /** Height of the toggle row (button and gap above it). */
    private static final int TOGGLE_HEIGHT = 24;
    /** Gap between the list and its scrollbar. */
    private static final int SCROLLBAR_GAP = 4;
    /** Colour of a greyed head (settler away or not loaded). */
    private static final int GREY_HEAD = 0xFF808080;
    /** The automatic assignment icon. */
    private static final ItemStack AUTO_ICON = new ItemStack(Items.REDSTONE);

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final WorkersTab tab;
    /** The first row shown. */
    private int offset;
    /** Whether the scrollbar is being dragged. */
    private boolean dragging;

    /**
     * A row of the list.
     *
     * @param line   the index of its Job line ({@link JobBlockInfo#places()}), -1 for a worker without one
     * @param worker the index of its worker ({@link JobBlockInfo#workers()}), -1 for the Job line itself
     */
    private record Row(int line, int worker) {
    }

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    WorkersTabView(JobBlockScreen screen, WorkersTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.tab = tab;
    }

    /**
     * Adds a toggle button of the Workers tab: across the panel in its bottom row; it presses a menu button on client and
     * server and rebuilds the widgets.
     *
     * @param screen   the screen
     * @param workers  the Workers tab's index
     * @param label    the label
     * @param buttonId the menu button id
     */
    static void button(JobBlockScreen screen, int workers, Component label, int buttonId) {
        screen.addWidget(workers, Button.builder(label, pressed -> {
            screen.clickMenuButton(buttonId);
            screen.rebuild();
        }).bounds(screen.left() + CivGui.PADDING, screen.top() + screen.panelHeight() - BOTTOM_MARGIN - 20, screen.panelWidth() - 2 * CivGui.PADDING, 20)
                .build());
    }

    // ---------------------------------------------------------------- layout

    /** Whether the Lumberjack's leaf toggle is shown. */
    private boolean leavesToggle() {
        JobBlockInfo info = menu.getInfo();
        return info.jobType().profile(LumberjackProfile.class).isPresent() && info.inSettlement();
    }

    /** Whether the bottom row holds a toggle button. */
    private boolean toggleRow() {
        return leavesToggle() || screen.views().stream().anyMatch(JobBlockTabView::workersToggle);
    }

    /** The lines other views draw below the list. */
    private int footerLines() {
        return screen.views().stream().mapToInt(JobBlockTabView::workersLines).sum();
    }

    /** The rows: every Job line, then its workers; workers whose Job has no line last. */
    private static List<Row> rows(JobBlockInfo info) {
        List<Row> rows = new ArrayList<>();
        List<JobBlockInfo.Worker> workers = info.workers();
        boolean[] listed = new boolean[workers.size()];
        for (int line = 0; line < info.places().size(); line++) {
            rows.add(new Row(line, -1));
            for (int worker = 0; worker < workers.size(); worker++) {
                if (workers.get(worker).assignee().job() == info.places().get(line).job()) {
                    rows.add(new Row(line, worker));
                    listed[worker] = true;
                }
            }
        }
        for (int worker = 0; worker < workers.size(); worker++) {
            if (!listed[worker]) {
                rows.add(new Row(-1, worker));
            }
        }
        return rows;
    }

    /** How many rows fit between the header and the lines and buttons below the list. */
    private int visible() {
        int bottom = screen.panelHeight() - BOTTOM_MARGIN - (toggleRow() ? TOGGLE_HEIGHT : 0) - footerLines() * LINE_HEIGHT;
        return Math.max(1, (bottom - TOP) / ROW_HEIGHT);
    }

    private int maxOffset(List<Row> rows) {
        return Math.max(0, rows.size() - visible());
    }

    /** The first row shown, kept inside the list. */
    private int first(List<Row> rows) {
        offset = Math.clamp(offset, 0, maxOffset(rows));
        return offset;
    }

    private boolean scrolls(List<Row> rows) {
        return rows.size() > visible();
    }

    /** Right edge of the rows' content (panel coordinates): left of the scrollbar while the list scrolls. */
    private int right(List<Row> rows) {
        int right = screen.panelWidth() - CivGui.PADDING;
        return scrolls(rows) ? right - CivGui.SCROLLBAR_WIDTH - SCROLLBAR_GAP : right;
    }

    /** Top of the row shown at position {@code shown} (panel coordinates). */
    private static int rowY(int shown) {
        return TOP + shown * ROW_HEIGHT;
    }

    // ---------------------------------------------------------------- widgets

    /** The ✕ buttons of the shown settler rows, the toggle row and the other views' toggles. */
    @Override
    public void init() {
        JobBlockInfo info = menu.getInfo();
        List<Row> rows = rows(info);
        int first = first(rows);
        int end = Math.min(rows.size(), first + visible());
        int x = screen.left() + right(rows) - ICON;
        for (int i = first; i < end; i++) {
            Row row = rows.get(i);
            if (row.worker() < 0) {
                continue;
            }
            int id = tab.unassignButtonId(row.worker());
            Button remove = screen.addWidget(tab.index(), Button.builder(Component.literal("✕"), pressed -> {
                screen.clickMenuButton(id);
                screen.rebuild();
            }).bounds(x, screen.top() + rowY(i - first) + 1, ICON, ICON).build());
            remove.setTooltip(Tooltip.create(Component.translatable("civmod.gui.job_block.unassign")));
        }
        if (leavesToggle()) {
            button(screen, tab.index(), Component.translatable(info.collectLeaves() ? "civmod.gui.job_block.leaves.on" : "civmod.gui.job_block.leaves.off"),
                    tab.buttonId(WorkersTab.TOGGLE_LEAVES));
        }
        screen.views().forEach(view -> view.initWorkers(tab.index()));
    }

    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        JobBlockInfo info = menu.getInfo();
        List<Component> lines = new ArrayList<>();
        if (!info.workers().isEmpty()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.open_settler"));
        }
        if (info.inSettlement() && !info.connected()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.connect"));
        }
        if (leavesToggle()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.leaves"));
        }
        // The biomes that change something for the Job, and that all others do not.
        lines.addAll(info.biome().overview());
        return lines;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        JobBlockInfo info = menu.getInfo();
        int color = info.inSettlement() && info.connected() ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR;
        List<Row> rows = rows(info);
        int first = first(rows);
        int end = Math.min(rows.size(), first + visible());
        int right = right(rows);
        if (scrolls(rows)) {
            CivGui.scrollbar(graphics, screen.panelWidth() - CivGui.PADDING - CivGui.SCROLLBAR_WIDTH, TOP, visible() * ROW_HEIGHT, offset, maxOffset(rows));
        }
        for (int i = first; i < end; i++) {
            Row row = rows.get(i);
            int y = rowY(i - first);
            if (row.worker() < 0) {
                jobLine(graphics, font, info, row.line(), y, right, color, mouseX, mouseY);
            } else {
                workerRow(graphics, font, info.workers().get(row.worker()), y, right, color, mouseX, mouseY);
            }
        }
        int y = screen.panelHeight() - BOTTOM_MARGIN - (toggleRow() ? TOGGLE_HEIGHT : 0) - footerLines() * LINE_HEIGHT + 2;
        for (JobBlockTabView view : screen.views()) {
            y = view.extractWorkers(graphics, y, screen.panelWidth() - 2 * CivGui.PADDING, color, mouseX, mouseY);
        }
    }

    /**
     * A Job line: "Deliveryman 2/4", the first one with today's biome ("· Jungle ★", its effects on hover); in a
     * settlement the automatic assignment icon on the right.
     */
    private void jobLine(GuiGraphicsExtractor graphics, Font font, JobBlockInfo info, int index, int y, int right, int color, int mouseX, int mouseY) {
        JobPlaces.Line line = info.places().get(index);
        MutableComponent text = JobPickerScreen.label(line).copy();
        JobBlockInfo.BiomeInfo biome = info.biome();
        boolean withBiome = index == 0 && biome.biome().isPresent();
        if (withBiome) {
            text.append(" · ").append(biome.biome().get());
            if (!biome.effects().isEmpty()) {
                text.append(" ").append(CivGui.STAR.copy().withColor(CivGui.STAR_COLOR & 0xFFFFFF));
            }
        }
        int width = right - CivGui.PADDING - (info.inSettlement() ? ICON + 2 : 0);
        Component shown = CivGui.fit(font, text, width);
        int textY = y + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
        graphics.text(font, shown, CivGui.PADDING, textY, color, false);
        if (withBiome && over(CivGui.PADDING, textY, Math.min(width, font.width(shown)), font.lineHeight, mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(biome.biome().get());
            lines.addAll(biome.effects());
            graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
        if (!info.inSettlement()) {
            return;
        }
        int x = right - ICON;
        graphics.item(AUTO_ICON, x, y + 1);
        if (!line.auto()) {
            CivGui.slotInactive(graphics, x, y + 1);
        }
        if (over(x, y + 1, ICON, ICON, mouseX, mouseY)) {
            CivGui.slotHover(graphics, x, y + 1);
            graphics.setComponentTooltipForNextFrame(font, List.of(Component.translatable(line.auto()
                    ? "civmod.gui.job_block.auto_assign.on" : "civmod.gui.job_block.auto_assign.off")), mouseX, mouseY);
        }
    }

    /** A settler row: head, name and "Lv 23" (a settler away: greyed, "Away", the mission on hover). */
    private void workerRow(GuiGraphicsExtractor graphics, Font font, JobBlockInfo.Worker worker, int y, int right, int color, int mouseX, int mouseY) {
        int x = CivGui.PADDING + INDENT;
        boolean grey = !worker.opensWindow();
        PlayerFaceExtractor.extractRenderState(graphics, SettlerRenderer.skin(Math.max(0, worker.skin())), x, y + (ROW_HEIGHT - HEAD) / 2, HEAD,
                grey ? GREY_HEAD : 0xFFFFFFFF);
        int textY = y + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
        int levelRight = right - ICON - 4;
        Component level = worker.away().isPresent() ? Component.translatable("civmod.gui.job_block.worker.away")
                : worker.loaded() ? Component.translatable("civmod.gui.settler.level_short", worker.level()) : Component.empty();
        int levelX = levelRight - font.width(level);
        graphics.text(font, level, levelX, textY, worker.away().isPresent() ? CivGui.HINT_COLOR : color, false);
        int nameX = x + HEAD + 4;
        Component name = CivGui.fit(font, Component.literal(worker.assignee().name()), levelX - 4 - nameX);
        graphics.text(font, name, nameX, textY, grey ? CivGui.HINT_COLOR : color, false);
        worker.away().ifPresent(away -> {
            if (over(x, y, levelRight - x, ROW_HEIGHT, mouseX, mouseY)) {
                Component mission = MissionsTabView.title(away.mission(), MissionsTabView.mission(away.mission()));
                graphics.setComponentTooltipForNextFrame(font, List.of(Component.translatable("civmod.gui.job_block.worker.away_tooltip", mission,
                        away.daysLeft())), mouseX, mouseY);
            }
        });
    }

    /** Whether the mouse is over an area given in panel coordinates. */
    private boolean over(int x, int y, int width, int height, double mouseX, double mouseY) {
        double relX = mouseX - screen.left();
        double relY = mouseY - screen.top();
        return relX >= x && relX < x + width && relY >= y && relY < y + height;
    }

    // ---------------------------------------------------------------- input

    /**
     * A left click on an automatic assignment icon sets the wanted state, on a settler's head or name opens its window
     * (loaded settlers at home only); a click on the scrollbar drags it.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        JobBlockInfo info = menu.getInfo();
        List<Row> rows = rows(info);
        if (overScrollbar(rows, event.x(), event.y())) {
            dragging = true;
            dragTo(rows, event.y());
            return true;
        }
        int first = first(rows);
        int end = Math.min(rows.size(), first + visible());
        int right = right(rows);
        for (int i = first; i < end; i++) {
            Row row = rows.get(i);
            int y = rowY(i - first);
            if (row.worker() < 0) {
                if (info.inSettlement() && over(right - ICON, y + 1, ICON, ICON, event.x(), event.y())) {
                    screen.clickMenuButton(tab.autoButtonId(row.line(), !info.places().get(row.line()).auto()));
                    screen.clickSound();
                    return true;
                }
            } else if (info.workers().get(row.worker()).opensWindow()
                    && over(CivGui.PADDING + INDENT, y, right - ICON - 4 - CivGui.PADDING - INDENT, ROW_HEIGHT, event.x(), event.y())) {
                screen.clickMenuButton(tab.openButtonId(row.worker()));
                screen.clickSound();
                return true;
            }
        }
        return false;
    }

    /** Scrolls the list by one row per wheel step. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        List<Row> rows = rows(menu.getInfo());
        if (!scrolls(rows)) {
            return false;
        }
        scrollTo(rows, offset - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event) {
        if (!dragging) {
            return false;
        }
        dragTo(rows(menu.getInfo()), event.y());
        return true;
    }

    @Override
    public boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    private boolean overScrollbar(List<Row> rows, double mouseX, double mouseY) {
        return scrolls(rows) && over(screen.panelWidth() - CivGui.PADDING - CivGui.SCROLLBAR_WIDTH, TOP, CivGui.SCROLLBAR_WIDTH, visible() * ROW_HEIGHT,
                mouseX, mouseY);
    }

    /** Moves the list so the scroller's centre follows the mouse. */
    private void dragTo(List<Row> rows, double mouseY) {
        int height = visible() * ROW_HEIGHT;
        int track = height - CivGui.scrollerHeight(height);
        if (track <= 0) {
            return;
        }
        double y = mouseY - screen.top() - TOP - CivGui.scrollerHeight(height) / 2.0;
        scrollTo(rows, (int) Math.round(y / track * maxOffset(rows)));
    }

    /** Shows the list from that row; the ✕ buttons move with it. */
    private void scrollTo(List<Row> rows, int row) {
        int target = Math.clamp(row, 0, maxOffset(rows));
        if (target != offset) {
            offset = target;
            screen.rebuild();
        }
    }
}
