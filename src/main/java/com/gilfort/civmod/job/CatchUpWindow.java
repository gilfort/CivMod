package com.gilfort.civmod.job;

import com.gilfort.civmod.job.SettlerWork.CatchUpState;
import com.gilfort.civmod.settlement.DayClock;

/**
 * The missed time of one settler that is waiting to be caught up (vault {@code Systems/Settlement.md}): where it starts and
 * ends, the clock value saved at unload, and whether the catch-up batch knows the settler already. The start moves up as
 * hours are simulated, so a settler unloaded during the run resumes instead of repeating hours.
 */
final class CatchUpWindow {

    private static final long NONE = -1L;

    /** The clock value saved with the settler; read once on the first tick after loading. */
    private long savedClock = NONE;
    private long from = NONE;
    private long to = NONE;
    private boolean registered;

    /**
     * @return whether missed time is waiting
     */
    boolean pending() {
        return from >= 0;
    }

    /**
     * @return whether the settler waits in a catch-up batch
     */
    boolean registered() {
        return registered;
    }

    /** Marks the settler as part of a batch. */
    void register() {
        registered = true;
    }

    /**
     * @param maxDays the longest time that is caught up, in days
     * @return the start of the missed time (the resume point once hours were simulated), capped to {@code maxDays} before its end
     */
    long cappedFrom(long maxDays) {
        return Math.max(from, to - maxDays * DayClock.DAY_TICKS);
    }

    /**
     * @return the end of the missed time
     */
    long to() {
        return to;
    }

    /**
     * Sets the missed time; the settler is not registered for it yet.
     *
     * @param from the clock at which the settler was unloaded
     * @param to   the clock at which it was loaded again
     */
    void set(long from, long to) {
        this.from = from;
        this.to = to;
        registered = false;
    }

    /** Forgets the missed time, as when the settlement is gone. */
    void drop() {
        from = NONE;
    }

    /** Ends the catch-up: nothing is pending and the settler is no longer registered. */
    void clear() {
        from = NONE;
        to = NONE;
        registered = false;
    }

    /**
     * Records that the time up to {@code end} is done.
     *
     * @param end the clock value up to which the settler has been simulated
     */
    void advanceTo(long end) {
        from = Math.max(from, end);
    }

    /**
     * Takes over what was saved with the settler.
     *
     * @param state the saved state
     */
    void restore(CatchUpState state) {
        savedClock = state.activeClock();
        from = state.from();
        to = state.to();
    }

    /**
     * @param clock the clock now, or -1 when the level is not a server level
     * @return what is saved with the settler
     */
    CatchUpState save(long clock) {
        return new CatchUpState(clock, from, to);
    }

    /**
     * The first tick after loading: turns the time since the saved clock into missed time when it is long enough.
     *
     * @param now      the clock now
     * @param minGap   the shortest missed time that is caught up
     * @param homeless whether the settler has no home (nothing to catch up then)
     */
    void resolveSaved(long now, long minGap, boolean homeless) {
        if (savedClock < 0) {
            return;
        }
        if (now - savedClock >= minGap && !homeless) {
            from = from >= 0 ? Math.min(from, savedClock) : savedClock;
            to = now;
        }
        savedClock = NONE;
    }
}
