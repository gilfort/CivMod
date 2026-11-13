package com.gilfort.civmod.job;

/**
 * The time a catch-up hour simulates, handed down to the loops and the work rules: the day time of the hour (at which Clerk
 * access is checked) and the clock span of its work, over which the speed bonuses are averaged. Live play passes
 * {@link #LIVE}, a constant, so nothing is allocated per tick.
 *
 * @param dayTime  the day time of the hour being simulated; Catch-up starts every day at 06:00 at the earliest, when every
 *                 Clerk (up 05:30–06:00) is already on shift
 * @param workFrom the clock value at which the simulated work begins, {@link #NOT_SIMULATING} live
 * @param workTo   the clock value at which it ends
 */
record SimulatedSpan(long dayTime, long workFrom, long workTo) {

    private static final long NOT_SIMULATING = Long.MIN_VALUE;

    /** Live play: no simulated time, the speed bonuses are those of now. */
    static final SimulatedSpan LIVE = new SimulatedSpan(0L, NOT_SIMULATING, NOT_SIMULATING);

    /**
     * @return whether this is catch-up time
     */
    boolean simulated() {
        return workFrom != NOT_SIMULATING;
    }
}
