package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * The state of a round act (vault {@code Systems/Entertainment.md}, Bard) for one performer and one day: how many stops it
 * has made, the settler it heads for, the stop it plays now and the wait before it looks again. The Service loop keeps one
 * per JobBlock and day. Times are game ticks, so a round also runs while the day clock stands still.
 * <p>
 * Not saved: a reload starts the day's round again, which the daily cap on the points absorbs.
 */
public final class ServiceRound {

    /** How long a stop lasts: 30 seconds. */
    public static final int STOP_TICKS = 600;
    /** How many targets the performer tries for one stop before it goes back to its place. */
    public static final int MAX_TRIES = 3;
    /** How long the performer waits at its place when nobody works or three targets were empty: about a minute. */
    public static final int RETRY_TICKS = 1200;

    private int stops;
    private int tries;
    private @Nullable UUID target;
    private @Nullable BlockPos spot;
    private long playingSince = -1L;
    private long retryAt;

    /**
     * @return how many stops started today (a stop that found nobody does not count)
     */
    public int stops() {
        return stops;
    }

    /**
     * @param stopsPerDay the number of stops a day (the Stage level)
     * @return whether the performer may start another stop
     */
    public boolean due(int stopsPerDay) {
        return stops < stopsPerDay;
    }

    /**
     * @param now the game time
     * @return whether a stop is being played
     */
    public boolean playing(long now) {
        return spot != null && now - playingSince < STOP_TICKS;
    }

    /**
     * @param now the game time
     * @return whether the performer waits at its place before it looks for a settler again
     */
    public boolean waiting(long now) {
        return now < retryAt;
    }

    /**
     * @return whether the round is under way away from the performer's place: heading for a settler or playing a stop
     */
    public boolean busy() {
        return target != null || spot != null;
    }

    /**
     * @return the settler the performer heads for
     */
    public Optional<UUID> target() {
        return Optional.ofNullable(target);
    }

    /**
     * @return where the stop is played (the performer stays there), if one is going on or just ended
     */
    public Optional<BlockPos> spot() {
        return Optional.ofNullable(spot);
    }

    /**
     * @param settler the settler the performer heads for next
     */
    public void head(UUID settler) {
        target = settler;
    }

    /**
     * No settler works: the performer goes back to its place and looks again after {@link #RETRY_TICKS}.
     *
     * @param now the game time
     */
    public void noOneWorks(long now) {
        target = null;
        tries = 0;
        retryAt = now + RETRY_TICKS;
    }

    /**
     * The performer arrived and found nobody within the radius. The stop is not used up; it tries another settler, and after
     * {@link #MAX_TRIES} tries in a row it waits like {@link #noOneWorks}.
     *
     * @param now the game time
     */
    public void arrivedAlone(long now) {
        target = null;
        tries++;
        if (tries >= MAX_TRIES) {
            noOneWorks(now);
        }
    }

    /**
     * A stop starts: it counts, and the performer plays where it stands.
     *
     * @param now  the game time
     * @param here where the performer plays
     */
    public void start(long now, BlockPos here) {
        stops++;
        tries = 0;
        target = null;
        spot = here.immutable();
        playingSince = now;
    }

    /**
     * The stop is over (or the window of the round closed): the performer leaves it. The count of stops stays.
     */
    public void reset() {
        target = null;
        tries = 0;
        spot = null;
        playingSince = -1L;
    }
}
