package com.gilfort.civmod.gametest;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

/**
 * Scripted random sources for the Mastery roll. One roll of the roll is {@code nextDouble() < chance}, so a hit is
 * {@link #HIT} and a miss is {@link #MISS}. The pick of a saved item asks {@code nextInt}, which always answers 0: the
 * first remaining ingredient is saved first. The class exists only in the GameTest source set.
 */
final class MasteryRolls {

    /** A roll that is below every chance above 0. */
    static final double HIT = 0.0;
    /** A roll that is above the highest chance a test uses. */
    static final double MISS = 0.99;

    private MasteryRolls() {
    }

    /**
     * @param rolls the numbers {@code nextDouble} returns in order; the last one repeats
     * @return a random source that rolls them
     */
    static RandomSource scripted(double... rolls) {
        return new LegacyRandomSource(0L) {
            private int next;

            @Override
            public double nextDouble() {
                return rolls[Math.min(next++, rolls.length - 1)];
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
    }

    /**
     * @param hits how many hits the settler rolls in a craft (the roll after the last one misses); 3 or more is the
     *             default cap, which ends the roll without another one
     * @return a random source that gives that many hits at the first craft and misses at every roll after it
     */
    static RandomSource hits(int hits) {
        double[] rolls = new double[hits + 1];
        for (int i = 0; i < hits; i++) {
            rolls[i] = HIT;
        }
        rolls[hits] = MISS;
        return scripted(rolls);
    }

    /**
     * A source for a craft of gear: {@code hits} hits, then a miss, and every pool draw answers with the next of the picks
     * (the index of the option in the pool, the last one repeats). With no picks every draw takes the first option.
     *
     * @param hits  how many hits the craft has, at most the cap
     * @param picks the index each effect draw takes, one per hit
     * @return the random source
     */
    static RandomSource drawing(int hits, int... picks) {
        double[] rolls = new double[hits + 1];
        for (int i = 0; i < hits; i++) {
            rolls[i] = HIT;
        }
        rolls[hits] = MISS;
        int[] answers = picks.length == 0 ? new int[] {0} : picks;
        return new LegacyRandomSource(0L) {
            private int nextRoll;
            private int nextPick;

            @Override
            public double nextDouble() {
                return rolls[Math.min(nextRoll++, rolls.length - 1)];
            }

            @Override
            public int nextInt(int bound) {
                return answers[Math.min(nextPick++, answers.length - 1)] % bound;
            }
        };
    }

    /** @return a random source that hits at every roll: three hits at every craft */
    static RandomSource always() {
        return scripted(HIT);
    }

    /** @return a random source that misses at every roll */
    static RandomSource never() {
        return scripted(MISS);
    }
}
