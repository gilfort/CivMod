package com.gilfort.civmod.job;

import net.minecraft.server.level.ServerLevel;

/**
 * A job scan that processes one chunk section per {@link #step} call, run spread over ticks by the
 * {@link ScanScheduler}.
 *
 * @param <R> the result type
 */
public interface SectionScan<R extends ScanResult> {

    /**
     * @return whether the scan is complete
     */
    boolean isDone();

    /**
     * Scans the next chunk section.
     *
     * @param level the level
     */
    void step(ServerLevel level);

    /**
     * @return the (partial until done) result
     */
    R result();

    /**
     * Runs the whole scan at once (tests only).
     *
     * @param level the level
     * @return the result
     */
    default R runAll(ServerLevel level) {
        while (!isDone()) {
            step(level);
        }
        return result();
    }
}
