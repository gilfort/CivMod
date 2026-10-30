package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.StockTab;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Clerk's "Stock" tab of the {@link JobBlockScreen} (vault {@code Jobs/Clerk.md}); the client side of
 * {@link StockTab}: 5 rows of 9 stock slots (9 per JobBlock level active) and the editor of the selected rule below.
 * The Workers tab gets the Warehouse line ({@link #extractWorkers}).
 */
final class StockTabView implements JobBlockTabView {

    /** Stock grid of the Clerk: 5 rows of 9 stock rule slots (9 per JobBlock level). */
    private static final int STOCK_X = CivGui.PADDING;
    private static final int STOCK_Y = 18;
    private static final int STOCK_ROWS = 5;
    /** The rule editor below the grid: rows Min / Max / Order on the left, priority and delete on the right. */
    private static final int STOCK_EDIT_Y = STOCK_Y + STOCK_ROWS * 18 + 14;
    /** Height of an editor row and the distance between two rows. */
    private static final int EDIT_ROW = 15;
    /** Left edge of the − buttons of the Min / Max / Order rows (after their labels). */
    private static final int FIELD_X = 40;
    /** Left edge of the priority column. */
    private static final int PRIORITY_X = 110;

    /** A number field of the rule editor. */
    private enum Field {
        MIN("civmod.gui.clerk.stock.field.min"),
        MAX("civmod.gui.clerk.stock.field.max"),
        ORDER("civmod.gui.clerk.stock.field.order"),
        PRIORITY("civmod.gui.clerk.stock.priority");

        private final String key;

        Field(String key) {
            this.key = key;
        }

        private int get(StockRule rule) {
            return switch (this) {
                case MIN -> rule.min();
                case MAX -> rule.max();
                case ORDER -> rule.order();
                case PRIORITY -> rule.priority();
            };
        }

        private StockRule with(StockRule rule, int value) {
            return switch (this) {
                case MIN -> rule.withMin(value);
                case MAX -> rule.withMax(value);
                case ORDER -> rule.withOrder(value);
                case PRIORITY -> rule.withPriority(value);
            };
        }
    }

    /** The widgets of one number field: − / text field / +. */
    private record FieldWidgets(Field field, Button minus, EditBox box, Button plus) {
    }

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final StockTab stock;
    /** The selected rule slot, {@code -1} if none. */
    private int selectedSlot = -1;
    private final List<FieldWidgets> fields = new ArrayList<>();
    private @Nullable Button delete;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    StockTabView(JobBlockScreen screen, StockTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.stock = tab;
    }

    private Font font() {
        return screen.getFont();
    }

    /** The Clerk's data as last synced. */
    private ClerkTabData clerk() {
        return stock.data().get();
    }

    // ---------------------------------------------------------------- stock rule editor

    /** Adds the stock rule editor's widgets (called on every widget rebuild). */
    @Override
    public void init() {
        fields.clear();
        int y = screen.top() + STOCK_EDIT_Y;
        int x = screen.left();
        fields.add(field(Field.MIN, x + FIELD_X, y, 32));
        fields.add(field(Field.MAX, x + FIELD_X, y + EDIT_ROW, 32));
        fields.add(field(Field.ORDER, x + FIELD_X, y + 2 * EDIT_ROW, 32));
        fields.add(field(Field.PRIORITY, x + PRIORITY_X, y + EDIT_ROW, 26));
        delete = screen.addWidget(stock.index(), Button.builder(Component.literal("✕"), button -> deleteSelected())
                .bounds(x + PRIORITY_X + 40, y + 2 * EDIT_ROW, 18, EDIT_ROW - 1).build());
        delete.setTooltip(Tooltip.create(Component.translatable("civmod.gui.clerk.stock.delete")));
        update();
    }

    private FieldWidgets field(Field field, int x, int y, int boxWidth) {
        Button minus = screen.addWidget(stock.index(), Button.builder(Component.literal("-"), button -> step(field, -1))
                .bounds(x, y, 12, EDIT_ROW - 1).build());
        EditBox box = screen.addWidget(stock.index(), new EditBox(font(), x + 13, y, boxWidth, EDIT_ROW - 1, Component.translatable(field.key)));
        box.setMaxLength(4);
        // Empty or 0: not set.
        box.setResponder(text -> parse(text).ifPresent(value -> editSelected(rule -> field.with(rule, value))));
        Button plus = screen.addWidget(stock.index(), Button.builder(Component.literal("+"), button -> step(field, 1))
                .bounds(x + 14 + boxWidth, y, 12, EDIT_ROW - 1).build());
        return new FieldWidgets(field, minus, box, plus);
    }

    /** The server sent the Clerk's data: the editor shows it. */
    @Override
    public void dataChanged() {
        update();
    }

    /** Shows the selected rule in the editor (a focused box keeps what the player types); Order and priority need Min. */
    private void update() {
        if (delete == null) {
            return;
        }
        Optional<StockRule> rule = selectedStockRule();
        boolean editable = rule.isPresent() && rule.get().activeAt(clerk().level());
        boolean min = rule.filter(StockRule::hasMin).isPresent();
        for (FieldWidgets widgets : fields) {
            boolean active = editable && (min || widgets.field() == Field.MIN || widgets.field() == Field.MAX);
            widgets.minus().active = active;
            widgets.plus().active = active;
            widgets.box().setEditable(active);
            if (!widgets.box().isFocused()) {
                widgets.box().setValue(rule.map(found -> text(widgets.field(), found)).orElse(""));
            }
        }
        delete.active = rule.isPresent();
    }

    /** The field's value as shown: "—" when not set (priority always shows its number). */
    private static String text(Field field, StockRule rule) {
        int value = field.get(rule);
        return field != Field.PRIORITY && value == StockRule.NOT_SET ? "—" : Integer.toString(value);
    }

    private Optional<StockRule> selectedStockRule() {
        return selectedSlot < 0 ? Optional.empty() : clerk().rule(selectedSlot);
    }

    /** A typed value; an empty field is 0 ("not set"), anything else that is no number is ignored. */
    private static Optional<Integer> parse(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.of(StockRule.NOT_SET);
        }
        try {
            return Optional.of(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** − / +: steps of 1, Shift 10, Ctrl 64. */
    private void step(Field field, int sign) {
        Minecraft minecraft = Minecraft.getInstance();
        int step = minecraft.hasControlDown() ? 64 : minecraft.hasShiftDown() ? 10 : 1;
        editSelected(rule -> field.with(rule, field.get(rule) + sign * step));
    }

    /**
     * Changes the selected rule (active slots only) and sends it to the server; a change that would leave neither Min
     * nor Max set is not sent.
     */
    private void editSelected(UnaryOperator<StockRule> change) {
        selectedStockRule().filter(rule -> rule.activeAt(clerk().level())).ifPresent(rule -> {
            StockRule changed = change.apply(rule);
            if (!changed.equals(rule) && changed.valid()) {
                sendStockRule(rule.slot(), Optional.of(changed));
            }
        });
    }

    private void deleteSelected() {
        if (selectedStockRule().isPresent()) {
            sendStockRule(selectedSlot, Optional.empty());
        }
    }

    private void sendStockRule(int slot, Optional<StockRule> rule) {
        ClientPacketDistributor.sendToServer(new ClerkTabData.Edit(menu.containerId, slot, rule));
    }

    // ---------------------------------------------------------------- stock slots

    /**
     * Sets the item of a rule slot, as a ghost: the item is not taken. A new rule gets the stack's count (at least 1)
     * as Min with priority 0; an existing rule keeps its fields.
     *
     * @param slot  the rule slot
     * @param stack the item
     * @return whether the slot took it (an active slot)
     */
    @Override
    public boolean acceptGhost(int slot, ItemStack stack) {
        if (stack.isEmpty() || slot < 0 || slot >= StockRule.slots(clerk().level())) {
            return false;
        }
        StockRule rule = clerk().rule(slot)
                .map(found -> found.withItem(stack.getItem()))
                .orElseGet(() -> StockRule.minimum(slot, stack.getItem(), Math.max(1, stack.getCount()), 0));
        selectedSlot = slot;
        sendStockRule(slot, Optional.of(rule));
        screen.clickSound();
        return true;
    }

    /** Shift + right-click: the first rule slot without a rule. */
    @Override
    public int nextEmptyGhost() {
        return clerk().nextEmptyGhostSlot();
    }

    /**
     * The screen areas that take a dragged item as a rule item (for JEI / REI ghost drag): the active rule slots.
     *
     * @return rule slot → absolute area
     */
    @Override
    public Map<Integer, Rect2i> ghostTargets() {
        Map<Integer, Rect2i> targets = new LinkedHashMap<>();
        for (int slot = 0; slot < StockRule.slots(clerk().level()); slot++) {
            targets.put(slot, new Rect2i(screen.left() + stockX(slot), screen.top() + stockY(slot), 16, 16));
        }
        return targets;
    }

    /**
     * @param x the mouse x position
     * @param y the mouse y position
     * @return the rule slot under the mouse, {@code -1} if none
     */
    @Override
    public int ghostTargetAt(double x, double y) {
        int column = (int) Math.floor((x - screen.left() - STOCK_X + 1) / 18.0);
        int row = (int) Math.floor((y - screen.top() - STOCK_Y + 1) / 18.0);
        return column >= 0 && column < 9 && row >= 0 && row < STOCK_ROWS ? row * 9 + column : -1;
    }

    private static int stockX(int slot) {
        return STOCK_X + (slot % 9) * 18;
    }

    private static int stockY(int slot) {
        return STOCK_Y + (slot / 9) * 18;
    }

    /**
     * A click on a rule slot: with an item on the cursor it becomes the rule's item (not consumed); otherwise a left
     * click selects the rule, a right click deletes it.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        int slot = ghostTargetAt(event.x(), event.y());
        if (slot < 0) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            acceptGhost(slot, carried);
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (clerk().rule(slot).isPresent()) {
                sendStockRule(slot, Optional.empty());
                screen.clickSound();
            }
            return true;
        }
        selectedSlot = slot;
        fields.forEach(widgets -> widgets.box().setFocused(false));
        update();
        screen.clickSound();
        return true;
    }

    /** Typing into a number box must not trigger the inventory key or hotbar swaps. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        for (FieldWidgets widgets : fields) {
            EditBox box = widgets.box();
            if (box.isFocused() && box.visible && !event.isEscape()) {
                box.keyPressed(event);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws the Stock tab: rule slots in rows of 9 (9 per JobBlock level active, the rest greyed), the selected rule
     * framed, and the editor labels.
     */
    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = font();
        ClerkTabData tab = clerk();
        int active = StockRule.slots(tab.level());
        int hovered = ghostTargetAt(mouseX, mouseY);
        for (int slot = 0; slot < STOCK_ROWS * 9; slot++) {
            int x = stockX(slot);
            int y = stockY(slot);
            CivGui.slot(graphics, x, y);
            Optional<StockRule> rule = tab.rule(slot);
            if (rule.isPresent()) {
                ItemStack icon = new ItemStack(rule.get().item());
                graphics.item(icon, x, y);
                // Min, or Max for a rule without Min.
                graphics.itemDecorations(font, icon, x, y, Integer.toString(rule.get().hasMin() ? rule.get().min() : rule.get().max()));
            }
            if (slot >= active) {
                CivGui.slotInactive(graphics, x, y);
            }
            if (slot == selectedSlot) {
                CivGui.slotSelected(graphics, x, y);
            } else if (slot == hovered) {
                CivGui.slotHover(graphics, x, y);
            }
        }
        Optional<StockRule> selected = selectedStockRule();
        Component label = selected.<Component>map(rule -> Component.translatable("civmod.gui.clerk.stock.selected", new ItemStack(rule.item()).getHoverName()))
                .orElseGet(() -> Component.translatable(selectedSlot >= 0 ? "civmod.gui.clerk.stock.empty_slot" : "civmod.gui.clerk.stock.none_selected"));
        graphics.text(font, CivGui.fit(font, label, screen.panelWidth() - 2 * CivGui.PADDING), CivGui.PADDING, STOCK_EDIT_Y - 11, CivGui.LABEL_COLOR, false);
        boolean min = selected.filter(StockRule::hasMin).isPresent();
        for (Field field : List.of(Field.MIN, Field.MAX, Field.ORDER)) {
            // Order is greyed out without Min.
            int color = selected.isPresent() && (min || field != Field.ORDER) ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR;
            Component name = CivGui.fit(font, Component.translatable(field.key), FIELD_X - CivGui.PADDING - 2);
            graphics.text(font, name, CivGui.PADDING, STOCK_EDIT_Y + field.ordinal() * EDIT_ROW + 3, color, false);
        }
        graphics.text(font, CivGui.fit(font, Component.translatable(Field.PRIORITY.key), screen.panelWidth() - PRIORITY_X - CivGui.PADDING),
                PRIORITY_X, STOCK_EDIT_Y + 3, min ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR, false);
        if (hovered >= 0) {
            graphics.setComponentTooltipForNextFrame(font, stockTooltip(hovered, active), mouseX, mouseY);
        }
    }

    private List<Component> stockTooltip(int slot, int active) {
        List<Component> lines = new ArrayList<>();
        Optional<StockRule> rule = clerk().rule(slot);
        if (rule.isPresent()) {
            lines.add(new ItemStack(rule.get().item()).getHoverName());
            StockRule found = rule.get();
            // One line per set field.
            if (found.hasMin()) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.min", found.min()).withStyle(ChatFormatting.GRAY));
            }
            if (found.hasMax()) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.max", found.max()).withStyle(ChatFormatting.GRAY));
            }
            if (found.order() != StockRule.NOT_SET) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.order", found.order()).withStyle(ChatFormatting.GRAY));
            }
            if (found.hasMin()) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.priority_value", found.priority()).withStyle(ChatFormatting.GRAY));
            }
            if (clerk().nowhere().contains(slot)) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.surplus_nowhere").withStyle(ChatFormatting.RED));
            }
        } else {
            lines.add(Component.translatable("civmod.gui.clerk.stock.empty_slot"));
        }
        if (slot >= active) {
            lines.add(Component.translatable("civmod.gui.clerk.stock.inactive", slot / StockRule.RULES_PER_LEVEL + 1).withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        return List.of(Component.translatable("civmod.gui.clerk.stock.help.click"), Component.translatable("civmod.gui.clerk.stock.help.delete"),
                Component.translatable("civmod.gui.clerk.stock.help.item"), Component.translatable("civmod.gui.clerk.stock.help.viewer"),
                Component.translatable("civmod.gui.clerk.stock.help.shift"), Component.translatable("civmod.gui.clerk.stock.help.ctrl"),
                Component.translatable("civmod.gui.clerk.stock.help.empty"),
                Component.translatable("civmod.gui.clerk.stock.help.min"), Component.translatable("civmod.gui.clerk.stock.help.max"),
                Component.translatable("civmod.gui.clerk.stock.help.order"));
    }

    // ---------------------------------------------------------------- Workers tab

    /** The Warehouse line, if the Clerk serves or is served by a Warehouse. */
    @Override
    public int workersLines() {
        ClerkTabData tab = clerk();
        return tab.servedBy().isPresent() || !tab.served().isEmpty() ? 1 : 0;
    }

    /**
     * Draws the Warehouse line of the Workers tab: a Warehouse counts its Served Clerks (names on hover), a Served Clerk
     * names its Warehouse.
     */
    @Override
    public int extractWorkers(GuiGraphicsExtractor graphics, int y, int width, int color, int mouseX, int mouseY) {
        Font font = font();
        ClerkTabData tab = clerk();
        if (tab.servedBy().isPresent()) {
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.clerk.served_by", tab.servedBy().get()), width), CivGui.PADDING, y,
                    color, false);
            return y + 11;
        }
        if (tab.served().isEmpty()) {
            return y;
        }
        Component line = Component.translatable("civmod.gui.clerk.served", tab.served().size());
        graphics.text(font, CivGui.fit(font, line, width), CivGui.PADDING, y, color, false);
        if (mouseX >= screen.left() + CivGui.PADDING && mouseX < screen.left() + CivGui.PADDING + Math.min(width, font.width(line))
                && mouseY >= screen.top() + y && mouseY < screen.top() + y + font.lineHeight) {
            graphics.setComponentTooltipForNextFrame(font, tab.served(), mouseX, mouseY);
        }
        return y + 11;
    }
}
