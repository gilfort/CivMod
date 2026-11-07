package com.gilfort.civmod.api;

import java.util.List;
import java.util.regex.Pattern;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.phys.Vec3;

/**
 * One thing a Service Job does (vault {@code Systems/Job Categories.md}, Service): a time window, a kind, a radius and the
 * effects that happen to the listeners. A {@link ServiceProfile} lists the acts of a Job; the Service loop runs them.
 * <p>
 * Times are ticks of the day as in the game's own day time: 0 is 06:00, 6000 is noon, 12000 is 18:00 and a day has
 * {@value #DAY_TICKS} ticks. {@link #clock} converts a time on the clock. A window never crosses the end of the day.
 * <p>
 * The datapack changes the numbers of an act (window, radius and the numbers of its effects, see
 * {@link ServiceEffect#numbers()}) in {@code data/<namespace>/civmod_jobs/<job>.json} under {@code service_acts}, never
 * the effects themselves.
 *
 * @param id      the name of the act inside its Job: lower case letters, digits and underscores; the datapack addresses the
 *                act by it
 * @param kind    how the act is played
 * @param start   the first tick of the window
 * @param end     the tick at which the window ends (exclusive)
 * @param radius  how far from the act's center a listener may be, in blocks
 * @param effects what happens to the listeners, in order
 */
@ApiStatus.Experimental
public record ServiceAct(String id, Kind kind, int start, int end, double radius, List<ServiceEffect> effects) {

    /** Ticks in a day. */
    public static final int DAY_TICKS = 24000;
    /** The largest radius: the loop looks for listeners in the cube around the center once per act. */
    public static final double MAX_RADIUS = 64.0;

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final int TICKS_PER_HOUR = 1000;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int HOURS_PER_DAY = 24;
    /** The clock hour at which the day time is 0. */
    private static final int DAY_START_HOUR = 6;

    /** How an act is played. */
    public enum Kind {
        /** The settler stands at the Job's place of the day for the whole window. */
        STATIONARY,
        /** The settler walks to working settlers and stops there; run by the Bard's round (vault {@code Systems/Entertainment.md}). */
        ROUND
    }

    /**
     * Checks the numbers.
     *
     * @throws IllegalArgumentException if the id is not lower case letters, digits and underscores, the window is empty or
     *                                  leaves the day, or the radius is negative or above {@value #MAX_RADIUS}
     */
    public ServiceAct {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A Service act id uses a-z, 0-9 and _ only: " + id);
        }
        if (start < 0 || end > DAY_TICKS || start >= end) {
            throw new IllegalArgumentException("The window of act " + id + " must be inside the day and not empty: " + start + ".." + end);
        }
        if (!(radius >= 0.0 && radius <= MAX_RADIUS)) {
            throw new IllegalArgumentException("The radius of act " + id + " must be 0 to " + MAX_RADIUS + " blocks: " + radius);
        }
        effects = List.copyOf(effects);
    }

    /**
     * @param id      the name of the act
     * @param start   the first tick of the window
     * @param end     the tick at which the window ends
     * @param radius  the listener radius in blocks
     * @param effects what happens to the listeners
     * @return an act the settler stands through at the Job's place of the day
     */
    public static ServiceAct stationary(String id, int start, int end, double radius, ServiceEffect... effects) {
        return new ServiceAct(id, Kind.STATIONARY, start, end, radius, List.of(effects));
    }

    /**
     * @param id      the name of the act
     * @param start   the first tick of the window
     * @param end     the tick at which the window ends
     * @param radius  the listener radius in blocks
     * @param effects what happens to the listeners
     * @return a round act; the round itself arrives with the Bard (until then it does nothing)
     */
    public static ServiceAct round(String id, int start, int end, double radius, ServiceEffect... effects) {
        return new ServiceAct(id, Kind.ROUND, start, end, radius, List.of(effects));
    }

    /**
     * @param hour   the hour on the clock, 0 to 23
     * @param minute the minute, 0 to 59
     * @return the tick of the day at that time on the clock (0 is 06:00, 11:00 is 5000, 20:00 is 14000)
     */
    public static int clock(int hour, int minute) {
        if (hour < 0 || hour >= HOURS_PER_DAY || minute < 0 || minute >= MINUTES_PER_HOUR) {
            throw new IllegalArgumentException("Not a time of day: " + hour + ":" + minute);
        }
        return (hour - DAY_START_HOUR + HOURS_PER_DAY) % HOURS_PER_DAY * TICKS_PER_HOUR + minute * TICKS_PER_HOUR / MINUTES_PER_HOUR;
    }

    /**
     * @param dayTime a tick of the day
     * @return whether the window is open then
     */
    public boolean isOpen(long dayTime) {
        return dayTime >= start && dayTime < end;
    }

    /**
     * @param center   the center of the act
     * @param position the position of a possible listener
     * @return whether the position is within the radius (on the edge counts)
     */
    public boolean reaches(Vec3 center, Vec3 position) {
        return center.distanceToSqr(position) <= radius * radius;
    }

    /**
     * @param newStart the first tick of the window
     * @param newEnd   the tick at which the window ends
     * @return the act with that window
     */
    public ServiceAct withWindow(int newStart, int newEnd) {
        return new ServiceAct(id, kind, newStart, newEnd, radius, effects);
    }

    /**
     * @param newRadius the listener radius in blocks
     * @return the act with that radius
     */
    public ServiceAct withRadius(double newRadius) {
        return new ServiceAct(id, kind, start, end, newRadius, effects);
    }

    /**
     * @param newEffects the effects
     * @return the act with those effects
     */
    public ServiceAct withEffects(List<ServiceEffect> newEffects) {
        return new ServiceAct(id, kind, start, end, radius, newEffects);
    }
}
