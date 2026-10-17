package com.gilfort.civmod.npc;

import java.util.UUID;

import com.gilfort.civmod.settlement.DayClock;

/**
 * How a sleeping settler lies: on its left side (~40 %), right side (~40 %) or back (~20 %).
 * <p>
 * Chosen per night from the settler and the clock, nothing saved and nothing synced: every client computes the same
 * pose from the settler's UUID and the overworld clock it already knows. Rendering only.
 */
public enum SleepingPose {
    LEFT_SIDE,
    RIGHT_SIDE,
    BACK;

    /** A night runs from noon to noon (day time 0 = 06:00), so the pose does not change while a settler sleeps. */
    private static final long NIGHT_START = 6000L;
    private static final int LEFT_PERCENT = 40;
    private static final int RIGHT_PERCENT = 40;
    /** Tells the pose apart from the settler's other daily values ({@code Schedule}'s salts are 1 to 3). */
    private static final long SALT = 4L;

    /**
     * @param settler the settler's UUID
     * @param clock   the overworld clock (ticks)
     * @return the pose of the night the clock lies in
     */
    public static SleepingPose of(UUID settler, long clock) {
        long night = DayClock.day(clock - NIGHT_START);
        int roll = SettlerDay.pick(settler, night, SALT, 100);
        if (roll < LEFT_PERCENT) {
            return LEFT_SIDE;
        }
        return roll < LEFT_PERCENT + RIGHT_PERCENT ? RIGHT_SIDE : BACK;
    }
}
