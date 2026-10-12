package com.gilfort.civmod.settlement;

import java.util.List;

import net.minecraft.util.RandomSource;

/**
 * Palette of settlement colours.
 * <p>
 * Based on the colour-blind safe Okabe–Ito palette, without vermillion (red is reserved for errors and the
 * unconnected marker) and black (invisible as particles at night).
 */
public final class SettlementColors {

    /** Default colour (bluish green), also used for settlements saved before colours existed. */
    public static final int DEFAULT = 0x009E73;

    /** All selectable colours as RGB. */
    public static final List<Integer> PALETTE = List.of(
            DEFAULT,   // bluish green
            0xE69F00,  // orange
            0x56B4E9,  // sky blue
            0xF0E442,  // yellow
            0x0072B2,  // blue
            0xCC79A7); // reddish purple

    private SettlementColors() {
    }

    /**
     * @param random the random source
     * @return a random palette colour
     */
    public static int random(RandomSource random) {
        return PALETTE.get(random.nextInt(PALETTE.size()));
    }
}
