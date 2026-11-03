package com.gilfort.civmod.settlement;

import java.util.UUID;

import com.gilfort.civmod.npc.SettlerDay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.RandomSource;

/**
 * The rules of the Center Block's shrine (vault {@code Jobs/Priest.md}): when it glows each day, how long, and what a
 * prayer rolls. Pure values and arithmetic; the glow state lives in the {@link Settlement}, the praying in the Priest's
 * work loop.
 */
public final class Shrine {

    /** The glow lasts one in-game hour. */
    public static final long GLOW_TICKS = 1000L;
    /** A Priest prays 10 seconds before it rolls. */
    public static final int PRAYER_TICKS = 200;
    /** The Priest stands within this many blocks of the Center Block. */
    public static final double PRAYER_REACH = 3.0;
    /** Earliest start of the glow: 07:00 (day time 0 = 06:00). */
    static final int EARLIEST_START = 1000;
    /** Latest start of the glow: 16:00. */
    static final int LATEST_START = 10000;
    /** Chance of a Settler at Priest level 0. */
    private static final double BASE_CHANCE = 0.05;
    /** Chance added per Priest level. */
    private static final double CHANCE_PER_LEVEL = 0.001;
    /** EXP per roll and JobBlock level, the default of the Priest's {@code baseExp}. */
    public static final int EXP_PER_LEVEL = 15;
    /** Salt of the daily glow time among the other per-day picks of {@link SettlerDay}. */
    private static final long GLOW_SALT = 0x5112E;

    private Shrine() {
    }

    /**
     * What a settlement keeps of its shrine; the glow start itself is computed, never saved.
     *
     * @param endedDay   the day whose glow a failed prayer ended ({@link #NO_DAY} if none)
     * @param chainUntil the clock value until which a successful prayer keeps the shrine glowing (0 = no chain)
     */
    public record State(long endedDay, long chainUntil) {

        /** Nothing ended, no chain. */
        public static final State NONE = new State(NO_DAY, 0L);

        static final Codec<State> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("ended_day", NO_DAY).forGetter(State::endedDay),
                Codec.LONG.optionalFieldOf("chain_until", 0L).forGetter(State::chainUntil)
        ).apply(instance, State::new));
    }

    /** {@link State#endedDay} while no glow was ended. */
    public static final long NO_DAY = Long.MIN_VALUE;

    /**
     * @param settlement the settlement id
     * @param day        the day
     * @return the time of day (0 = 06:00) at which the Center Block starts to glow that day, between 07:00 and 16:00
     */
    public static long glowStart(int settlement, long day) {
        return EARLIEST_START + SettlerDay.pick(new UUID(settlement, 0L), day, GLOW_SALT, LATEST_START - EARLIEST_START + 1);
    }

    /**
     * @param priestLevel the Priest's skill level
     * @return the chance that a prayer brings a Settler: 5 % + 0.1 % per level
     */
    public static double chance(int priestLevel) {
        return BASE_CHANCE + CHANCE_PER_LEVEL * priestLevel;
    }

    /**
     * @param random      the random source
     * @param priestLevel the Priest's skill level
     * @return whether the roll of a prayer succeeds
     */
    public static boolean roll(RandomSource random, int priestLevel) {
        return random.nextDouble() < chance(priestLevel);
    }

    /**
     * The state of the glow at one moment.
     *
     * @param settlement the settlement id
     * @param day        the day
     * @param dayTime    the time of day (0 = 06:00)
     * @param clock      the clock value of that moment
     * @param state      the settlement's shrine state
     * @return whether the Center Block glows
     */
    public static boolean glowing(int settlement, long day, long dayTime, long clock, State state) {
        if (state.endedDay() == day) {
            return false;
        }
        long start = glowStart(settlement, day);
        return dayTime >= start && dayTime < start + GLOW_TICKS || clock < state.chainUntil();
    }
}
