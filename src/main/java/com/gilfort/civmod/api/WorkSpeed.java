package com.gilfort.civmod.api;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.npc.Settler;

import net.minecraft.world.entity.Mob;

/**
 * Speeds up the work of every settler that listens, for some hours (vault {@code Systems/Entertainment.md}): a timed bonus
 * like the Priest's blessing. It adds to the other speed bonuses (the blessing, Entertainment) instead of multiplying with
 * them. A settler has one such boost at a time: a new one replaces a weaker or ended boost, extends an equal one and is
 * ignored while a stronger one still runs, so listening to several performers never stacks. The datapack numbers are
 * {@code percent} and {@code hours}.
 *
 * @param percent how much faster the settler works, in percent of its base speed
 * @param hours   how long the boost lasts, in in-game hours (1000 ticks)
 */
@ApiStatus.Experimental
public record WorkSpeed(double percent, double hours) implements ServiceEffect {

    /** Ticks in an in-game hour. */
    public static final int TICKS_PER_HOUR = 1000;

    private static final String PERCENT = "percent";
    private static final String HOURS = "hours";

    /**
     * @throws IllegalArgumentException if the percent or the hours are negative
     */
    public WorkSpeed {
        if (!(percent >= 0.0) || !(hours >= 0.0)) {
            throw new IllegalArgumentException("WorkSpeed percent and hours must not be negative: " + percent + ", " + hours);
        }
    }

    /**
     * @return how long the boost lasts, in ticks
     */
    public long ticks() {
        return Math.round(hours * TICKS_PER_HOUR);
    }

    @Override
    public Targets targets() {
        return Targets.SETTLERS;
    }

    @Override
    public void apply(ServiceContext context) {
        for (Mob listener : context.settlers()) {
            if (listener instanceof Settler settler) {
                settler.getWork().startSpeedBoost(percent, context.clock(), context.clock() + ticks());
            }
        }
    }

    @Override
    public Map<String, Double> numbers() {
        return Map.of(PERCENT, percent, HOURS, hours);
    }

    @Override
    public ServiceEffect withNumbers(Map<String, Double> values) {
        return new WorkSpeed(values.getOrDefault(PERCENT, percent), values.getOrDefault(HOURS, hours));
    }
}
