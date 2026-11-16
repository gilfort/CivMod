package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobNeeds;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.menu.StationTypesTab;
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
 * of {@link StationTypesTab}. Five slots in a row for the unlock items, one open slot per JobBlock level; below them the
 * Jobs the JobBlock offers (the Base Job first). The "?" lists every unlock item with its Jobs.
 */
final class StationTypesTabView implements JobBlockTabView {

    /** The 5 slots, centred in the panel. */
    private static final int SLOTS_X = 44;
    private static final int SLOTS_Y = 30;
    private static final int LINES_Y = 56;
    private static final int LINE_HEIGHT = 11;
    /** The job blocks: the window under the offered-Jobs line, down to the label of the inventory. */
    private static final int BLOCKS_TOP = 80;
    private static final int BLOCKS_BOTTOM = 166;
    /** A block line: the tick, the icon and the text. */
    private static final int BLOCK_LINE = 17;
    private static final int BLOCK_GAP = 4;
    private static final int ICON_X = 18;
    private static final int TEXT_X = 38;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final StationTypesTab stations;
    private final ListScroller scroller;
    /** The state line after a refused item ("Unlocks nothing here"), until the next click. */
    private Optional<Component> notice = Optional.empty();

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    StationTypesTabView(JobBlockScreen screen, StationTypesTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.stations = tab;
        this.scroller = new ListScroller(screen);
    }

    @Override
    public void onTabChanged() {
        scroller.reset();
    }

    /** New Station Types change which Patterns tabs the tab bar shows. */
    @Override
    public void dataChanged() {
        screen.rebuild();
    }

    private static int slotX(int slot) {
        return SLOTS_X + slot * 18;
    }

    /** The slot under the mouse, {@code -1} if none. */
    private int slotAt(double mouseX, double mouseY) {
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
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        if (scroller.press(event.x(), event.y(), scrollbarX(), BLOCKS_TOP, BLOCKS_BOTTOM - BLOCKS_TOP, maxOffset())) {
            return true;
        }
        int slot = slotAt(event.x(), event.y());
        if (slot < 0) {
            return false;
        }
        StationTypesTabData tab = stations.data().get();
        Item item = menu.getCarried().getItem();
        notice = Optional.empty();
        if (menu.getCarried().isEmpty() || !tab.isOpen(slot)) {
            return true;
        }
        if (tab.jobsOf(item).isEmpty()) {
            notice = Optional.of(Component.translatable("civmod.gui.station_types.unlocks_nothing", new ItemStack(item).getHoverName()));
        } else if (tab.slots().contains(item)) {
            notice = Optional.of(Component.translatable("civmod.gui.station_types.already", new ItemStack(item).getHoverName()));
        } else {
            screen.clickMenuButton(stations.slotButtonId(slot));
            screen.clickSound();
        }
        return true;
    }

    /**
     * Shift + left click on an inventory item: shows the refusal line for an item that unlocks nothing or is in a slot
     * already; the menu moves an accepted one into a free slot on the server.
     *
     * @return {@code false}: the menu takes the click
     */
    @Override
    public boolean quickMove(ItemStack stack) {
        StationTypesTabData tab = stations.data().get();
        notice = Optional.empty();
        if (tab.jobsOf(stack.getItem()).isEmpty()) {
            notice = Optional.of(Component.translatable("civmod.gui.station_types.unlocks_nothing", stack.getHoverName()));
        } else if (tab.slots().contains(stack.getItem())) {
            notice = Optional.of(Component.translatable("civmod.gui.station_types.already", stack.getHoverName()));
        }
        return false;
    }

    /** Scrolls the job blocks. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return scroller.wheel(scrollY, maxOffset(), BLOCK_LINE);
    }

    /** Drags the scrollbar of the job blocks. */
    @Override
    public boolean mouseDragged(MouseButtonEvent event) {
        return scroller.drag(event.y(), BLOCKS_TOP, BLOCKS_BOTTOM - BLOCKS_TOP, maxOffset());
    }

    @Override
    public boolean mouseReleased() {
        return scroller.release();
    }

    private int scrollbarX() {
        return screen.panelWidth() - CivGui.PADDING - CivGui.SCROLLBAR_WIDTH;
    }

