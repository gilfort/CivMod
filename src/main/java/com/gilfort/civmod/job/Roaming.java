package com.gilfort.civmod.job;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * How a settler of a Resource Collection Job moves around while it works (vault {@code Jobs/Lumberjack.md},
 * {@code Jobs/Veggy Farmer.md}). It splits three places that are one for most jobs: the <em>stand target</em> the
 * settler walks to, looks at and hits, the <em>anchor</em> (the work place of the day, which the scan centres on and
 * the occupation belongs to), and the <em>roll place</em> the {@link WorkRules} roll for.
 * <p>
 * One instance lives per work place of the day and settler ({@link Workplace}); it is not saved and starts at the
 * anchor again after a reload. Live work only: the catch-up simulation never roams.
 */
public interface Roaming {

    /**
     * @return the work place of the day: the scan anchor, occupied by the settler
     */
    BlockPos anchor();

    /**
     * @return where the settler walks, looks and swings now
     */
    BlockPos stand();

    /**
     * @return the place the roll context names ({@code Context#poi}); the Lumberjack's double weight sits on its block
     */
    BlockPos rollPlace();

    /**
     * Asked every work tick, so it only looks at the clock and at the stand target.
     *
     * @param level    the level
     * @param gameTime the game time now
     * @return whether {@link #moveOn} may move the settler now
     */
    boolean isDue(ServerLevel level, long gameTime);

    /**
     * Moves the settler if a move is due and a place is found.
     *
     * @param context what a move may look at
     * @return the new stand target if the settler moves now, else empty
     */
    Optional<BlockPos> moveOn(RoamingContext context);

    /**
     * @param place the work place of the day
     * @return the roaming of a job that stands at its work place: stand target, anchor and roll place are the same, it never moves
     */
    static Roaming standingAt(BlockPos place) {
        return new Roaming() {
            @Override
            public BlockPos anchor() {
                return place;
            }

            @Override
            public BlockPos stand() {
                return place;
            }

            @Override
            public BlockPos rollPlace() {
                return place;
            }

            @Override
            public boolean isDue(ServerLevel level, long gameTime) {
                return false;
            }

            @Override
            public Optional<BlockPos> moveOn(RoamingContext context) {
                return Optional.empty();
            }
        };
    }
}
