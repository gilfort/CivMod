package com.gilfort.civmod.job;

/**
 * How many of the needs that count for the reputation of a settlement are met (vault {@code Systems/NPC Population.md}): the
 * needs of one settler, or the sum over all settlers of a settlement.
 *
 * @param met   the needs that are met
 * @param total all the needs that count
 */
public record NeedTally(int met, int total) {

    /** Nothing counted. */
    public static final NeedTally NONE = new NeedTally(0, 0);

    /**
     * @param other another tally
     * @return the two added up
     */
    public NeedTally plus(NeedTally other) {
        return new NeedTally(met + other.met, total + other.total);
    }

    /**
     * @return the share of met needs, 0 when nothing counts
     */
    public double share() {
        return total == 0 ? 0.0 : (double) met / total;
    }
}