    private static int blockHeight() {
        return LINE_HEIGHT + 3 * BLOCK_LINE + BLOCK_GAP;
    }

    /** How far the job blocks scroll, in pixels: 0 while they fit. */
    private int maxOffset() {
        int content = stations.data().get().blocks().size() * blockHeight();
        return Math.max(0, content - (BLOCKS_BOTTOM - BLOCKS_TOP));
    }

    /**
     * @return the help lines: how slots work, then one line per unlock item and Job ("Stonecutter: Stonecutter – …")
     */
    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        StationTypesTabData tab = stations.data().get();
        List<Component> lines = new ArrayList<>(List.of(Component.translatable("civmod.gui.station_types.help.put"),
                Component.translatable("civmod.gui.station_types.help.shift")));
        for (StationTypes.Unlock unlock : tab.overview()) {
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
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        StationTypesTabData tab = stations.data().get();
        graphics.text(font, Component.translatable("civmod.gui.station_types.title"), CivGui.PADDING, 18, CivGui.LABEL_COLOR, false);
        int hovered = slotAt(mouseX, mouseY);
        for (int slot = 0; slot < StationTypes.SLOTS; slot++) {
            int x = slotX(slot);
            CivGui.slot(graphics, x, SLOTS_Y);
            Item item = tab.item(slot);
            if (item != Items.AIR) {
                graphics.item(new ItemStack(item), x, SLOTS_Y);
            }
            if (!tab.isOpen(slot)) {
                // Closed slots open with the JobBlock level.
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
        extractBlocks(graphics, tab);
        if (hovered >= 0) {
            graphics.setComponentTooltipForNextFrame(font, tooltip(tab, hovered), mouseX, mouseY);
        }
    }

    /** One block per unlocked Job in the look of the Needs rows: a name, then a tick or a cross, an icon and a text per need. */
    private void extractBlocks(GuiGraphicsExtractor graphics, StationTypesTabData tab) {
        Font font = screen.getFont();
        int maxOffset = maxOffset();
        int offset = scroller.offset(maxOffset);
        int right = screen.panelWidth() - CivGui.PADDING - (maxOffset > 0 ? CivGui.SCROLLBAR_WIDTH + 2 : 0);
        graphics.enableScissor(CivGui.PADDING - 2, BLOCKS_TOP, right + 2, BLOCKS_BOTTOM);
        int y = BLOCKS_TOP - offset;
        for (JobNeeds need : tab.blocks()) {
            graphics.text(font, CivGui.fit(font, need.job().displayName(), right - CivGui.PADDING), CivGui.PADDING, y, CivGui.LABEL_COLOR, false);
            y += LINE_HEIGHT;
            for (JobNeeds.Line line : List.of(need.station(), need.tool(), need.extra())) {
                extractLine(graphics, font, line, y, right);
                y += BLOCK_LINE;
            }
            y += BLOCK_GAP;
        }
        graphics.disableScissor();
        scroller.extract(graphics, scrollbarX(), BLOCKS_TOP, BLOCKS_BOTTOM - BLOCKS_TOP, maxOffset);
    }

    private static void extractLine(GuiGraphicsExtractor graphics, Font font, JobNeeds.Line line, int y, int right) {
        int textY = y + (16 - font.lineHeight) / 2;
        int color = switch (line.state()) {
            case NOT_NEEDED -> CivGui.HINT_COLOR;
            case PRESENT -> CivGui.NEED_MET_COLOR;
            case MISSING -> CivGui.ERROR_COLOR;
        };
        if (line.state() != JobNeeds.State.NOT_NEEDED) {
            graphics.text(font, Component.literal(line.present() ? "✓" : "✗"), CivGui.PADDING, textY, color, false);
        }
        if (!line.icon().isEmpty()) {
            graphics.item(line.icon(), ICON_X, y);
        }
        graphics.text(font, CivGui.fit(font, line.text(), right - TEXT_X), TEXT_X, textY, CivGui.LABEL_COLOR, false);
    }

    private static List<Component> tooltip(StationTypesTabData tab, int slot) {
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
        lines.add(Component.translatable("civmod.gui.station_types.unlocks", ComponentUtils.formatList(tab.jobsOf(item), Job::displayName))
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }
}
