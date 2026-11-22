package com.gilfort.civmod.settlement;

/**
 * The arrival checks of an inn on the day clock (vault {@code Systems/NPC Population.md}): three a day, at day time 0, 6000 and
 * 12000 (06:00, 12:00 and 18:00). A slot is the number of such a check: {@code day * 3 + n}, so a stay of days is a number of
 * slots too. Plain functions over plain values.
 */
public final class InnSlots {

    /** Arrival checks per day. */
    public static final int PER_DAY = 3;
    /** Ticks between two checks (a quarter of the day). */
    public static final long SPACING_TICKS = DayClock.DAY_TICKS / 4;
    /** The number of the morning check within a day. */
    private static final int MORNING = 0;

    private InnSlots() {
    }

    /**
     * @param clock a clock value
     * @return the latest check at or before it; the evening check stays the latest one through the night
     */
    public static long latest(long clock) {
        long inDay = Math.min(PER_DAY - 1, DayClock.dayTime(clock) / SPACING_TICKS);
        return DayClock.day(clock) * PER_DAY + inDay;
    }

    /**
     * @param slot a check
     * @return its day
     */
    public static long day(long slot) {
        return Math.floorDiv(slot, PER_DAY);
    }

    /**
     * @param slot a check
     * @return its day time (0, 6000 or 12000)
     */
    public static long dayTime(long slot) {
        return Math.floorMod(slot, PER_DAY) * SPACING_TICKS;
    }

    /**
     * @param slot a check
     * @return the first morning check at or after it
     */
    public static long nextMorning(long slot) {
        long rest = Math.floorMod(slot, PER_DAY) - MORNING;
        return rest == 0 ? slot : slot + PER_DAY - rest;
    }

    /**
     * @param slot a check
     * @param days days of stay
     * @return the same check that many days later
     */
    public static long after(long slot, int days) {
        return slot + (long) days * PER_DAY;
    }
}
