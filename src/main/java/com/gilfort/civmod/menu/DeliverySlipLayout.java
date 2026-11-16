package com.gilfort.civmod.menu;

/**
 * Where the parts of the Delivery Slip window sit (panel coordinates). The menu places its slots by it, the screen draws
 * the limit rows by it and the layout GameTest checks that no row overlaps another.
 */
public final class DeliverySlipLayout {

    /** Panel height. */
    public static final int HEIGHT = 218;
    /** Panel y of the "Inventory" label: the vanilla position, {@code imageHeight - 94}. */
    public static final int INVENTORY_LABEL_Y = HEIGHT - 94;
    /** Panel y of the first row of the player inventory slots. */
    public static final int INVENTORY_Y = HEIGHT - 82;
    /** Left edge of the filter slots. */
    public static final int FILTER_X = 8;
    /** Top edge of the filter slots. */
    public static final int FILTER_Y = 38;
    /** Size of a slot. */
    public static final int SLOT_SIZE = 18;
    /** Top of the "fill target up to" row: its text line, the buttons follow below. */
    public static final int FILL_Y = 60;
    /** Top of the "keep at the source" row. */
    public static final int KEEP_Y = 90;
    /** Offset of the step buttons below the text line of their limit. */
    public static final int STEP_ROW_OFFSET = 12;
    /** Height of a step button. */
    public static final int STEP_BUTTON_HEIGHT = 14;

    private DeliverySlipLayout() {
    }
}
