package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.DiaryEntryType;
import com.gilfort.civmod.chronicle.DiaryEntry;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Chronicle tab of the settler window (vault {@code Systems/Chronicle.md}): the settler's diary in its own words. Entries from
 * before the arrival are listed first under "Before day X", then the others in the order they were written, each with its day. The
 * text is wrapped to the panel and scrolls when it is longer than the window. The name is in the window's header on every tab.
 */
final class ChronicleTabView {

    /** Y of the first line (panel coordinates). */
    private static final int TOP = 22;
    /** Top edge of the list's window: a little above the first line. */
    private static final int VIEW_TOP = TOP - 2;
    private static final int LINE_HEIGHT = 10;
    /** Space between two entries. */
    private static final int ENTRY_GAP = 4;
    /** Gap between the text and the scrollbar. */
    private static final int SCROLLBAR_GAP = 4;

    private final SettlerMenu menu;
    private final ListScroller scroller;
    private List<Line> lines = List.of();
    private int contentHeight;
    private int panelWidth;
    private int panelHeight;

    /** One wrapped line of text. */
    private record Line(FormattedCharSequence text, int y, int color) {
    }

    /**
     * @param screen the settler screen the tab is drawn on
     * @param menu   the settler menu
     */
    ChronicleTabView(SettlerScreen screen, SettlerMenu menu) {
        this.menu = menu;
        this.scroller = new ListScroller(screen);
    }

    /**
     * Wraps the diary to the panel; call whenever the screen is laid out.
     *
     * @param width  the panel width
     * @param height the panel height
     */
    void init(int width, int height) {
        panelWidth = width;
        panelHeight = height;
        Font font = Minecraft.getInstance().font;
        int textWidth = width - 2 * CivGui.PADDING - CivGui.SCROLLBAR_WIDTH - SCROLLBAR_GAP;
        SettlerInfo.Person person = menu.getInfo().person();
        List<Line> built = new ArrayList<>();
        int y = 0;
        if (!person.hasOrigin()) {
            y = append(built, font, Component.translatable("civmod.gui.chronicle.no_origin"), textWidth, CivGui.HINT_COLOR, y);
        }
        List<DiaryEntry> before = person.diary().stream().filter(entry -> type(entry).filter(DiaryEntryType::beforeArrival).isPresent()).toList();
        List<DiaryEntry> since = person.diary().stream().filter(entry -> type(entry).filter(type -> !type.beforeArrival()).isPresent()).toList();
        if (!before.isEmpty()) {
            Component heading = since.isEmpty() ? Component.translatable("civmod.gui.chronicle.before_arrival")
                    : Component.translatable("civmod.gui.chronicle.before_day", since.get(0).day() + 1);
            y = append(built, font, heading, textWidth, CivGui.HINT_COLOR, y);
            for (DiaryEntry entry : before) {
                y = append(built, font, text(entry), textWidth, CivGui.LABEL_COLOR, y);
            }
        }
        for (DiaryEntry entry : since) {
            y = append(built, font, Component.translatable("civmod.gui.chronicle.entry", entry.day() + 1, text(entry)), textWidth, CivGui.LABEL_COLOR, y);
        }
        lines = List.copyOf(built);
        contentHeight = Math.max(0, y - ENTRY_GAP);
    }

    private static Optional<DiaryEntryType> type(DiaryEntry entry) {
        return ModDiaryEntryTypes.REGISTRY.getOptional(entry.type());
    }

    private static Component text(DiaryEntry entry) {
        return type(entry).map(type -> type.text().apply(entry.params())).orElse(Component.empty());
    }

    private static int append(List<Line> into, Font font, Component text, int width, int color, int y) {
        int next = y;
        for (FormattedCharSequence wrapped : font.split(text, width)) {
            into.add(new Line(wrapped, next, color));
            next += LINE_HEIGHT;
        }
        return next + ENTRY_GAP;
    }

    /** Back to the top of the diary. */
    void reset() {
        scroller.reset();
    }

    private int viewBottom() {
        return panelHeight - CivGui.PADDING;
    }

    private int viewHeight() {
        return viewBottom() - VIEW_TOP;
    }

    private int maxOffset() {
        return Math.max(0, contentHeight - (viewBottom() - TOP));
    }

    private int scrollbarX() {
        return panelWidth - CivGui.PADDING - CivGui.SCROLLBAR_WIDTH;
    }

    /**
     * Draws the diary (panel coordinates); what is scrolled out is clipped.
     *
     * @param graphics the graphics extractor
     */
    void extractLabels(GuiGraphicsExtractor graphics) {
        Font font = Minecraft.getInstance().font;
        int offset = scroller.offset(maxOffset());
        graphics.enableScissor(CivGui.PADDING - 2, VIEW_TOP, scrollbarX() - SCROLLBAR_GAP / 2, viewBottom());
        for (Line line : lines) {
            graphics.text(font, line.text(), CivGui.PADDING, TOP + line.y() - offset, line.color(), false);
        }
        graphics.disableScissor();
        scroller.extract(graphics, scrollbarX(), VIEW_TOP, viewHeight(), maxOffset());
    }

    boolean mouseClicked(MouseButtonEvent event) {
        return event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && scroller.press(event.x(), event.y(), scrollbarX(), VIEW_TOP, viewHeight(), maxOffset());
    }

    boolean mouseScrolled(double scrollY) {
        return scroller.wheel(scrollY, maxOffset(), LINE_HEIGHT);
    }

    boolean mouseDragged(MouseButtonEvent event) {
        return scroller.drag(event.y(), VIEW_TOP, viewHeight(), maxOffset());
    }

    boolean mouseReleased() {
        return scroller.release();
    }
}
