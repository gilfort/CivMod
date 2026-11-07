package com.gilfort.civmod.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The timed work speed boost a settler got from a {@code WorkSpeed} effect of a Service act (vault
 * {@code Systems/Entertainment.md}); saved with the settler. It is the per-settler sibling of the settlement's blessing
 * ({@link com.gilfort.civmod.settlement.Bonuses}) and adds to it in {@link SettlerWork#speedFactor}.
 * <p>
 * A settler has one boost at a time ({@link #start}): listening to several performers never stacks. Immutable.
 *
 * @param percent how much faster the settler works, in percent of its base speed (0: none)
 * @param from    the clock value at which the boost begins
 * @param until   the clock value at which it ends
 */
public record SpeedBoost(double percent, long from, long until) {

    /** No boost. */
    public static final SpeedBoost NONE = new SpeedBoost(0.0, 0L, 0L);

    /** Saved form. */
    public static final Codec<SpeedBoost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("percent").forGetter(SpeedBoost::percent),
            Codec.LONG.fieldOf("from").forGetter(SpeedBoost::from),
            Codec.LONG.fieldOf("until").forGetter(SpeedBoost::until)
    ).apply(instance, SpeedBoost::new));

    /**
     * A new boost replaces a weaker or ended one, extends an equal one that runs and is ignored while a stronger one runs.
     *
     * @param newPercent the percent of the new boost
     * @param start      the clock value at which it begins
     * @param end        the clock value at which it ends
     * @return the boost the settler has afterwards
     */
    public SpeedBoost start(double newPercent, long start, long end) {
        if (newPercent <= 0.0 || end <= start) {
            return this;
        }
        boolean running = percent > 0.0 && start >= from && start < until;
        if (!running || newPercent > percent) {
            return new SpeedBoost(newPercent, start, end);
        }
        if (newPercent == percent) {
            return new SpeedBoost(percent, from, Math.max(until, end));
        }
        return this;
    }

    /**
     * @param clock a clock value
     * @return the share the boost adds to the speed factor at that moment: the percent as a fraction while it runs, else 0
     */
    public double share(long clock) {
        return clock >= from && clock < until ? percent / 100.0 : 0.0;
    }

    /**
     * @param spanFrom start of a span (clock value)
     * @param spanTo   end of a span
     * @return the share averaged over the span, weighted by the part of it the boost covers (catch-up)
     */
    public double averageShare(long spanFrom, long spanTo) {
        if (spanTo <= spanFrom) {
            return share(spanFrom);
        }
        double covered = Math.max(0L, Math.min(spanTo, until) - Math.max(spanFrom, from)) / (double) (spanTo - spanFrom);
        return percent / 100.0 * covered;
    }
}
