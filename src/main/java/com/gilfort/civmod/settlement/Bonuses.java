package com.gilfort.civmod.settlement;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The timed bonuses of a settlement (vault {@code Jobs/Priest.md}, blessing): for each {@link Kind} the clock span in which
 * it acts. A kind never stacks: starting it again inside or right after its span only extends it. Immutable; the
 * settlement keeps the current value. A further timed bonus is one more {@link Kind}.
 */
public final class Bonuses {

    /** The blessing of a prayer lasts three in-game hours. */
    public static final long BLESSING_TICKS = 3000L;
    /** No bonus. */
    public static final Bonuses NONE = new Bonuses(Map.of());

    static final Codec<Bonuses> CODEC = Codec.unboundedMap(Codec.STRING, Span.CODEC).xmap(map -> new Bonuses(Map.copyOf(map)), bonuses -> bonuses.spans);

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

    /**
     * @param from  the clock value at which the bonus begins
     * @param until the clock value at which it ends
     */
    record Span(long from, long until) {

        static final Codec<Span> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("from").forGetter(Span::from),
                Codec.LONG.fieldOf("until").forGetter(Span::until)
        ).apply(instance, Span::new));

        static final Span EMPTY = new Span(0L, 0L);
    }

    private final Map<String, Span> spans;

    private Bonuses(Map<String, Span> spans) {
        this.spans = spans;
    }

    private Span span(Kind kind) {
        return spans.getOrDefault(kind.id, Span.EMPTY);
    }

    /**
     * @param kind the bonus
     * @param from the clock value at which it begins
     * @param end  the clock value at which it ends
     * @return the bonuses with that kind acting from {@code from} until {@code end}; joined with the running span if they
     *         touch, which never shortens it
     */
    public Bonuses start(Kind kind, long from, long end) {
        Span old = span(kind);
        Span joined = from <= old.until() && end >= old.from() ? new Span(Math.min(old.from(), from), Math.max(old.until(), end)) : new Span(from, end);
        if (joined.equals(old)) {
            return this;
        }
        Map<String, Span> copy = new HashMap<>(spans);
        copy.put(kind.id, joined);
        return new Bonuses(Map.copyOf(copy));
    }

    /**
     * @param kind  the bonus
     * @param clock a clock value
     * @return the ticks the bonus still acts at that moment, 0 if it does not (yet or any more)
     */
    public long remaining(Kind kind, long clock) {
        Span span = span(kind);
        return clock >= span.from() ? Math.max(0L, span.until() - clock) : 0L;
    }

    /**
     * @param kind the bonus
     * @param from start of a span (clock value)
     * @param to   end of a span
     * @return the factor of the bonus averaged over the span: 1 outside the bonus, its factor inside, weighted by overlap
     */
    public double averageFactor(Kind kind, long from, long to) {
        if (to <= from) {
            return remaining(kind, from) > 0 ? kind.factor() : 1.0;
        }
        Span span = span(kind);
        double covered = Math.max(0L, Math.min(to, span.until()) - Math.max(from, span.from())) / (double) (to - from);
        return 1.0 + (kind.factor() - 1.0) * covered;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Bonuses bonuses && spans.equals(bonuses.spans);
    }

    @Override
    public int hashCode() {
        return spans.hashCode();
    }
}
