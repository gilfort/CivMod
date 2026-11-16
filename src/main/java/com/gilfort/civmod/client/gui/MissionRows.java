package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.Missions;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * What the lists of the Mission Board's tab ({@link MissionsTabView}, {@link MissionsAutoView}) share: the list's place in
 * the panel, the row under the mouse, the scroll arrows, the checkbox of a row and the texts of a mission.
 */
final class MissionRows {

    /** Panel y of the top of the list inset. */
    static final int LIST_Y = 34;
    /** Width the description of a mission wraps to in a tooltip. */
    private static final int TOOLTIP_WIDTH = 200;

    private MissionRows() {
    }

    /**
     * Draws the scroll arrows in the column on the right of a list: the up arrow at the top row, the down arrow at the
     * bottom row; an arrow is dim when there is nothing more to see that way.
     *
     * @param graphics    the graphics extractor
     * @param font        the font
     * @param arrowX      the x position of the arrows (panel coordinates)
     * @param offset      the first visible row
     * @param visibleRows how many rows the list shows
     * @param totalRows   how many rows it holds
     */
    static void scrollArrows(GuiGraphicsExtractor graphics, Font font, int arrowX, int offset, int visibleRows, int totalRows) {
        int top = LIST_Y + CivGui.INSET_PADDING;
        graphics.text(font, Component.literal("▲"), arrowX, top, offset > 0 ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
        graphics.text(font, Component.literal("▼"), arrowX, top + visibleRows * JobBlockScreen.ROW_HEIGHT - font.lineHeight,
                offset + visibleRows < totalRows ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
    }

    /**
     * @param screen      the screen showing the list
     * @param mouseX      the mouse x position (absolute)
     * @param mouseY      the mouse y position (absolute)
     * @param offset      the first visible row
     * @param visibleRows how many rows the list shows
     * @param totalRows   how many rows it holds
     * @return the index of the row under the mouse, {@code -1} if none
     */
    static int rowAt(JobBlockScreen screen, double mouseX, double mouseY, int offset, int visibleRows, int totalRows) {
        int left = screen.left() + CivGui.PADDING + CivGui.INSET_PADDING;
        int top = screen.top() + LIST_Y + CivGui.INSET_PADDING;
        int right = screen.left() + screen.panelWidth() - CivGui.PADDING - CivGui.INSET_PADDING
                - (totalRows > visibleRows ? JobBlockScreen.SCROLL_COLUMN : 0);
        if (mouseX < left || mouseX >= right || mouseY < top) {
            return -1;
        }
        int row = (int) ((mouseY - top) / JobBlockScreen.ROW_HEIGHT);
        int index = offset + row;
        return row < visibleRows && index < totalRows ? index : -1;
    }

    /** Draws a vanilla checkbox at the right end of a list row. */
    static void checkbox(GuiGraphicsExtractor graphics, Font font, boolean selected, boolean hovered, int x, int rowY) {
        int box = Checkbox.getBoxSize(font);
        CivGui.checkbox(graphics, selected, hovered, x, rowY + (JobBlockScreen.ROW_HEIGHT - box) / 2 - 1, box);
    }

    /**
     * @param id a mission id
     * @return the mission, if the client knows it
     */
    static Optional<Mission> mission(Identifier id) {
        return Minecraft.getInstance().level.registryAccess().lookup(Missions.KEY).flatMap(registry -> registry.getOptional(id));
    }

    /**
     * @param id      a mission id
     * @param mission the mission, if known
     * @return the mission title, or its id if the client does not know the mission
     */
    static Component title(Identifier id, Optional<Mission> mission) {
        return mission.map(Mission::title).orElseGet(() -> Component.literal(id.toString()));
    }

    /** The details line of a mission: level, days and its result (loot rolls or Villagers). */
    static Component details(Mission mission) {
        return mission.villagers().map(villagers -> Component.translatable("civmod.gui.mission.details.villagers", mission.recommendedLevel(),
                mission.days(), villagers.count()))
                .orElseGet(() -> Component.translatable("civmod.gui.mission.details", mission.recommendedLevel(), mission.days(), mission.rolls()));
    }

    /** Title, description, recommended level, duration and rolls of a mission, as tooltip lines. */
    static List<FormattedCharSequence> missionLines(Font font, Identifier id) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        Optional<Mission> mission = mission(id);
        lines.add(title(id, mission).getVisualOrderText());
        mission.ifPresent(found -> {
            if (!found.description().getString().isEmpty()) {
                lines.addAll(font.split(found.description().copy().withStyle(ChatFormatting.GRAY), TOOLTIP_WIDTH));
            }
            lines.add(Component.translatable("civmod.gui.mission.recommended_level", found.recommendedLevel()).getVisualOrderText());
            lines.add(Component.translatable("civmod.gui.mission.duration", found.days()).getVisualOrderText());
            lines.add(found.villagers().map(villagers -> Component.translatable("civmod.gui.mission.villagers", villagers.count()))
                    .orElseGet(() -> Component.translatable("civmod.gui.mission.rolls", found.rolls())).getVisualOrderText());
        });
        return lines;
    }
}
