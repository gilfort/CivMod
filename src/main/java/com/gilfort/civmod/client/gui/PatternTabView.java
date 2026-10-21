package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.GuiIcons;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.PatternTabData;
import com.gilfort.civmod.menu.PatternsTab;
import com.gilfort.civmod.menu.TabData;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The "Patterns" tabs of a JobBlock in the {@link JobBlockScreen} (vault {@code Systems/Producing Jobs.md}), one per
 * Producing Job with a library there; the client side of {@link PatternsTab}, one view for all of them. Two views: the library (54 pattern slots
 * with checkmarks) and the editor of one pattern (3×3 grid, output, recipe arrows, Check / Clear / Back). Also the target
 * of recipe viewer transfers. Adds the delivery toggle (Self / Deliveryman) to the Info tab.
 */
final class PatternTabView implements JobBlockTabView {

    /** The library: 6 rows of 9 pattern slots. */
    private static final int LIBRARY_X = CivGui.PADDING;
    private static final int LIBRARY_Y = 18;
    private static final int LIBRARY_ROWS = 6;
    /** The pattern editor: 3×3 grid and output slot. */
    private static final int GRID_X = 30;
    private static final int GRID_Y = 30;
    private static final int OUTPUT_X = 116;
    private static final int OUTPUT_Y = 48;
    private static final int PATTERN_BUTTONS_Y = 94;
    /** Ghost target ids of the editor (library slots are 0–53). */
    private static final int TARGET_CELL = 100;
    private static final int TARGET_OUTPUT = 200;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    /** The pattern open in the editor, {@code -1} while the library is shown. */
    private int editing = -1;
    private @Nullable Button checkButton;
    private @Nullable Button previousButton;
    private @Nullable Button nextButton;

    /**
     * @param screen the screen showing the tab
     */
    PatternTabView(JobBlockScreen screen) {
        this.screen = screen;
        this.menu = screen.getMenu();
    }

    // ---------------------------------------------------------------- the shown library

    /** The Job of the Patterns tab shown, else of the one looked at last (the Base Job before any). */
    private Job job() {
        return menu.activeTab() instanceof PatternsTab shown ? shown.job() : library().get().job().orElse(menu.getInfo().jobType());
    }

    /** The synced library of {@link #job()}; empty until the server sent it. */
    private PatternTabData tab() {
        PatternTabData synced = library().get();
        return synced.job().filter(job()::equals).isPresent() ? synced : PatternTabData.EMPTY;
    }

    /** The library the server sent last, shared by the Patterns tabs. */
    private TabData<PatternTabData> library() {
        return menu.tab(PatternsTab.class).orElseThrow().data();
    }

    /** The delivery toggle (Self / Deliveryman) on the Info tab of a JobBlock in a settlement; the Base Job's tab handles it. */
    @Override
    public void initInfo(int info) {
        JobBlockInfo current = menu.getInfo();
        PatternsTab base = menu.tab(PatternsTab.class).orElseThrow();
        if (current.inSettlement()) {
            InfoTabView.button(screen, info, Component.translatable(current.byDeliveryman() ? "civmod.gui.job_block.delivery.deliveryman"
                    : "civmod.gui.job_block.delivery.self"), InfoTabView.TOGGLE_ROW, base.buttonId(PatternsTab.TOGGLE_DELIVERY), true);
        }
    }

    @Override
    public List<Component> infoHelpLines() {
        return List.of(Component.translatable("civmod.gui.job_block.help.delivery"));
    }

    /** Another tab: the editor closes. */
    @Override
    public void onTabChanged() {
        if (editing >= 0) {
            showLibrary();
        }
    }

    // ---------------------------------------------------------------- editor widgets

