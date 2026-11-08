package com.gilfort.civmod.client.gui;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.gilfort.civmod.config.CivModClientConfig;
import com.gilfort.civmod.config.CivModClientConfig.StorageSort;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.menu.StorageTerminal;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The Storage tab of a Clerk's settler window (vault {@code Jobs/Clerk.md}, terminal): the Clerk's whole storage as a
 * scrolling item grid with search and sort, above the player inventory. Clicks go to the server
 * ({@link StorageTerminal.Click}); outside the Clerk's shift the grid is grey and takes no clicks.
 */
final class StorageTabView {

    private static final int COLUMNS = 8;
    private static final int ROWS = 3;
    private static final int SLOT = 18;
    private static final int GRID_X = CivGui.PADDING;
    private static final int GRID_Y = 18;
    private static final int SCROLLBAR_X = GRID_X + COLUMNS * SLOT + 2;
    private static final int SEARCH_WIDTH = 64;
    private static final int SORT_WIDTH = 14;
    private static final int TOP_ROW_Y = 4;
    private static final int TOP_ROW_HEIGHT = 12;
    private static final int STATUS_Y = GRID_Y + ROWS * SLOT + 3;

    private final SettlerScreen screen;
    private final SettlerMenu menu;
    private final ListScroller scroller;
    private EditBox search;
    private Button sort;
    private String query = "";
    /** The shown resources, sorted and filtered; rebuilt when the list, the search or the sort changes. */
    private List<Row> shown = List.of();
    private int shownRevision = -1;
    private String shownQuery = "";
    private StorageSort shownSort = StorageSort.AMOUNT;

    /**
     * @param screen the settler screen
     * @param menu   its menu
     */
    StorageTabView(SettlerScreen screen, SettlerMenu menu) {
        this.screen = screen;
        this.menu = menu;
        this.scroller = new ListScroller(screen);
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    /**
     * Adds the search field and the sort button (on every rebuild of the screen).
     *
     * @param panelWidth the width of the panel
     */
    void init(int panelWidth) {
        int right = screen.left() + panelWidth - CivGui.PADDING;
        search = screen.addTabWidget(SettlerMenu.TAB_STORAGE, new EditBox(font(), right - SORT_WIDTH - 2 - SEARCH_WIDTH, screen.top() + TOP_ROW_Y,
                SEARCH_WIDTH, TOP_ROW_HEIGHT, Component.translatable("civmod.gui.storage.search")));
        search.setValue(query);
        search.setHint(Component.translatable("civmod.gui.storage.search"));
        search.setResponder(text -> query = text);
        sort = screen.addTabWidget(SettlerMenu.TAB_STORAGE, Button.builder(sortLabel(), button -> toggleSort())
                .bounds(right - SORT_WIDTH, screen.top() + TOP_ROW_Y, SORT_WIDTH, TOP_ROW_HEIGHT).build());
        sort.setTooltip(Tooltip.create(sortTooltip()));
    }

    private static Component sortLabel() {
        return Component.literal(CivModClientConfig.STORAGE_SORT.get().label());
    }

    private static Component sortTooltip() {
        return Component.translatable(CivModClientConfig.STORAGE_SORT.get().tooltipKey());
    }

    private void toggleSort() {
        CivModClientConfig.STORAGE_SORT.set(CivModClientConfig.STORAGE_SORT.get().next());
        CivModClientConfig.SPEC.save();
        sort.setMessage(sortLabel());
        sort.setTooltip(Tooltip.create(sortTooltip()));
    }

    /** One shown resource with its lower-case name, worked out once per list change. */
    private record Row(ItemResource resource, long amount, String name) {
    }

    private static final Comparator<Row> BY_NAME = Comparator.comparing(Row::name);
    private static final Comparator<Row> BY_AMOUNT = Comparator.comparingLong(Row::amount).reversed();

    /** The shown entries, re-sorted only when something changed. */
    private List<Row> shown() {
        StorageTerminal terminal = menu.terminal();
        StorageSort order = CivModClientConfig.STORAGE_SORT.get();
        if (terminal.revision() != shownRevision || !query.equals(shownQuery) || order != shownSort) {
            String needle = query.trim().toLowerCase(Locale.ROOT);
            Comparator<Row> comparator = order == StorageSort.AMOUNT ? BY_AMOUNT.thenComparing(BY_NAME) : BY_NAME.thenComparing(BY_AMOUNT);
            shown = terminal.entries().entrySet().stream()
                    .map(entry -> new Row(entry.getKey(), entry.getValue(), entry.getKey().toStack(1).getHoverName().getString().toLowerCase(Locale.ROOT)))
                    .filter(row -> needle.isEmpty() || row.name().contains(needle))
                    .sorted(comparator)
                    .toList();
            shownRevision = terminal.revision();
            shownQuery = query;
            shownSort = order;
        }
        return shown;
    }

    private int maxOffset() {
        return Math.max(0, (shown().size() + COLUMNS - 1) / COLUMNS - ROWS);
    }

    /** The index in {@link #shown()} under the mouse, -1 for none, {@link #shown()}'s size for a free slot. */
    private int indexAt(double mouseX, double mouseY) {
        double relX = mouseX - screen.left() - GRID_X;
        double relY = mouseY - screen.top() - GRID_Y;
        if (relX < 0 || relY < 0 || relX >= COLUMNS * SLOT || relY >= ROWS * SLOT) {
            return -1;
        }
        int index = (scroller.offset(maxOffset()) + (int) relY / SLOT) * COLUMNS + (int) relX / SLOT;
        return Math.min(index, shown().size());
    }

    /** Draws the slot backgrounds (absolute coordinates). */
    void extractBackground(GuiGraphicsExtractor graphics) {
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            CivGui.slot(graphics, screen.left() + GRID_X + (i % COLUMNS) * SLOT, screen.top() + GRID_Y + (i / COLUMNS) * SLOT);
        }
    }

