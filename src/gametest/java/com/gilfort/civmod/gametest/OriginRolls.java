package com.gilfort.civmod.gametest;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

/**
 * Scripted random sources for the origin roll. The roll asks only {@code nextInt(bound)}; the answers are the picks in the order
 * of the draws (see {@code OriginRoller}): gender, first name, upbringing, place, surname type, surname detail, turning point,
 * reason. The class exists only in the GameTest source set.
 */
final class OriginRolls {

    /** The pick of the gender draw that makes a settler male. */
    static final int MALE = 0;
    /** The pick of the gender draw that makes a settler female. */
    static final int FEMALE = 1;

    private OriginRolls() {
    }

    /**
     * @param picks the answer of each draw in order, each taken modulo its bound; the last pick repeats
     * @return a random source that answers {@code nextInt(bound)} with the picks
     */
    static RandomSource picks(int... picks) {
        return new LegacyRandomSource(0L) {
            private int next;

            @Override
            public int nextInt(int bound) {
                return picks[Math.min(next++, picks.length - 1)] % bound;
            }
        };
    }
}