    /** Adds the editor's widgets while a pattern is open (called on every widget rebuild). */
    @Override
    public void init() {
        int tabIndex = PatternsTab.of(menu, job()).map(JobBlockTab::index).orElse(-1);
        if (editing < 0 || tabIndex < 0) {
            return;
        }
        int left = screen.left();
        int y = screen.top() + PATTERN_BUTTONS_Y;
        int width = (screen.panelWidth() - 2 * CivGui.PADDING - 4) / 3;
        checkButton = screen.addWidget(tabIndex, Button.builder(Component.empty(), button -> sendPattern(
                PatternTabData.Edit.toggleCheck(menu.containerId, job(), editing))).bounds(left + CivGui.PADDING, y, width, 20).build());
        screen.addWidget(tabIndex, Button.builder(Component.translatable("civmod.gui.patterns.clear"), button -> sendPattern(
                PatternTabData.Edit.clear(menu.containerId, job(), editing))).bounds(left + CivGui.PADDING + width + 2, y, width, 20).build());
        screen.addWidget(tabIndex, Button.builder(Component.translatable("gui.back"), button -> showLibrary())
                .bounds(left + CivGui.PADDING + 2 * (width + 2), y, width, 20).build());
        previousButton = screen.addWidget(tabIndex, Button.builder(Component.literal("<"), button -> sendPattern(
                PatternTabData.Edit.cycle(menu.containerId, job(), editing, -1))).bounds(left + OUTPUT_X - 9, screen.top() + OUTPUT_Y + 20, 16, 16).build());
        nextButton = screen.addWidget(tabIndex, Button.builder(Component.literal(">"), button -> sendPattern(
                PatternTabData.Edit.cycle(menu.containerId, job(), editing, 1))).bounds(left + OUTPUT_X + 9, screen.top() + OUTPUT_Y + 20, 16, 16).build());
        Tooltip cycle = Tooltip.create(Component.translatable("civmod.gui.patterns.cycle"));
        previousButton.setTooltip(cycle);
        nextButton.setTooltip(cycle);
        update();
    }

    /** The server sent a library: the editor buttons follow it. */
    @Override
    public void dataChanged() {
        update();
    }

    /** Shows the edited pattern's state on the editor buttons. */
    private void update() {
        if (checkButton == null || previousButton == null || nextButton == null || editing < 0) {
            return;
        }
        PatternTabData tab = tab();
        Optional<Pattern> pattern = tab.pattern(editing);
        boolean checked = pattern.map(Pattern::checked).orElse(false);
        checkButton.setMessage(Component.translatable(checked ? "civmod.gui.patterns.uncheck" : "civmod.gui.patterns.check"));
        checkButton.active = !tab.locked() && (checked || (pattern.filter(Pattern::valid).isPresent() && !tab.unusable(editing)
                && tab.checked() < tab.limit()));
        boolean recipe = pattern.filter(found -> found.valid() && !found.exact()).isPresent();
        previousButton.active = recipe;
        nextButton.active = recipe;
    }

    private void showEditor(int slot) {
        editing = slot;
        screen.rebuild();
    }

    private void showLibrary() {
        editing = -1;
        checkButton = null;
        previousButton = null;
        nextButton = null;
        screen.rebuild();
    }

    private void sendPattern(PatternTabData.Edit edit) {
        ClientPacketDistributor.sendToServer(edit);
        screen.clickSound();
    }

    // ---------------------------------------------------------------- targets

    private static int librarySlotX(int slot) {
        return LIBRARY_X + (slot % 9) * 18;
    }

    private static int librarySlotY(int slot) {
        return LIBRARY_Y + (slot / 9) * 18;
    }

    private static int cellX(int cell) {
        return GRID_X + (cell % 3) * 18;
    }

    private static int cellY(int cell) {
        return GRID_Y + (cell / 3) * 18;
    }

    /**
     * The ghost targets of the Patterns tab, relative to the panel: the library slots (output), or the grid cells and the
     * output of the open pattern.
     */
    private Map<Integer, Rect2i> areas() {
        Map<Integer, Rect2i> areas = new LinkedHashMap<>();
        if (!(menu.activeTab() instanceof PatternsTab)) {
            return areas;
        }
        if (editing < 0) {
            for (int slot = 0; slot < Pattern.SLOTS; slot++) {
                areas.put(slot, new Rect2i(librarySlotX(slot), librarySlotY(slot), 16, 16));
            }
            return areas;
        }
        for (int cell = 0; cell < Pattern.GRID; cell++) {
            areas.put(TARGET_CELL + cell, new Rect2i(cellX(cell), cellY(cell), 16, 16));
        }
        areas.put(TARGET_OUTPUT, new Rect2i(OUTPUT_X, OUTPUT_Y, 16, 16));
        return areas;
    }

