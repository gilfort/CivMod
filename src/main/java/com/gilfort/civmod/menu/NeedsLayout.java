package com.gilfort.civmod.menu;

import java.util.List;

import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedsReport;

/**
 * Heights of the settler window's Needs tab (vault {@code Systems/GUI.md}): the tiers one below the other, each a header
 * line and its need rows, and how far the list scrolls when it is longer than the window.
 * <p>
 * Lives in common code, not in the client-only screen, so the dedicated GameTest server can check it.
 */
public final class NeedsLayout {

    /** Height of one line of text: a tier header, a need's name, a need's detail. */
    public static final int LINE = 10;
    /** Size of an item icon in a need's row. */
    public static final int ICON = 16;
    /** Space between two tiers. */
    public static final int GAP = 4;

    private NeedsLayout() {
    }

    /**
     * @param row a need
     * @return the height of its row: name, detail and a line of icons if it has any
     */
    public static int rowHeight(NeedsReport.Row row) {
        return 2 * LINE + (row.icons().isEmpty() ? 0 : ICON);
    }

    /**
     * @param rows the needs of one tier
     * @return the height of the tier's section: its header and its rows
     */
    public static int sectionHeight(List<NeedsReport.Row> rows) {
        return LINE + rows.stream().mapToInt(NeedsLayout::rowHeight).sum();
    }

    /**
     * @param needs the needs
     * @param tier  a tier
     * @return the distance of the tier's section from the top of the list, tiers without needs take no room
     */
    public static int sectionTop(NeedsReport needs, int tier) {
        int top = 0;
        for (int lower = 1; lower < tier; lower++) {
            List<NeedsReport.Row> rows = needs.rows(lower);
            if (!rows.isEmpty()) {
                top += sectionHeight(rows) + GAP;
            }
        }
        return top;
    }

    /**
     * @param needs the needs
     * @return the height of the whole list, 0 without needs
     */
    public static int contentHeight(NeedsReport needs) {
        int height = 0;
        for (int tier = 1; tier <= NeedState.MAX_TIER; tier++) {
            List<NeedsReport.Row> rows = needs.rows(tier);
            if (!rows.isEmpty()) {
                height += (height > 0 ? GAP : 0) + sectionHeight(rows);
            }
        }
        return height;
    }

    /**
     * @param contentHeight the height of the whole list
     * @param viewHeight    the height of the window's part that shows it
     * @return how far the list scrolls: the part that does not fit, 0 for a list that fits
     */
    public static int maxOffset(int contentHeight, int viewHeight) {
        return Math.max(0, contentHeight - viewHeight);
    }
}
