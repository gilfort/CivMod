package com.gilfort.civmod.job;

/**
 * What one catch-up run produced, for the log line at its end.
 *
 * @param days  how many days were caught up
 * @param items the items delivered
 * @param meals the meals eaten
 */
record CatchUpCounters(double days, int items, int meals) {

    /** No run yet. */
    static final CatchUpCounters NONE = new CatchUpCounters(0.0, 0, 0);

    /**
     * @param days the days the run covers
     * @return the counters at the start of a run
     */
    static CatchUpCounters start(double days) {
        return new CatchUpCounters(days, 0, 0);
    }

    /**
     * @param count the items delivered
     * @return the counters with them added
     */
    CatchUpCounters withItems(int count) {
        return new CatchUpCounters(days, items + count, meals);
    }

    /**
     * @return the counters with one more meal
     */
    CatchUpCounters withMeal() {
        return new CatchUpCounters(days, items, meals + 1);
    }
}