    /**
     * @return target id → absolute area, for JEI / REI ghost drag
     */
    @Override
    public Map<Integer, Rect2i> ghostTargets() {
        Map<Integer, Rect2i> targets = new LinkedHashMap<>();
        areas().forEach((id, area) -> targets.put(id, new Rect2i(screen.left() + area.getX(), screen.top() + area.getY(), area.getWidth(),
                area.getHeight())));
        return targets;
    }

    /**
     * @param x the mouse x position
     * @param y the mouse y position
     * @return the target under the mouse, {@code -1} if none
     */
    @Override
    public int ghostTargetAt(double x, double y) {
        for (Map.Entry<Integer, Rect2i> entry : areas().entrySet()) {
            Rect2i area = entry.getValue();
            if (area.contains((int) Math.floor(x - screen.left()), (int) Math.floor(y - screen.top()))) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Puts an item into a pattern target as a ghost (the item is not taken).
     *
     * @return whether the target took it
     */
    @Override
    public boolean acceptGhost(int target, ItemStack stack) {
        if (stack.isEmpty() || target < 0) {
            return false;
        }
        if (target < Pattern.SLOTS) {
            sendPattern(PatternTabData.Edit.setOutput(menu.containerId, job(), target, stack.getItem()));
        } else if (target == TARGET_OUTPUT) {
            sendPattern(PatternTabData.Edit.setOutput(menu.containerId, job(), editing, stack.getItem()));
        } else {
            sendPattern(PatternTabData.Edit.setInput(menu.containerId, job(), editing, target - TARGET_CELL, stack.getItem()));
        }
        return true;
    }

    /**
     * A click on the Patterns tab. Library: with an item on the cursor the slot's pattern is made from that output, a
     * left click opens the editor, a right click checks or unchecks. Editor: with an item a cell (or the output) is set,
     * a right click empties a cell.
     *
     * @return whether the click was taken
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        int target = ghostTargetAt(event.x(), event.y());
        if (target < 0) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            return acceptGhost(target, carried);
        }
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        if (target < Pattern.SLOTS) {
            if (right) {
                if (tab().pattern(target).isPresent()) {
                    sendPattern(PatternTabData.Edit.toggleCheck(menu.containerId, job(), target));
                }
            } else {
                screen.clickSound();
                showEditor(target);
            }
            return true;
        }
        if (right && target != TARGET_OUTPUT) {
            sendPattern(PatternTabData.Edit.setInput(menu.containerId, job(), editing, target - TARGET_CELL, Items.AIR));
        }
        return true;
    }

    /**
     * Fills a pattern from a recipe viewer's transfer button: the open pattern, else the first empty slot of the library
     * shown (or looked at last).
     *
     * @param type       the recipe type of the recipe the viewer shows
     * @param recipe     the recipe the viewer shows
     * @param grid       the items it shows, row by row
     * @param doTransfer whether to transfer (false: only check)
     * @return the reason it cannot, empty if it can (always present: Producing JobBlocks take recipes)
     */
    @Override
    public Optional<Optional<Component>> transfer(RecipeType<?> type, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid,
            boolean doTransfer) {
        return Optional.of(transferPattern(type, recipe, grid, doTransfer));
    }

    private Optional<Component> transferPattern(RecipeType<?> type, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid, boolean doTransfer) {
        PatternTabData tab = tab();
        if (!tab.follows(type)) {
            return Optional.of(Component.translatable(tab.locked() ? "civmod.gui.patterns.locked" : "civmod.gui.patterns.foreign"));
        }
        int slot = editing;
        for (int candidate = 0; slot < 0 && candidate < Pattern.SLOTS; candidate++) {
            if (tab.pattern(candidate).isEmpty()) {
                slot = candidate;
            }
        }
        if (slot < 0) {
            return Optional.of(Component.translatable("civmod.gui.patterns.full"));
        }
        if (doTransfer) {
            sendPattern(PatternTabData.Edit.transfer(menu.containerId, job(), slot, recipe, grid));
            PatternsTab.of(menu, job()).ifPresent(shown -> screen.select(shown.index()));
            showEditor(slot);
        }
        return Optional.empty();
    }

    /**
     * @return the help lines of the Patterns tab: the library's or the editor's usage
     */
    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        if (editing >= 0) {
            return List.of(Component.translatable("civmod.gui.patterns.help.cell"), Component.translatable("civmod.gui.patterns.help.empty_cell"));
        }
        return List.of(Component.translatable("civmod.gui.patterns.help.click"), Component.translatable("civmod.gui.patterns.help.check"),
                Component.translatable("civmod.gui.patterns.help.item"), Component.translatable("civmod.gui.patterns.help.viewer"),
                Component.translatable("civmod.gui.patterns.help.crafted", GuiIcons.inlineCheckmark()));
    }

    // ---------------------------------------------------------------- drawing

    /** Draws the Patterns tab: the library with checkmarks, or the editor of one pattern. */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        PatternTabData tab = tab();
        int hovered = ghostTargetAt(mouseX, mouseY);
        if (editing >= 0) {
            extractEditor(graphics, tab, hovered, mouseX, mouseY);
            return;
        }
        for (int slot = 0; slot < LIBRARY_ROWS * 9; slot++) {
            int x = librarySlotX(slot);
            int y = librarySlotY(slot);
            CivGui.slot(graphics, x, y);
            Optional<Pattern> pattern = tab.pattern(slot);
            pattern.filter(Pattern::valid).ifPresent(found -> {
                ItemStack icon = new ItemStack(found.output(), found.count());
                graphics.item(icon, x, y);
                graphics.itemDecorations(font, icon, x, y);
            });
            if (tab.unusable(slot)) {
                // Filtered, a foreign recipe type (keeps its checkmark) or a locked library: the Job cannot use it.
                CivGui.slotInactive(graphics, x, y);
            }
            if (pattern.filter(Pattern::checked).isPresent()) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GuiIcons.CHECKMARK, x - 1, y - 1, 9, 9);
            }
            if (slot == hovered) {
                CivGui.slotHover(graphics, x, y);
            }
        }
        // Two lines between the library and the inventory label (which starts 9 px below the second one).
        int y = LIBRARY_Y + LIBRARY_ROWS * 18 + 2;
        graphics.text(font, Component.translatable("civmod.gui.patterns.checkmarks", tab.checked(), tab.limit()), CivGui.PADDING, y,
                CivGui.LABEL_COLOR, false);
        Optional<Component> state = tab.locked() ? Optional.of(Component.translatable("civmod.gui.patterns.locked")) : tab.notice();
        state.ifPresent(line -> graphics.text(font, CivGui.fit(font, line, screen.panelWidth() - 2 * CivGui.PADDING), CivGui.PADDING, y + 9,
                CivGui.ERROR_COLOR, false));
        if (hovered >= 0) {
            graphics.setComponentTooltipForNextFrame(font, tooltip(tab, hovered), mouseX, mouseY);
        }
    }

    /** Why the Job cannot use a pattern: a locked library, a foreign recipe type, else its profile's filter line. */
    private static Component unusableLine(PatternTabData tab, int slot) {
        if (tab.locked()) {
            return Component.translatable("civmod.gui.patterns.locked");
        }
        return tab.foreign().contains(slot) ? Component.translatable("civmod.gui.patterns.foreign") : tab.filteredLine();
    }

    private static List<Component> tooltip(PatternTabData tab, int slot) {
        List<Component> lines = new ArrayList<>();
        Optional<Pattern> pattern = tab.pattern(slot);
        if (pattern.filter(Pattern::valid).isPresent()) {
            Pattern found = pattern.get();
            lines.add(Component.translatable("civmod.gui.clerk.request.amount", found.count(), new ItemStack(found.output()).getHoverName()));
            lines.add(Component.translatable(found.exact() ? "civmod.gui.patterns.exact" : "civmod.gui.patterns.recipe").withStyle(ChatFormatting.GRAY));
            if (tab.unusable(slot)) {
                lines.add(unusableLine(tab, slot).copy().withStyle(ChatFormatting.RED));
            } else {
                lines.add(Component.translatable(found.checked() ? "civmod.gui.patterns.checked" : "civmod.gui.patterns.unchecked")
                        .withStyle(found.checked() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
        } else {
            lines.add(Component.translatable(pattern.isPresent() ? "civmod.gui.patterns.no_recipe" : "civmod.gui.patterns.empty_slot"));
        }
        return lines;
    }

    private void extractEditor(GuiGraphicsExtractor graphics, PatternTabData tab, int hovered, int mouseX, int mouseY) {
        Font font = screen.getFont();
        Optional<Pattern> pattern = tab.pattern(editing);
        graphics.text(font, Component.translatable("civmod.gui.patterns.editing", editing + 1), CivGui.PADDING, 18, CivGui.LABEL_COLOR, false);
        for (int cell = 0; cell < Pattern.GRID; cell++) {
            int x = cellX(cell);
            int y = cellY(cell);
            CivGui.slot(graphics, x, y);
            int index = cell;
            Item item = pattern.map(found -> found.grid().get(index)).orElse(Items.AIR);
            if (item != Items.AIR) {
                graphics.item(new ItemStack(item), x, y);
            }
            if (TARGET_CELL + cell == hovered) {
                CivGui.slotHover(graphics, x, y);
            }
        }
        graphics.text(font, Component.literal("→"), GRID_X + 3 * 18 + 6, OUTPUT_Y + 4, CivGui.LABEL_COLOR, false);
        CivGui.slot(graphics, OUTPUT_X, OUTPUT_Y);
        pattern.filter(Pattern::valid).ifPresent(found -> {
            ItemStack icon = new ItemStack(found.output(), found.count());
            graphics.item(icon, OUTPUT_X, OUTPUT_Y);
            graphics.itemDecorations(font, icon, OUTPUT_X, OUTPUT_Y);
        });
        if (hovered == TARGET_OUTPUT) {
            CivGui.slotHover(graphics, OUTPUT_X, OUTPUT_Y);
        }
        // Two short state lines between the buttons and the inventory label; what they mean is in the help.
        boolean valid = pattern.filter(Pattern::valid).isPresent();
        Component mode = pattern.map(found -> Component.translatable(!found.valid() ? "civmod.gui.patterns.no_recipe"
                : found.exact() ? "civmod.gui.patterns.exact" : "civmod.gui.patterns.recipe")).orElse(Component.translatable("civmod.gui.patterns.empty_slot"));
        int y = PATTERN_BUTTONS_Y + 24;
        int width = screen.panelWidth() - 2 * CivGui.PADDING;
        graphics.text(font, CivGui.fit(font, mode, width), CivGui.PADDING, y, pattern.isPresent() && !valid ? CivGui.ERROR_COLOR : CivGui.HINT_COLOR,
                false);
        if (tab.unusable(editing)) {
            graphics.text(font, CivGui.fit(font, unusableLine(tab, editing), width)
, CivGui.PADDING, y + 10, CivGui.ERROR_COLOR, false);
        }
        if (hovered >= TARGET_CELL && hovered < TARGET_CELL + Pattern.GRID) {
            Item item = pattern.map(found -> found.grid().get(hovered - TARGET_CELL)).orElse(Items.AIR);
            if (item != Items.AIR) {
                graphics.setComponentTooltipForNextFrame(font, List.of(new ItemStack(item).getHoverName()), mouseX, mouseY);
            }
        }
    }
}