    /** Draws the items, amounts, scrollbar and status (panel coordinates). */
    void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        StorageTerminal terminal = menu.terminal();
        List<Row> entries = shown();
        int first = scroller.offset(maxOffset()) * COLUMNS;
        int hovered = indexAt(mouseX, mouseY);
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            int x = GRID_X + (i % COLUMNS) * SLOT;
            int y = GRID_Y + (i / COLUMNS) * SLOT;
            int index = first + i;
            if (index < entries.size()) {
                ItemStack icon = entries.get(index).resource().toStack(1);
                graphics.item(icon, x, y);
                graphics.itemDecorations(font(), icon, x, y, StorageTerminal.shortAmount(entries.get(index).amount()));
            }
            if (!terminal.onShift()) {
                CivGui.slotInactive(graphics, x, y);
            } else if (index == hovered) {
                CivGui.slotHover(graphics, x, y);
            }
        }
        scroller.extract(graphics, SCROLLBAR_X, GRID_Y, ROWS * SLOT, maxOffset());
        if (!terminal.onShift()) {
            graphics.text(font(), Component.translatable("civmod.gui.storage.closed"), GRID_X, STATUS_Y, CivGui.HINT_COLOR, false);
        } else if (terminal.entries().isEmpty()) {
            graphics.text(font(), Component.translatable("civmod.gui.storage.empty"), GRID_X, STATUS_Y, CivGui.HINT_COLOR, false);
        }
        if (hovered >= 0 && hovered < entries.size() && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font(), entries.get(hovered).resource().toStack(1), mouseX, mouseY);
        }
    }

    /**
     * A click on the grid: empty-handed left takes a stack, right half a stack, shift-click into the inventory, Ctrl + left
     * click one item (more of the same one with it already on the cursor); with an item on the cursor left stores it,
     * right stores one.
     *
     * @param event the click
     * @return whether the click was taken
     */
    boolean mouseClicked(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && scroller.press(event.x(), event.y(), SCROLLBAR_X, GRID_Y, ROWS * SLOT, maxOffset())) {
            return true;
        }
        int index = indexAt(event.x(), event.y());
        if (index < 0) {
            return false;
        }
        if (!menu.terminal().onShift()) {
            return true;
        }
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        if (!right && event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return true;
        }
        ItemStack icon = index < shown().size() ? shown().get(index).resource().toStack(1) : ItemStack.EMPTY;
        StorageTerminal.Action action;
        if (!right && !icon.isEmpty() && event.hasControlDown() && !event.hasShiftDown()) {
            action = StorageTerminal.Action.TAKE_ONE;
        } else if (!menu.getCarried().isEmpty()) {
            action = right ? StorageTerminal.Action.STORE_ONE : StorageTerminal.Action.STORE;
        } else if (icon.isEmpty()) {
            return true;
        } else if (event.hasShiftDown()) {
            action = StorageTerminal.Action.TAKE_TO_INVENTORY;
        } else {
            action = right ? StorageTerminal.Action.TAKE_HALF : StorageTerminal.Action.TAKE_STACK;
        }
        ClientPacketDistributor.sendToServer(new StorageTerminal.Click(menu.containerId, icon, action));
        return true;
    }

    /**
     * @param scrollY the wheel movement
     * @return whether the grid scrolls (the wheel was taken)
     */
    boolean mouseScrolled(double scrollY) {
        return scroller.wheel(scrollY, maxOffset());
    }

    /**
     * @param event the drag
     * @return whether the scrollbar is being dragged
     */
    boolean mouseDragged(MouseButtonEvent event) {
        return scroller.drag(event.y(), GRID_Y, ROWS * SLOT, maxOffset());
    }

    /**
     * @return whether the scrollbar was being dragged
     */
    boolean mouseReleased() {
        return scroller.release();
    }

    /** Typing into the search field must not trigger the inventory key or hotbar swaps. */
    boolean keyPressed(KeyEvent event) {
        if (search != null && search.isFocused() && search.visible && !event.isEscape()) {
            search.keyPressed(event);
            return true;
        }
        return false;
    }

    /** The list changed: the first rows are shown again if the list got shorter than the offset. */
    void dataChanged() {
        scroller.offset(maxOffset());
    }
}
