package com.gilfort.civmod.npc;

import java.util.UUID;

/**
 * A value chosen from a settler and a day: deterministic, the same in live play and catch-up, never saved (vault
 * {@code Systems/NPC Daily Schedule.md}). Used for the daily offset inside a Schedule window; several values per settler
 * and day are told apart by a salt.
 */
public final class SettlerDay {

    private SettlerDay() {
    }

    /**
     * @param settler the settler
     * @param day     the day
     * @param salt    tells several values of the same settler and day apart
     * @param bound   the upper bound (exclusive, positive)
     * @return a value in {@code [0, bound)}, spread evenly over settlers and days
     */
    public static int pick(UUID settler, long day, long salt, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long hash = mix(settler.getMostSignificantBits() ^ mix(settler.getLeastSignificantBits() ^ mix(day ^ mix(salt))));
        return (int) Long.remainderUnsigned(hash, bound);
    }

    /** The SplitMix64 finalizer: every input bit changes about half of the output bits. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
