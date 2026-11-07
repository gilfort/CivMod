package com.gilfort.civmod.entertainment;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The Entertainment of one settler (vault {@code Systems/Entertainment.md}), saved with the settler: the value from 0 to
 * {@link EntertainmentRules#MAX}, the day the decay was taken for, and the points of the latest day that gave any. The
 * arithmetic lives in {@link EntertainmentRules}.
 *
 * @param value      the value, 0 to {@link EntertainmentRules#MAX}
 * @param decayedDay the day the decay was last taken for, {@link #NO_DAY} if none
 * @param today      the points added on {@link #todayDay}
 * @param todayDay   the day {@link #today} counts for, {@link #NO_DAY} if none
 */
public record Entertainment(double value, long decayedDay, double today, long todayDay) {

    /** Marks "no day". */
    static final long NO_DAY = Long.MIN_VALUE;
    /** A settler nothing has happened to: 0 points. */
    public static final Entertainment START = new Entertainment(0.0, NO_DAY, 0.0, NO_DAY);

    /** Save codec; a save without Entertainment starts at {@link #START}. */
    public static final Codec<Entertainment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("value", 0.0).forGetter(Entertainment::value),
            Codec.LONG.optionalFieldOf("decayed_day", NO_DAY).forGetter(Entertainment::decayedDay),
            Codec.DOUBLE.optionalFieldOf("today", 0.0).forGetter(Entertainment::today),
            Codec.LONG.optionalFieldOf("today_day", NO_DAY).forGetter(Entertainment::todayDay)
    ).apply(instance, Entertainment::new));

    /**
     * Keeps the value in range.
     *
     * @param value      the value
     * @param decayedDay the day the decay was last taken for
     * @param today      the points added on {@code todayDay}
     * @param todayDay   the day {@code today} counts for
     */
    public Entertainment {
        value = EntertainmentRules.clamp(value);
    }

    /**
     * @param value a value
     * @return an Entertainment with that value, no decay taken yet and no points today
     */
    public static Entertainment of(double value) {
        return new Entertainment(value, NO_DAY, 0.0, NO_DAY);
    }

    /**
     * Takes the decay of the days since the last time. The first call only marks the day; a day already marked changes
     * nothing, so live play and catch-up can both call this at every check.
     *
     * @param day   the day
     * @param rules the rules
     * @return the Entertainment of that day
     */
    public Entertainment decayed(long day, EntertainmentRules rules) {
        if (decayedDay == NO_DAY) {
            return new Entertainment(value, day, today, todayDay);
        }
        if (day <= decayedDay) {
            return this;
        }
        return new Entertainment(rules.decayed(value, day - decayedDay), day, today, todayDay);
    }

    /**
     * @param points the points a source gives
     * @param day    the day
     * @return the Entertainment with the points added (never above {@link EntertainmentRules#MAX}) and counted for that day
     */
    public Entertainment add(double points, long day) {
        return new Entertainment(value + points, decayedDay, pointsOn(day) + points, day);
    }

    /**
     * @param day a day
     * @return the points added on that day (0 if the latest points are of another day)
     */
    public double pointsOn(long day) {
        return todayDay == day ? today : 0.0;
    }

    /**
     * @param number a value or a number of points
     * @return the number as the Needs tab shows it: rounded to one decimal, a whole number without a decimal point
     */
    public static String format(double number) {
        double tenths = Math.round(number * 10.0) / 10.0;
        return tenths == Math.rint(tenths) ? Long.toString((long) tenths) : Double.toString(tenths);
    }
}
