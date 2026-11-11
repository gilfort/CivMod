package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * The Lumberjack's change of log (vault {@code Jobs/Lumberjack.md}): every 2 to 4 game hours, by a random timer, he
 * works at another log. With player-set work points he rotates between them (exactly one: he stays); without, the
 * nearest free log of the automatic search that is a visible step away from his current one. The anchor (his first pick
 * of the day) never changes, so there is no rescan, and the roll place is the log he stands at, so the double weight of
 * the work place log moves with him.
 */
final class LogRoaming implements Roaming {

    /** The shortest wait between two changes: 2 game hours. */
    private static final int MIN_WAIT_TICKS = (int) (2 * DayClock.HOUR_TICKS);
    /** The longest wait between two changes: 4 game hours. */
    private static final int MAX_WAIT_TICKS = (int) (4 * DayClock.HOUR_TICKS);
    /** The automatic search skips logs closer than this to the current one (a neighbour on the same trunk is no change). */
    private static final int MIN_STEP = 4;

    private final LumberjackProfile profile;
    private final BlockPos anchor;
    private final RandomSource random;
    private BlockPos stand;
    private long changeAt;

    /**
     * @param profile  the Lumberjack's profile (what a log is, and its automatic search)
     * @param anchor   the work place of the day, where he starts
     * @param gameTime the game time now
     * @param random   the source of the waits
     */
    LogRoaming(LumberjackProfile profile, BlockPos anchor, long gameTime, RandomSource random) {
        this.profile = profile;
        this.anchor = anchor;
        this.random = random;
        this.stand = anchor;
        this.changeAt = gameTime + nextWait();
    }

    @Override
    public BlockPos anchor() {
        return anchor;
    }

    @Override
    public BlockPos stand() {
        return stand;
    }

    @Override
    public BlockPos rollPlace() {
        return stand;
    }

    /** Due by the timer, or at once when the log he moved to is felled (the work place of the day itself is the loop's to replace). */
    @Override
    public boolean isDue(ServerLevel level, long gameTime) {
        return gameTime >= changeAt || !stand.equals(anchor) && !profile.isWorkPlace(level, stand);
    }

    @Override
    public Optional<BlockPos> moveOn(RoamingContext context) {
        if (!isDue(context.level(), context.gameTime())) {
            return Optional.empty();
        }
        boolean logGone = !profile.isWorkPlace(context.level(), stand);
        changeAt = context.gameTime() + nextWait();
        Optional<BlockPos> next = nextLog(context);
        if (next.isEmpty() && logGone) {
            // Nothing else to go to: back to the log of the day, so the next tick does not search again.
            stand = anchor;
            next = profile.isWorkPlace(context.level(), anchor) ? Optional.of(anchor) : Optional.empty();
        }
        next.ifPresent(log -> stand = log);
        return next;
    }

    private int nextWait() {
        return MIN_WAIT_TICKS + random.nextInt(MAX_WAIT_TICKS - MIN_WAIT_TICKS + 1);
    }

    private Optional<BlockPos> nextLog(RoamingContext context) {
        ServerLevel level = context.level();
        List<BlockPos> points = context.site().block().workPoints();
        return points.stream().anyMatch(point -> profile.isWorkPlace(level, point)) ? nextPoint(points, context) : searchLog(context);
    }

    /**
     * The point after the current one in the player's order that still is a log and that no other settler occupies; none
     * if only the current is left.
     */
    private Optional<BlockPos> nextPoint(List<BlockPos> points, RoamingContext context) {
        int from = points.indexOf(stand);
        for (int step = 1; step <= points.size(); step++) {
            BlockPos point = points.get(Math.floorMod(from + step, points.size()));
            if (!point.equals(stand) && profile.isWorkPlace(context.level(), point) && context.free().test(point)) {
                return Optional.of(point);
            }
        }
        return Optional.empty();
    }

    /** One box search around the JobBlock: the nearest free log that is a step away from the current one. */
    private Optional<BlockPos> searchLog(RoamingContext context) {
        BlockPos current = stand;
        return Optional.ofNullable(profile.autoSearch(context.level(), context.site().pos(),
                pos -> pos.distSqr(current) >= MIN_STEP * MIN_STEP && context.free().test(pos)));
    }
}
