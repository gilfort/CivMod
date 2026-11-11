package com.gilfort.civmod.npc;

import java.util.Optional;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The look of the Fisherman's cast on the client (vault {@code Jobs/Fisherman.md}): the rod is cast with a line to a
 * bobber on the water, the bobber floats, dips at the catch and the line is reeled in; after a short pause the next cast
 * follows. Visual only: the server knows the water ({@link Settler#castTarget()}) and the moment of each catch, and this
 * timeline is played by the client from those two signals, so no hook entity exists and nothing ticks per catch.
 * <p>
 * Times are game ticks of the client level.
 */
public final class FishingCast {

    /** Ticks the bobber flies from the hand to the water. */
    public static final int FLIGHT_TICKS = 8;
    /** Ticks the bobber dips after the catch. */
    public static final int DIP_TICKS = 12;
    /** Ticks the line is reeled in after the dip. */
    public static final int REEL_TICKS = 10;
    /** Ticks without a line between the reel-in and the next cast. */
    public static final int PAUSE_TICKS = 10;

    /** How high the bobber arcs above the straight line between hand and water in the cast. */
    private static final double FLIGHT_ARC = 1.0;
    /** How high the bobber arcs while it is reeled in. */
    private static final double REEL_ARC = 0.4;
    /** How deep the bobber sinks at the catch. */
    private static final double DIP_DEPTH = 0.2;
    /** Height and speed of the bobber's slow bobbing on the water. */
    private static final double FLOAT_AMPLITUDE = 0.02;
    private static final double FLOAT_SPEED = 0.3;

    private static final long NEVER = Long.MIN_VALUE;

    /** What the bobber does at a moment. */
    public enum Phase { HIDDEN, FLYING, FLOATING, DIPPING, REELING }

    /**
     * @param phase    what the bobber does
     * @param progress FLYING, DIPPING, REELING: 0 to 1 through the phase; FLOATING: the ticks since the bobber landed
     */
    public record Pose(Phase phase, float progress) {
    }

    private long castAt = NEVER;
    private long caughtAt = NEVER;

    /** The settler casts its line: the bobber flies out from {@code now}. */
    public void start(long now) {
        castAt = now;
        caughtAt = NEVER;
    }

    /** A fish bites at {@code now}: the bobber dips, the line is reeled in and the next cast follows after a pause. */
    public void caught(long now) {
        caughtAt = now;
        castAt = now + DIP_TICKS + REEL_TICKS + PAUSE_TICKS;
    }

    /**
     * @param now the game time, with the partial tick
     * @return what the bobber does now
     */
    public Pose pose(float now) {
        if (castAt == NEVER) {
            return new Pose(Phase.HIDDEN, 0.0F);
        }
        if (now >= castAt) {
            float since = now - castAt;
            return since < FLIGHT_TICKS ? new Pose(Phase.FLYING, since / FLIGHT_TICKS) : new Pose(Phase.FLOATING, since - FLIGHT_TICKS);
        }
        if (caughtAt == NEVER) {
            return new Pose(Phase.HIDDEN, 0.0F);
        }
        float since = now - caughtAt;
        if (since < 0.0F) {
            return new Pose(Phase.FLOATING, 0.0F);
        }
        if (since < DIP_TICKS) {
            return new Pose(Phase.DIPPING, since / DIP_TICKS);
        }
        return since < DIP_TICKS + REEL_TICKS ? new Pose(Phase.REELING, (since - DIP_TICKS) / REEL_TICKS) : new Pose(Phase.HIDDEN, 0.0F);
    }

    /**
     * @param pose  what the bobber does
     * @param hand  where the line leaves the settler
     * @param water the water surface the bobber floats on
     * @return where the bobber is, empty while it is hidden
     */
    public static Optional<Vec3> bobber(Pose pose, Vec3 hand, Vec3 water) {
        float progress = pose.progress();
        return switch (pose.phase()) {
            case HIDDEN -> Optional.empty();
            case FLYING -> Optional.of(hand.lerp(water, progress).add(0.0, FLIGHT_ARC * Mth.sin(progress * Mth.PI), 0.0));
            case FLOATING -> Optional.of(water.add(0.0, FLOAT_AMPLITUDE * Mth.sin((float) (progress * FLOAT_SPEED)), 0.0));
            case DIPPING -> Optional.of(water.add(0.0, -DIP_DEPTH * Mth.sin(progress * Mth.PI), 0.0));
            case REELING -> Optional.of(water.lerp(hand, progress).add(0.0, REEL_ARC * Mth.sin(progress * Mth.PI), 0.0));
        };
    }
}
