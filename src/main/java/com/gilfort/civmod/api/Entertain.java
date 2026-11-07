package com.gilfort.civmod.api;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.npc.Settler;

import net.minecraft.world.entity.Mob;

/**
 * Adds points to the Entertainment of every settler that listens (vault {@code Systems/Entertainment.md}); the value
 * stays between 0 and 10. The datapack numbers are {@code points} and {@code percent_per_level}.
 * <p>
 * The points grow with the performer's skill: each level adds {@code percentPerLevel} percent (the Bard: 1 %). A
 * {@link Cap} limits how often one settler can profit from this kind of act, however many performers reach it; without a
 * cap the effect adds every time it is played, and how often an act plays is the act's business (a stationary act
 * plays once a day).
 *
 * @param points          the points each listener gets at skill level 0
 * @param percentPerLevel the percent the points grow per skill level of the performer, 0 or more
 * @param cap             how often one settler can get the points
 */
@ApiStatus.Experimental
public record Entertain(double points, double percentPerLevel, Cap cap) implements ServiceEffect {

    /** How often one settler can get the points of an {@link Entertain}, counted for the kind of act and not for the performer. */
    public enum Cap {
        /** No limit. */
        NONE,
        /** At most once per day from the stops of a round. */
        ROUND_STOP,
        /** At most once per evening from a stage act. */
        STAGE_ACT
    }

    private static final String POINTS = "points";
    private static final String PERCENT_PER_LEVEL = "percent_per_level";
    private static final double PERCENT = 100.0;

    /**
     * @param points the points each listener gets, no growth with the skill and no cap
     */
    public Entertain(double points) {
        this(points, 0.0, Cap.NONE);
    }

    /**
     * @throws IllegalArgumentException if the points or the growth are negative
     */
    public Entertain {
        if (!(points >= 0.0)) {
            throw new IllegalArgumentException("Entertain points must not be negative: " + points);
        }
        if (!(percentPerLevel >= 0.0)) {
            throw new IllegalArgumentException("Entertain percent per level must not be negative: " + percentPerLevel);
        }
    }

    /**
     * @param skill the performer's skill level
     * @return the points each listener gets from a performer of that level
     */
    public double pointsFor(int skill) {
        return points * (1.0 + percentPerLevel / PERCENT * skill);
    }

    @Override
    public Targets targets() {
        return Targets.SETTLERS;
    }

    @Override
    public void apply(ServiceContext context) {
        long day = context.day();
        double given = pointsFor(context.skill());
        for (Mob listener : context.settlers()) {
            if (listener instanceof Settler settler) {
                settler.getWork().entertain(given, cap, day);
            }
        }
    }

    @Override
    public Map<String, Double> numbers() {
        return Map.of(POINTS, points, PERCENT_PER_LEVEL, percentPerLevel);
    }

    @Override
    public ServiceEffect withNumbers(Map<String, Double> values) {
        return new Entertain(values.getOrDefault(POINTS, points), values.getOrDefault(PERCENT_PER_LEVEL, percentPerLevel), cap);
    }
}
