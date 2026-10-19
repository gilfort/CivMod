package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.server.level.ServerLevel;

/**
 * A Resource Collection Job's scan ({@link ResourceCollectionProfile#createScan}) that processes one chunk section per
 * {@link #step} call. CivMod runs it spread over ticks with a fixed budget per tick; a step reads only loaded chunks
 * (skip unloaded ones) and should use the section's block palette to skip sections without relevant blocks.
 *
 * @param <R> the result type
 */
@ApiStatus.Experimental
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
