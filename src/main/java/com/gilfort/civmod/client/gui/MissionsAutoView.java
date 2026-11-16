package com.gilfort.civmod.client.gui;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.MissionsTab;
import com.gilfort.civmod.menu.MissionsTabData;
import com.gilfort.civmod.mission.Mission;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Auto view of the Mission Board's "Missions" tab ({@link MissionsTabView}): every mission the board's unlocked Mission
 * Jobs may take, each with a checkbox; a tick lets the board send a settler on it by itself. Draws the list, finds the row
 * under the mouse, ticks on a click and scrolls.
 */
final class MissionsAutoView {

    /** Mission rows of the Auto view (the Back button takes the place of the last row). */
    static final int ROWS = 4;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final MissionsTab missions;
    private int offset;

    /**
     * @param screen   the screen showing the tab
     * @param missions the tab
     */
    MissionsAutoView(JobBlockScreen screen, MissionsTab missions) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.missions = missions;
    }

    /** The view opens: the list starts at the top. */
    void reset() {
        offset = 0;
    }

    private MissionsTabData board() {
        return missions.data().get();
    }

    /**
     * Draws the view: every mission the board's unlocked Mission Jobs may take (icon, title, details, a checkbox for the
     * tick); a click ticks or unticks it. More than {@value #ROWS} rows scroll.
     */
    void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = screen.getFont();
        MissionsTabData board = board();
        graphics.text(font, Component.translatable("civmod.gui.mission.auto.title"), CivGui.PADDING, MissionsTabView.TITLE_Y, CivGui.LABEL_COLOR, false);
        int left = CivGui.PADDING + CivGui.INSET_PADDING;
        int width = screen.panelWidth() - 2 * left;
        List<Identifier> available = board.available();
        if (available.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable("civmod.gui.mission.auto.none"), left,
                    MissionRows.LIST_Y + CivGui.INSET_PADDING, width, CivGui.HINT_COLOR, false);
            return;
        }
        offset = Math.clamp(offset, 0, Math.max(0, available.size() - ROWS));
        if (available.size() > ROWS) {
            width -= JobBlockScreen.SCROLL_COLUMN;
            MissionRows.scrollArrows(graphics, font, left + width + 1, offset, ROWS, available.size());
        }
        int box = Checkbox.getBoxSize(font);
        int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < Math.min(ROWS, available.size() - offset); row++) {
            int index = offset + row;
            Identifier id = available.get(index);
            Optional<Mission> mission = MissionRows.mission(id);
            int y = MissionRows.LIST_Y + CivGui.INSET_PADDING + row * JobBlockScreen.ROW_HEIGHT;
            int textWidth = width - 20 - box - 2;
            graphics.item(mission.map(Mission::iconStack).orElse(ItemStack.EMPTY), left, y + 2);
            graphics.text(font, CivGui.fit(font, MissionRows.title(id, mission), textWidth), left + 20, y + 1, CivGui.LABEL_COLOR, false);
            mission.ifPresent(found -> graphics.text(font, CivGui.fit(font, MissionRows.details(found), textWidth), left + 20, y + 11,
                    CivGui.HINT_COLOR, false));
            MissionRows.checkbox(graphics, font, board.auto().contains(id), index == hovered, left + width - box, y);
        }
        if (hovered >= 0) {
            List<FormattedCharSequence> lines = MissionRows.missionLines(font, available.get(hovered));
            lines.add(Component.translatable(board.auto().contains(available.get(hovered)) ? "civmod.gui.mission.auto.ticked"
                    : "civmod.gui.mission.auto.unticked").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
            graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    /**
     * @return the index (in the available missions) of the row under the mouse, {@code -1} if none
     */
    private int rowAt(double mouseX, double mouseY) {
        return MissionRows.rowAt(screen, mouseX, mouseY, offset, ROWS, board().available().size());
    }

    /**
     * A left click on a row ticks or unticks its mission.
     *
     * @return whether the click was taken
     */
    boolean mouseClicked(MouseButtonEvent event) {
        int row = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? rowAt(event.x(), event.y()) : -1;
        if (row < 0) {
            return false;
        }
        Identifier mission = board().available().get(row);
        ClientPacketDistributor.sendToServer(new MissionsTabData.AutoMission(menu.containerId, mission, !board().auto().contains(mission)));
        screen.clickSound();
        return true;
    }

    /** Scrolls the list by the wheel. */
    void mouseScrolled(double scrollY) {
        offset = Math.clamp(offset - (int) Math.signum(scrollY), 0, Math.max(0, board().available().size() - ROWS));
    }
}
