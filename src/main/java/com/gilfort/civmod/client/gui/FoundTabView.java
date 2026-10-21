package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;

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
 * {@code Systems/Resource Collection Jobs.md}); the client side of {@link FoundTab}: scan info lines, then the found
 * resources as a slot grid. Excluded entries are grey and struck through, entries at their Max at the Home Clerk grey
 * ("Maximum reached at &lt;Clerk&gt;"); a star marks each biome bonus effect acting on an entry (its tooltip names the
 * effects and their files); a right click excludes or includes one. A list that does not fit the panel
 * scrolls by rows (mouse wheel, or the scrollbar right of the grid, which then has 8 columns).
 */
final class FoundTabView implements JobBlockTabView {

    private static final int TOP = 22;
    private static final int LINE_HEIGHT = 11;
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
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    FoundTabView(JobBlockScreen screen, FoundTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.found = tab;
    }

    /** Top edge of the grid (below the info lines). */
    private int gridY(FoundTabData tab) {
        return TOP + tab.lines().size() * LINE_HEIGHT + (tab.lines().isEmpty() ? 0 : 2);
    }

    /** How many rows fit the panel. */
    private int rows(FoundTabData tab) {
        return Math.max(0, (screen.panelHeight() - BOTTOM_MARGIN - gridY(tab)) / 18);
    }

    /** Whether the list does not fit the panel (then it scrolls). */
    private boolean scrolls(FoundTabData tab) {
        return tab.entries().size() > rows(tab) * COLUMNS;
    }

    /** Columns of the grid: one fewer while the scrollbar needs the room. */
    private int columns(FoundTabData tab) {
        return scrolls(tab) ? COLUMNS - 1 : COLUMNS;
    }

    /** The largest row offset. */
    private int maxOffset(FoundTabData tab) {
        int columns = columns(tab);
        int total = (tab.entries().size() + columns - 1) / columns;
        return Math.max(0, total - rows(tab));
    }

    /** The first entry shown. */
    private int first(FoundTabData tab) {
        rowOffset = Math.clamp(rowOffset, 0, maxOffset(tab));
        return rowOffset * columns(tab);
    }

    /** One past the last entry shown. */
    private int end(FoundTabData tab) {
        return Math.min(tab.entries().size(), first(tab) + rows(tab) * columns(tab));
    }

    /** Left edge of the scrollbar (panel coordinates). */
    private int scrollbarX(FoundTabData tab) {
        return CivGui.PADDING + columns(tab) * 18 + SCROLLBAR_GAP;
    }

    /**
     * @return the index of the entry under the mouse, {@code -1} if none
     */
    private int entryAt(FoundTabData tab, double mouseX, double mouseY) {
        if (!tab.scanned()) {
            return -1;
        }
        int columns = columns(tab);
        int x = (int) Math.floor(mouseX - screen.left()) - CivGui.PADDING;
        int y = (int) Math.floor(mouseY - screen.top()) - gridY(tab);
        if (x < 0 || y < 0 || x >= columns * 18 || x % 18 >= 16 || y % 18 >= 16) {
            return -1;
        }
        int index = first(tab) + (y / 18) * columns + x / 18;
        return index < end(tab) ? index : -1;
    }

    /** Draws the tab. */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        FoundTabData tab = found.data().get();
        int width = screen.panelWidth() - 2 * CivGui.PADDING;
        if (!tab.scanned()) {
            graphics.text(font, Component.translatable("civmod.gui.found.not_scanned"), CivGui.PADDING, TOP, CivGui.LABEL_COLOR, false);
            return;
        }
        for (int i = 0; i < tab.lines().size(); i++) {
            graphics.text(font, CivGui.fit(font, tab.lines().get(i), width), CivGui.PADDING, TOP + i * LINE_HEIGHT, CivGui.LABEL_COLOR, false);
        }
        int gridY = gridY(tab);
        if (tab.entries().isEmpty()) {
            graphics.text(font, Component.translatable("civmod.gui.found.nothing"), CivGui.PADDING, gridY, CivGui.LABEL_COLOR, false);
            return;
        }
        int hovered = entryAt(tab, mouseX, mouseY);
        int columns = columns(tab);
        int first = first(tab);
        if (scrolls(tab)) {
            CivGui.scrollbar(graphics, scrollbarX(tab), gridY - 1, rows(tab) * 18, rowOffset, maxOffset(tab));
        }
        for (int i = first; i < end(tab); i++) {
            FoundTabData.Entry entry = tab.entries().get(i);
            int x = CivGui.PADDING + ((i - first) % columns) * 18;
            int y = gridY + ((i - first) / columns) * 18;
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
            if (i == hovered) {
                CivGui.slotHover(graphics, x, y);
                List<Component> lines = new ArrayList<>(List.of(entry.icon().getHoverName()));
                if (entry.needsTool()) {
                    lines.add(Component.translatable("civmod.gui.found.needs_tool").withStyle(ChatFormatting.RED));
                }
                if (entry.excluded()) {
                    lines.add(Component.translatable("civmod.gui.found.excluded").withStyle(ChatFormatting.GRAY));
                }
                entry.maxAt().ifPresent(clerk -> lines.add(Component.translatable("civmod.gui.found.max_reached", clerk).withStyle(ChatFormatting.GOLD)));
                entry.bonusLines().forEach(line -> lines.add(Component.literal("★ ").append(line).withStyle(ChatFormatting.YELLOW)));
                graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            }
        }
    }

    /** A right click on an entry excludes or includes it; a left click on the scrollbar starts dragging it. */
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
        int index = entryAt(tab, event.x(), event.y());
        if (index < 0) {
            return false;
        }
        ClientPacketDistributor.sendToServer(new FoundTabData.Toggle(menu.containerId, tab.entries().get(index).type()));
        screen.clickSound();
        return true;
    }

    /** Scrolls the list by one row per wheel step. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        FoundTabData tab = found.data().get();
        if (!tab.scanned() || !scrolls(tab)) {
            return false;
        }
        rowOffset = Math.clamp(rowOffset - (int) Math.signum(scrollY), 0, maxOffset(tab));
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
        if (!tab.scanned() || !scrolls(tab)) {
            return false;
        }
        double x = mouseX - screen.left() - scrollbarX(tab);
        double y = mouseY - screen.top() - (gridY(tab) - 1);
        return x >= 0 && x < CivGui.SCROLLBAR_WIDTH && y >= 0 && y < rows(tab) * 18;
    }

    /** Moves the list so the scroller's centre follows the mouse. */
    private void dragTo(FoundTabData tab, double mouseY) {
        int height = rows(tab) * 18;
        int track = height - CivGui.scrollerHeight(height);
        if (track <= 0) {
            return;
        }
        double y = mouseY - screen.top() - (gridY(tab) - 1) - CivGui.scrollerHeight(height) / 2.0;
        rowOffset = Math.clamp((int) Math.round(y / track * maxOffset(tab)), 0, maxOffset(tab));
    }

    /**
     * @return the help lines of the tab
     */
    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        return List.of(Component.translatable("civmod.gui.found.help.toggle"));
    }
}
