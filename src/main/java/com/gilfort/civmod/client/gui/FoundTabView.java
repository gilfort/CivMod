package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The "Found" tab of a Resource Collection JobBlock in the {@link JobBlockScreen} (vault
 * {@code Systems/Resource Collection Jobs.md}); the client side of {@link FoundTab}: per Job of the JobBlock's settlers a
 * section (headed by the Job's name when there are several) with its scan info lines, then its found resources as a slot
 * grid. Excluded entries are grey and struck through, entries at their Max at the Home Clerk grey ("Maximum reached at
 * &lt;Clerk&gt;"); a star marks each biome bonus effect acting on an entry (its tooltip names the effects and their
 * files); a right click excludes or includes one for its section's Job. A tab that does not fit the panel scrolls by
 * rows (mouse wheel, or the scrollbar right of the grid, which then has 8 columns).
 */
final class FoundTabView implements JobBlockTabView {

    private static final int TOP = 22;
    private static final int LINE_HEIGHT = 11;
    private static final int SLOT = 18;
    /** Gap between scan info lines and the grid below them. */
    private static final int GRID_GAP = 2;
    private static final int COLUMNS = 9;
    private static final int BOTTOM_MARGIN = 6;
    /** Gap between the grid and its scrollbar. */
    private static final int SCROLLBAR_GAP = 4;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final FoundTab found;
    /** The first row shown. */
    private int rowOffset;
    /** Whether the scrollbar is being dragged. */
    private boolean dragging;

    /**
     * One row of the tab: a text line, or a row of grid slots.
     *
     * @param text    the text of a line, null for a grid row
     * @param section the section the row belongs to
     * @param first   the index of the first entry of a grid row in its section
     * @param count   the entries in a grid row
     * @param height  the row's height, gaps included
     * @param top     the gap above the row's content
     */
    private record Row(@Nullable Component text, int section, int first, int count, int height, int top) {
    }

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    FoundTabView(JobBlockScreen screen, FoundTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.found = tab;
    }

    /** The rows of the tab with that many grid columns. */
    private static List<Row> rows(FoundTabData tab, int columns) {
        List<Row> rows = new ArrayList<>();
        boolean headers = tab.sections().size() > 1;
        for (int i = 0; i < tab.sections().size(); i++) {
            FoundTabData.Section section = tab.sections().get(i);
            if (headers) {
                rows.add(new Row(section.job().displayName(), i, 0, 0, LINE_HEIGHT, 0));
            }
            if (!section.scanned()) {
                rows.add(new Row(Component.translatable("civmod.gui.found.not_scanned"), i, 0, 0, LINE_HEIGHT, 0));
                continue;
            }
            for (Component line : section.lines()) {
                rows.add(new Row(line, i, 0, 0, LINE_HEIGHT, 0));
            }
            if (section.entries().isEmpty()) {
                rows.add(new Row(Component.translatable("civmod.gui.found.nothing"), i, 0, 0, LINE_HEIGHT, 0));
                continue;
            }
            for (int first = 0; first < section.entries().size(); first += columns) {
                int gap = first == 0 && !section.lines().isEmpty() ? GRID_GAP : 0;
                rows.add(new Row(null, i, first, Math.min(columns, section.entries().size() - first), SLOT + gap, gap));
            }
        }
        if (rows.isEmpty()) {
            rows.add(new Row(Component.translatable("civmod.gui.found.not_scanned"), 0, 0, 0, LINE_HEIGHT, 0));
        }
        return rows;
    }

    /** The height the rows may fill. */
    private int available() {
        return Math.max(0, screen.panelHeight() - BOTTOM_MARGIN - TOP);
    }

    private static int height(List<Row> rows, int from) {
        return rows.subList(from, rows.size()).stream().mapToInt(Row::height).sum();
    }

    /** Whether the tab does not fit the panel (then it scrolls). */
    private boolean scrolls(FoundTabData tab) {
        return height(rows(tab, COLUMNS), 0) > available();
    }

    /** Columns of the grid: one fewer while the scrollbar needs the room. */
    private int columns(FoundTabData tab) {
        return scrolls(tab) ? COLUMNS - 1 : COLUMNS;
    }

    /** The rows as laid out now. */
    private List<Row> rows(FoundTabData tab) {
        return rows(tab, columns(tab));
    }

    /** The largest row offset: the first row from which the rest fits. */
    private int maxOffset(List<Row> rows) {
        int offset = rows.size();
        while (offset > 0 && height(rows, offset - 1) <= available()) {
            offset--;
        }
        return Math.min(offset, rows.size() - 1);
    }

    /** The first row shown. */
    private int first(List<Row> rows) {
        rowOffset = Math.clamp(rowOffset, 0, Math.max(0, maxOffset(rows)));
        return rowOffset;
    }

    /** One past the last row shown. */
    private int end(List<Row> rows) {
        int y = 0;
        int index = first(rows);
        while (index < rows.size() && y + rows.get(index).height() <= available()) {
            y += rows.get(index).height();
            index++;
        }
        return index;
    }

    /** Left edge of the scrollbar (panel coordinates). */
    private int scrollbarX(FoundTabData tab) {
        return CivGui.PADDING + columns(tab) * SLOT + SCROLLBAR_GAP;
    }

    /**
     * A shown entry.
     *
     * @param section the section index
     * @param index   the entry index in its section
     */
    private record Hit(int section, int index) {
    }

    /**
     * @return the entry under the mouse, null if none
     */
    private @Nullable Hit entryAt(FoundTabData tab, double mouseX, double mouseY) {
        List<Row> rows = rows(tab);
        int x = (int) Math.floor(mouseX - screen.left()) - CivGui.PADDING;
        int y = (int) Math.floor(mouseY - screen.top()) - TOP;
        if (x < 0 || y < 0 || x % SLOT >= 16) {
            return null;
        }
        int rowY = 0;
        for (int i = first(rows); i < end(rows); i++) {
            Row row = rows.get(i);
            int slotY = rowY + row.top();
            if (row.text() == null && y >= slotY && y < slotY + 16 && x / SLOT < row.count()) {
                return new Hit(row.section(), row.first() + x / SLOT);
            }
            rowY += row.height();
        }
        return null;
    }

    /** Draws the tab. */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        FoundTabData tab = found.data().get();
        int width = screen.panelWidth() - 2 * CivGui.PADDING;
        List<Row> rows = rows(tab);
        Hit hovered = entryAt(tab, mouseX, mouseY);
        if (scrolls(tab)) {
            CivGui.scrollbar(graphics, scrollbarX(tab), TOP - 1, available(), rowOffset, maxOffset(rows));
        }
        int y = TOP;
        for (int i = first(rows); i < end(rows); i++) {
            Row row = rows.get(i);
            if (row.text() != null) {
                graphics.text(font, CivGui.fit(font, row.text(), width), CivGui.PADDING, y, CivGui.LABEL_COLOR, false);
            } else {
                List<FoundTabData.Entry> entries = tab.sections().get(row.section()).entries();
                for (int column = 0; column < row.count(); column++) {
                    int index = row.first() + column;
                    slot(graphics, font, entries.get(index), CivGui.PADDING + column * SLOT, y + row.top(),
                            hovered != null && hovered.equals(new Hit(row.section(), index)), mouseX, mouseY);
                }
            }
            y += row.height();
        }
    }

    /** Draws one entry; a hovered one with its tooltip. */
    private void slot(GuiGraphicsExtractor graphics, Font font, FoundTabData.Entry entry, int x, int y, boolean hovered, int mouseX, int mouseY) {
        CivGui.slot(graphics, x, y);
        graphics.item(entry.icon(), x, y);
        if (entry.excluded() || entry.needsTool() || entry.maxAt().isPresent()) {
            CivGui.slotInactive(graphics, x, y);
        }
        if (entry.excluded()) {
            // Struck through: the job does not produce it.
            graphics.fill(x, y + 7, x + 16, y + 9, CivGui.LABEL_COLOR);
        }
        // A star per biome bonus effect acting on the drop.
        CivGui.bonusStars(graphics, font, x, y, (entry.rareBonus() ? 1 : 0) + (entry.doubleBonus() ? 1 : 0));
        if (!hovered) {
            return;
        }
        CivGui.slotHover(graphics, x, y);
        List<Component> lines = new ArrayList<>(List.of(entry.icon().getHoverName()));
        if (entry.needsTool()) {
            lines.add(Component.translatable("civmod.gui.found.needs_tool").withStyle(ChatFormatting.RED));
        }
        if (entry.excluded()) {
            lines.add(Component.translatable("civmod.gui.found.excluded").withStyle(ChatFormatting.GRAY));
        }
        entry.maxAt().ifPresent(clerk -> lines.add(Component.translatable("civmod.gui.found.max_reached", clerk).withStyle(ChatFormatting.GOLD)));
        entry.bonusLines().forEach(line -> lines.add(Component.empty().append(CivGui.STAR).append(" ").append(line).withColor(CivGui.STAR_COLOR)));
        graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    /** A right click on an entry excludes or includes it for its section's Job; a left click on the scrollbar drags it. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        FoundTabData tab = found.data().get();
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overScrollbar(tab, event.x(), event.y())) {
            dragging = true;
            dragTo(tab, event.y());
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
            return false;
        }
        Hit hit = entryAt(tab, event.x(), event.y());
        if (hit == null) {
            return false;
        }
        FoundTabData.Section section = tab.sections().get(hit.section());
        ClientPacketDistributor.sendToServer(new FoundTabData.Toggle(menu.containerId, section.job(), section.entries().get(hit.index()).type()));
        screen.clickSound();
        return true;
    }

    /** Scrolls the tab by one row per wheel step. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        FoundTabData tab = found.data().get();
        if (!scrolls(tab)) {
            return false;
        }
        rowOffset = Math.clamp(rowOffset - (int) Math.signum(scrollY), 0, maxOffset(rows(tab)));
        return true;
    }

    /**
     * Drags the scrollbar.
     *
     * @param event the drag
     * @return whether the scrollbar is being dragged
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event) {
        if (!dragging) {
            return false;
        }
        dragTo(found.data().get(), event.y());
        return true;
    }

    /**
     * Ends dragging the scrollbar.
     *
     * @return whether it was being dragged
     */
    @Override
    public boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    private boolean overScrollbar(FoundTabData tab, double mouseX, double mouseY) {
        if (!scrolls(tab)) {
            return false;
        }
        double x = mouseX - screen.left() - scrollbarX(tab);
        double y = mouseY - screen.top() - (TOP - 1);
        return x >= 0 && x < CivGui.SCROLLBAR_WIDTH && y >= 0 && y < available();
    }

    /** Moves the tab so the scroller's centre follows the mouse. */
    private void dragTo(FoundTabData tab, double mouseY) {
        int height = available();
        int track = height - CivGui.scrollerHeight(height);
        if (track <= 0) {
            return;
        }
        int maxOffset = maxOffset(rows(tab));
        double y = mouseY - screen.top() - (TOP - 1) - CivGui.scrollerHeight(height) / 2.0;
        rowOffset = Math.clamp((int) Math.round(y / track * maxOffset), 0, maxOffset);
    }

    /**
     * @return the help lines of the tab
     */
    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        return List.of(Component.translatable("civmod.gui.found.help.toggle"));
    }
}
