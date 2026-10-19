package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StationTypeTab;
import com.gilfort.civmod.settlement.StationTypes;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Station Types" tab of a JobBlock in the {@link JobBlockScreen} (vault {@code Systems/JobBlock.md}); the client side
 * of {@link StationTypeTab}. Five slots in a row: the Base Job's station (locked), then the unlock items, one more open
 * slot per JobBlock level; below them the Jobs the JobBlock offers. The "?" lists every unlock item with its Jobs.
 */
final class StationTypesView {

    /** The 5 slots, centred in the panel. */
    private static final int SLOTS_X = 44;
    private static final int SLOTS_Y = 30;
    private static final int LINES_Y = 56;
    private static final int LINE_HEIGHT = 11;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    /** The state line after a refused item ("Unlocks nothing here"), until the next click. */
    private Optional<Component> notice = Optional.empty();

    /**
     * @param screen the screen showing the tab
     */
    StationTypesView(JobBlockScreen screen) {
        this.screen = screen;
        this.menu = screen.getMenu();
    }

    private static int slotX(int slot) {
        return SLOTS_X + slot * 18;
    }

    /** The slot under the mouse, {@code -1} if none. */
    private int slotAt(double mouseX, double mouseY) {
        if (menu.getActiveTab() != JobBlockMenu.TAB_STATION_TYPES) {
            return -1;
        }
        int x = (int) Math.floor(mouseX - screen.left());
        int y = (int) Math.floor(mouseY - screen.top());
        for (int slot = 0; slot < StationTypes.SLOTS; slot++) {
            if (x >= slotX(slot) && x < slotX(slot) + 16 && y >= SLOTS_Y && y < SLOTS_Y + 16) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * A click with an item on an open slot puts one of it in (the server takes it if it unlocks something new).
     *
     * @return whether the click was taken
     */
    boolean mouseClicked(MouseButtonEvent event) {
        int slot = slotAt(event.x(), event.y());
        if (slot < 0) {
            return false;
        }
        StationTypeTab tab = menu.getStationTypeTab();
        Item item = menu.getCarried().getItem();
        notice = Optional.empty();
        if (menu.getCarried().isEmpty() || slot == 0 || slot >= tab.open()) {
            return true;
        }
        if (tab.jobsOf(item).isEmpty()) {
            notice = Optional.of(Component.translatable("civmod.gui.station_types.unlocks_nothing", new ItemStack(item).getHoverName()));
        } else if (tab.slots().contains(item)) {
            notice = Optional.of(Component.translatable("civmod.gui.station_types.already", new ItemStack(item).getHoverName()));
        } else {
            screen.clickMenuButton(JobBlockMenu.stationTypeButtonId(slot));
            screen.clickSound();
        }
        return true;
    }

    /**
     * @return the help lines: how slots work, then one line per unlock item and Job ("Stonecutter: Stonecutter – …")
     */
    List<Component> helpLines() {
        List<Component> lines = new ArrayList<>(List.of(Component.translatable("civmod.gui.station_types.help.put"),
                Component.translatable("civmod.gui.station_types.help.replace"), Component.translatable("civmod.gui.station_types.help.station")));
        for (StationTypes.Unlock unlock : menu.getStationTypeTab().overview()) {
            Component item = new ItemStack(unlock.item()).getHoverName();
            for (Job job : unlock.jobs()) {
                Component description = job.description();
                lines.add(description.getString().isEmpty() ? Component.translatable("civmod.gui.station_types.unlock", item, job.displayName())
                        : Component.translatable("civmod.gui.station_types.unlock_described", item, job.displayName(), description));
            }
        }
        return lines;
    }

    /** Draws the slots, the offered Jobs and the state line. */
    void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = screen.getFont();
        StationTypeTab tab = menu.getStationTypeTab();
        graphics.text(font, Component.translatable("civmod.gui.station_types.title"), CivGui.PADDING, 18, CivGui.LABEL_COLOR, false);
        int hovered = slotAt(mouseX, mouseY);
        for (int slot = 0; slot < StationTypes.SLOTS; slot++) {
            int x = slotX(slot);
            CivGui.slot(graphics, x, SLOTS_Y);
            Item item = tab.item(slot);
            if (item != Items.AIR) {
                graphics.item(new ItemStack(item), x, SLOTS_Y);
            }
            if (slot == 0 || slot >= tab.open()) {
                // The Base Job's station is fixed; closed slots open with the JobBlock level.
                CivGui.slotInactive(graphics, x, SLOTS_Y);
            }
            if (slot == hovered) {
                CivGui.slotHover(graphics, x, SLOTS_Y);
            }
        }
        int y = LINES_Y;
        int width = screen.panelWidth() - 2 * CivGui.PADDING;
        Component jobs = ComponentUtils.formatList(tab.unlocked(), Job::displayName);
        graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.station_types.offered", jobs), width), CivGui.PADDING, y,
                CivGui.LABEL_COLOR, false);
        notice.ifPresent(line -> graphics.text(font, CivGui.fit(font, line, width), CivGui.PADDING, y + LINE_HEIGHT, CivGui.ERROR_COLOR, false));
        if (hovered >= 0) {
            graphics.setComponentTooltipForNextFrame(font, tooltip(tab, hovered), mouseX, mouseY);
        }
    }

    private static List<Component> tooltip(StationTypeTab tab, int slot) {
        List<Component> lines = new ArrayList<>();
        Item item = tab.item(slot);
        if (slot >= tab.open()) {
            lines.add(Component.translatable("civmod.gui.station_types.closed", slot + 1).withStyle(ChatFormatting.GRAY));
            return lines;
        }
        if (item == Items.AIR) {
            lines.add(Component.translatable("civmod.gui.station_types.empty"));
            return lines;
        }
        lines.add(new ItemStack(item).getHoverName());
        List<Job> jobs = slot == 0 ? tab.unlocked().stream().limit(1).toList() : tab.jobsOf(item);
        lines.add(Component.translatable(slot == 0 ? "civmod.gui.station_types.base" : "civmod.gui.station_types.unlocks",
                ComponentUtils.formatList(jobs, Job::displayName)).withStyle(ChatFormatting.GRAY));
        return lines;
    }
}
