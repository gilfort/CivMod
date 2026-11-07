package com.gilfort.civmod.api;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.npc.Settler;

import net.minecraft.world.entity.Mob;

/**
 * Adds points to the Entertainment of every settler that listens (vault {@code Systems/Entertainment.md}); the value
 * stays between 0 and 10. The datapack number is {@code points}.
 * <p>
 * The effect adds the points every time it is played. How often an act plays is the act's business: a stationary act
 * plays once a day.
 *
 * @param points the points each listener gets
 */
@ApiStatus.Experimental
public record Entertain(double points) implements ServiceEffect {

    private static final String POINTS = "points";

    /**
     * @throws IllegalArgumentException if the points are negative
     */
    public Entertain {
        if (!(points >= 0.0)) {
            throw new IllegalArgumentException("Entertain points must not be negative: " + points);
        }
    }

    @Override
    public Targets targets() {
        return Targets.SETTLERS;
    }

    @Override
    public void apply(ServiceContext context) {
        long day = context.day();
        for (Mob listener : context.settlers()) {
            if (listener instanceof Settler settler) {
                settler.getWork().setEntertainment(settler.getWork().entertainment().add(points, day));
            }
        }
    }

    @Override
    public Map<String, Double> numbers() {
        return Map.of(POINTS, points);
    }

    @Override
    public ServiceEffect withNumbers(Map<String, Double> values) {
        return new Entertain(values.getOrDefault(POINTS, points));
    }
}
