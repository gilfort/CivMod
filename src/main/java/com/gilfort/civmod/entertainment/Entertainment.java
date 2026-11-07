package com.gilfort.civmod.entertainment;

import com.gilfort.civmod.api.Entertain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The Entertainment of one settler (vault {@code Systems/Entertainment.md}), saved with the settler: the value from 0 to
 * {@link EntertainmentRules#MAX}, the day the decay was taken for, the points of the latest day that gave any and the
 * last evening the Tavern paid, the last evening whose record bubble was settled, the last days a round stop and a
 * stage act of a Bard paid and the marks of the conversations. The arithmetic lives in {@link EntertainmentRules}.
 *
 * @param value      the value, 0 to {@link EntertainmentRules#MAX}
 * @param decayedDay the day the decay was last taken for, {@link #NO_DAY} if none
 * @param today      the points added on {@link #todayDay}
 * @param todayDay   the day {@link #today} counts for, {@link #NO_DAY} if none
 * @param tavernDay  the day of the last Tavern evening that paid, {@link #NO_DAY} if none
 * @param recordDay  the day of the last Tavern evening whose record bubble was settled (shown, or not needed), {@link #NO_DAY} if none
 * @param roundDay   the day of the last round stop that paid ({@link Entertain.Cap#ROUND_STOP}), {@link #NO_DAY} if none
 * @param stageDay   the day of the last evening a stage act paid ({@link Entertain.Cap#STAGE_ACT}), {@link #NO_DAY} if none
 * @param talk       the marks of the conversations ({@link Talk#NONE} if none)
 */
public record Entertainment(double value, long decayedDay, double today, long todayDay, long tavernDay, long recordDay, long roundDay,
        long stageDay, Talk talk) {

    /** Marks "no game tick" in {@link Talk#lastTick}. */
    static final long NO_TICK = Long.MIN_VALUE;

    /**
     * The marks of the conversations of one settler (vault {@code Systems/Entertainment.md}, Conversations and greetings):
     * the cooldown runs from the game tick of the last one, the daily limit counts the conversations of the latest day that
     * had any, and the points of that day are kept apart for the Needs tooltip.
     *
     * @param day      the day {@link #count} and {@link #points} count for, {@link #NO_DAY} if none
     * @param count    the conversations held on {@link #day}
     * @param lastTick the game time the last conversation ended, {@link #NO_TICK} if none
     * @param points   the points the conversations gave on {@link #day}
     */
    public record Talk(long day, int count, long lastTick, double points) {

        /** No conversation yet. */
        public static final Talk NONE = new Talk(NO_DAY, 0, NO_TICK, 0.0);

        /** Save codec. */
        public static final Codec<Talk> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter(Talk::day),
                Codec.INT.optionalFieldOf("count", 0).forGetter(Talk::count),
                Codec.LONG.optionalFieldOf("last_tick", NO_TICK).forGetter(Talk::lastTick),
                Codec.DOUBLE.optionalFieldOf("points", 0.0).forGetter(Talk::points)
        ).apply(instance, Talk::new));

        /**
         * @param when a day
         * @return the conversations held on that day (0 if the latest ones are of another day)
         */
        public int on(long when) {
            return day == when ? count : 0;
        }

        /**
         * @param when a day
         * @return the points the conversations gave on that day
         */
        public double pointsOn(long when) {
            return day == when ? points : 0.0;
        }

        /**
         * @param now the game time
         * @return the ticks since the last conversation ended, {@link Long#MAX_VALUE} if there was none
         */
        public long elapsed(long now) {
            return lastTick == NO_TICK ? Long.MAX_VALUE : now - lastTick;
        }
    }

    /** Marks "no day". */
    static final long NO_DAY = Long.MIN_VALUE;
    /** A settler nothing has happened to: 0 points. */
    public static final Entertainment START = new Entertainment(0.0, NO_DAY, 0.0, NO_DAY, NO_DAY, NO_DAY, NO_DAY, NO_DAY, Talk.NONE);

    /** Save codec; a save without Entertainment starts at {@link #START}. */
    public static final Codec<Entertainment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("value", 0.0).forGetter(Entertainment::value),
            Codec.LONG.optionalFieldOf("decayed_day", NO_DAY).forGetter(Entertainment::decayedDay),
            Codec.DOUBLE.optionalFieldOf("today", 0.0).forGetter(Entertainment::today),
            Codec.LONG.optionalFieldOf("today_day", NO_DAY).forGetter(Entertainment::todayDay),
            Codec.LONG.optionalFieldOf("tavern_day", NO_DAY).forGetter(Entertainment::tavernDay),
            Codec.LONG.optionalFieldOf("record_day", NO_DAY).forGetter(Entertainment::recordDay),
            Codec.LONG.optionalFieldOf("round_day", NO_DAY).forGetter(Entertainment::roundDay),
            Codec.LONG.optionalFieldOf("stage_day", NO_DAY).forGetter(Entertainment::stageDay),
            Talk.CODEC.optionalFieldOf("talk", Talk.NONE).forGetter(Entertainment::talk)
    ).apply(instance, Entertainment::new));

    /**
     * Keeps the value in range.
     *
     * @param value      the value
     * @param decayedDay the day the decay was last taken for
     * @param today      the points added on {@code todayDay}
     * @param todayDay   the day {@code today} counts for
     * @param tavernDay  the day of the last Tavern evening that paid
     * @param recordDay  the day of the last Tavern evening whose record bubble was settled
     * @param roundDay   the day of the last round stop that paid
     * @param stageDay   the day of the last evening a stage act paid
     * @param talk       the marks of the conversations
     */
    public Entertainment {
        value = EntertainmentRules.clamp(value);
    }

    /**
     * @param value a value
     * @return an Entertainment with that value, no decay taken yet and no points today
     */
    public static Entertainment of(double value) {
        return new Entertainment(value, NO_DAY, 0.0, NO_DAY, NO_DAY, NO_DAY, NO_DAY, NO_DAY, Talk.NONE);
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
            return new Entertainment(value, day, today, todayDay, tavernDay, recordDay, roundDay, stageDay, talk);
        }
        if (day <= decayedDay) {
            return this;
        }
        return new Entertainment(rules.decayed(value, day - decayedDay), day, today, todayDay, tavernDay, recordDay, roundDay, stageDay, talk);
    }

    /**
     * @param points the points a source gives
     * @param day    the day
     * @return the Entertainment with the points added (never above {@link EntertainmentRules#MAX}); the day counts the points
     *         that were gained, so points above the cap are not listed
     */
    public Entertainment add(double points, long day) {
        double raised = EntertainmentRules.clamp(value + points);
        return new Entertainment(raised, decayedDay, pointsOn(day) + (raised - value), day, tavernDay, recordDay, roundDay, stageDay, talk);
    }

    /**
     * @param day the day of an evening
     * @return whether the Tavern paid this settler on that evening already
     */
    public boolean tavernPaid(long day) {
        return tavernDay == day;
    }

    /**
     * @param points the points of the Tavern evening
     * @param day    the day of the evening
     * @return the Entertainment with the points added ({@link #add}) and the evening marked as paid
     */
    public Entertainment payTavern(double points, long day) {
        Entertainment added = add(points, day);
        return new Entertainment(added.value, added.decayedDay, added.today, added.todayDay, day, recordDay, roundDay, stageDay, talk);
    }

    /**
     * @param cap the kind of act
     * @param day the day
     * @return whether this settler already got the points of that kind of act on the day (never for {@link Entertain.Cap#NONE})
     */
    public boolean heard(Entertain.Cap cap, long day) {
        return switch (cap) {
            case NONE -> false;
            case ROUND_STOP -> roundDay == day;
            case STAGE_ACT -> stageDay == day;
        };
    }

    /**
     * Adds the points of an act once per day and kind of act, however many Bards reach the settler: the first one pays, the
     * others find the day marked.
     *
     * @param points the points
     * @param cap    the kind of act that gives them
     * @param day    the day
     * @return the Entertainment with the points added ({@link #add}) and the day marked for the kind; unchanged if the kind
     *         paid on that day already
     */
    public Entertainment addCapped(double points, Entertain.Cap cap, long day) {
        if (heard(cap, day)) {
            return this;
        }
        Entertainment added = add(points, day);
        return switch (cap) {
            case NONE -> added;
            case ROUND_STOP -> new Entertainment(added.value, added.decayedDay, added.today, added.todayDay, tavernDay, recordDay, day, stageDay, talk);
            case STAGE_ACT -> new Entertainment(added.value, added.decayedDay, added.today, added.todayDay, tavernDay, recordDay, roundDay, day, talk);
        };
    }

    /**
     * @param day the day of an evening
     * @return whether the record bubble of that evening is settled: it was shown, or a jukebox with a disc made it needless
     */
    public boolean recordSettled(long day) {
        return recordDay == day;
    }

    /**
     * @param day the day of the evening
     * @return the Entertainment with the record bubble of that evening marked as settled
     */
    public Entertainment markRecord(long day) {
        return new Entertainment(value, decayedDay, today, todayDay, tavernDay, day, roundDay, stageDay, talk);
    }

    /**
     * A conversation ended: the points are added ({@link #add}) and the conversation is counted for the day and marked with
     * the game time, which starts the cooldown.
     *
     * @param points the points of the conversation
     * @param day    the day
     * @param now    the game time
     * @return the Entertainment after the conversation
     */
    public Entertainment talked(double points, long day, long now) {
        Entertainment added = add(points, day);
        double gained = added.value - value;
        Talk marks = new Talk(day, talk.on(day) + 1, now, talk.pointsOn(day) + gained);
        return new Entertainment(added.value, added.decayedDay, added.today, added.todayDay, added.tavernDay, added.recordDay, added.roundDay,
                added.stageDay, marks);
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
