package com.gilfort.civmod.settlement;

import net.minecraft.server.level.ServerLevel;

/**
 * Day and time of day of a clock value (ticks; day time 0 = 06:00). Settlers follow the overworld clock.
 */
public final class DayClock {

    /** Ticks per day. */
    public static final long DAY_TICKS = 24000L;

    private DayClock() {
    }

    /**
     * @param clock a clock value
     * @return its day
     */
    public static long day(long clock) {
        return Math.floorDiv(clock, DAY_TICKS);
    }

    /**
     * @param clock a clock value
     * @return its time of day (0 = 06:00)
     */
    public static long dayTime(long clock) {
        return Math.floorMod(clock, DAY_TICKS);
    }

    /**
     * @param level the level
     * @return the day of the overworld clock now
     */
    public static long day(ServerLevel level) {
        return day(level.getOverworldClockTime());
    }

    /**
     * @param level the level
     * @return the time of day of the overworld clock now
     */
    public static long dayTime(ServerLevel level) {
        return dayTime(level.getOverworldClockTime());
    }
}
