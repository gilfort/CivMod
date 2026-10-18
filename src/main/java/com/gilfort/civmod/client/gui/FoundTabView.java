package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.JobBlockMenu;
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
 * resources as a slot grid. Excluded entries are grey and struck through; a right click excludes or includes one.
 */
final class FoundTabView {

    private static final int TOP = 22;
    private static final int LINE_HEIGHT = 11;
    private static final int COLUMNS = 9;
    private static final int BOTTOM_MARGIN = 6;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;

    /**
     * @param screen the screen showing the tab
     */
    FoundTabView(JobBlockScreen screen) {
        this.screen = screen;
        this.menu = screen.getMenu();
    }

    /** Top edge of the grid (below the info lines). */
    private int gridY(FoundTab tab) {
        return TOP + tab.lines().size() * LINE_HEIGHT + (tab.lines().isEmpty() ? 0 : 2);
    }

    /** How many entries fit the panel. */
    private int visible(FoundTab tab) {
        int rows = Math.max(0, (screen.panelHeight() - BOTTOM_MARGIN - gridY(tab)) / 18);
        return Math.min(tab.entries().size(), rows * COLUMNS);
    }

    /**
     * @return the index of the entry under the mouse, {@code -1} if none
     */
    private int entryAt(FoundTab tab, double mouseX, double mouseY) {
        if (!tab.scanned()) {
            return -1;
        }
        int x = (int) Math.floor(mouseX - screen.left()) - CivGui.PADDING;
        int y = (int) Math.floor(mouseY - screen.top()) - gridY(tab);
        if (x < 0 || y < 0 || x >= COLUMNS * 18 || x % 18 >= 16 || y % 18 >= 16) {
            return -1;
        }
        int index = (y / 18) * COLUMNS + x / 18;
        return index < visible(tab) ? index : -1;
    }

    /**
     * Draws the tab.
     *
     * @param graphics the graphics extractor (panel coordinates)
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     */
    void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = screen.getFont();
        FoundTab tab = menu.getFoundTab();
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
        for (int i = 0; i < visible(tab); i++) {
            FoundTab.Entry entry = tab.entries().get(i);
            int x = CivGui.PADDING + (i % COLUMNS) * 18;
            int y = gridY + (i / COLUMNS) * 18;
            CivGui.slot(graphics, x, y);
            graphics.item(entry.icon(), x, y);
            if (entry.excluded() || entry.needsTool()) {
                CivGui.slotInactive(graphics, x, y);
            }
            if (entry.excluded()) {
                // Struck through: the job does not produce it.
                graphics.fill(x, y + 7, x + 16, y + 9, CivGui.LABEL_COLOR);
            }
            if (i == hovered) {
                CivGui.slotHover(graphics, x, y);
                List<Component> lines = new ArrayList<>(List.of(entry.icon().getHoverName()));
                if (entry.needsTool()) {
                    lines.add(Component.translatable("civmod.gui.found.needs_tool").withStyle(ChatFormatting.RED));
                }
                if (entry.excluded()) {
                    lines.add(Component.translatable("civmod.gui.found.excluded").withStyle(ChatFormatting.GRAY));
                }
                graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            }
        }
    }

    /**
     * A right click on an entry excludes or includes it.
     *
     * @param event the click
     * @return whether the click was taken
     */
    boolean mouseClicked(MouseButtonEvent event) {
        if (menu.getActiveTab() != JobBlockMenu.TAB_FOUND || event.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
            return false;
        }
        FoundTab tab = menu.getFoundTab();
        int index = entryAt(tab, event.x(), event.y());
        if (index < 0) {
            return false;
        }
        ClientPacketDistributor.sendToServer(new FoundTab.Toggle(menu.containerId, tab.entries().get(index).type()));
        screen.clickSound();
        return true;
    }

    /**
     * @return the help lines of the tab
     */
    List<Component> helpLines() {
        return List.of(Component.translatable("civmod.gui.found.help.toggle"));
    }
}
