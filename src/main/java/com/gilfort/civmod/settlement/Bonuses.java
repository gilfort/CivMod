package com.gilfort.civmod.settlement;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;

/**
 * The timed bonuses of a settlement (vault {@code Jobs/Priest.md}, blessing): for each {@link Kind} the clock value until
 * which it acts. A kind never stacks: starting it again only extends it. Immutable; the settlement keeps the current value.
 * A further timed bonus is one more {@link Kind}.
 */
public final class Bonuses {

    /** The blessing of a prayer lasts three in-game hours. */
    public static final long BLESSING_TICKS = 3000L;
    /** No bonus. */
    public static final Bonuses NONE = new Bonuses(Map.of());

    static final Codec<Bonuses> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(
            map -> new Bonuses(Map.copyOf(map)), bonuses -> bonuses.until);

    /** The kinds of timed bonus. */
    public enum Kind {
        /** +20 % work speed in all Jobs, from a prayer. */
        WORK_SPEED("work_speed", 1.2);

        private final String id;
        private final double factor;

        Kind(String id, double factor) {
            this.id = id;
            this.factor = factor;
        }

        /**
         * @return the factor the bonus applies while it acts
         */
        public double factor() {
            return factor;
        }
    }

    private final Map<String, Long> until;

    private Bonuses(Map<String, Long> until) {
        this.until = until;
    }

    /**
     * @param kind the bonus
     * @param end  the clock value until which it acts
     * @return the bonuses with that kind running until {@code end}, or as before if it already runs longer
     */
    public Bonuses start(Kind kind, long end) {
        if (end <= until.getOrDefault(kind.id, 0L)) {
            return this;
        }
        Map<String, Long> copy = new HashMap<>(until);
        copy.put(kind.id, end);
        return new Bonuses(Map.copyOf(copy));
    }

    /**
     * @param kind  the bonus
     * @param clock a clock value
     * @return the ticks the bonus still acts at that moment, 0 if it does not
     */
    public long remaining(Kind kind, long clock) {
        return Math.max(0L, until.getOrDefault(kind.id, 0L) - clock);
    }

    /**
     * @param kind the bonus
     * @param from start of a span (clock value)
     * @param to   end of a span
     * @return the factor of the bonus averaged over the span: 1 outside it, {@link Kind#factor} inside, weighted by overlap
     */
    public double averageFactor(Kind kind, long from, long to) {
        long end = until.getOrDefault(kind.id, 0L);
        if (to <= from) {
            return end > from ? kind.factor : 1.0;
        }
        double covered = Math.max(0L, Math.min(to, end) - from) / (double) (to - from);
        return 1.0 + (kind.factor - 1.0) * covered;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Bonuses bonuses && until.equals(bonuses.until);
    }

    @Override
    public int hashCode() {
        return until.hashCode();
    }
}
