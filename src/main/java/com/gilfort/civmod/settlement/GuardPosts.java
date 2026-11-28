package com.gilfort.civmod.settlement;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.api.JobCategory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/**
 * The rules of a Guard Post (vault {@code Jobs/Guard.md}): which guard point a Guard holds in the current half day, and
 * which points are out of reach. A Guard picks a point at 06:00 and again at 18:30, one that no other Guard of the block
 * holds if there is one, else any; the pick is saved on the JobBlock, so every Guard of the block sees it.
 */
public final class GuardPosts {

    /** The day time of the second pick (18:30; day time 0 = 06:00). */
    public static final long EVENING = 12_500L;

    /** Most guard points a Guard Post holds (the work points limit of every JobBlock). */
    public static final int MAX_POINTS = SettlementBlock.MAX_WORK_POINTS;

    /** What a click with the scroll did to the guard points of a Guard Post. */
    public enum Toggle {
        /** The block is a guard point now, numbered after the points before it. */
        ADDED,
        /** The block was a guard point and is none now; the points after it moved up a number. */
        REMOVED,
        /** The JobBlock is no registered Guard Post. */
        NO_POST,
        /** The block lies outside the settlement. */
        OUTSIDE,
        /** The Guard Post holds {@value GuardPosts#MAX_POINTS} points already. */
        TOO_MANY,
        /** A Guard cannot stand on the block (two blocks of air above a sturdy top). */
        NOT_STANDABLE
    }

    private GuardPosts() {
    }

    /**
     * Sets or removes a guard point: a click on a point removes it (the later points move up a number), a click on any
     * other block inside the settlement adds it with the facing of the click. Containers do not come here: the scroll
     * cycles them as collect and drop-off inventory.
     *
     * @param data      the dimension's settlement data
     * @param post      the bound Guard Post
     * @param target    the clicked position
     * @param standable whether a Guard can stand on the target
     * @param facing    the direction a Guard faces there (reverse of the player's look direction)
     * @return what happened
     */
    public static Toggle togglePoint(SettlementData data, BlockPos post, BlockPos target, boolean standable, Direction facing) {
        Optional<JobBlockSite> site = JobBlockSite.at(data, post);
        if (site.isEmpty() || site.get().block().jobType().category() != JobCategory.GUARD) {
            return Toggle.NO_POST;
        }
        SettlementBlock block = site.get().block();
        BlockPos pos = target.immutable();
        if (block.mutableWorkPoints().removeIf(point -> point.pos().equals(pos))) {
            block.guard().mutableReach().remove(pos);
            block.guard().mutablePicks().values().removeIf(pick -> pick.point().equals(pos));
            data.changed();
            return Toggle.REMOVED;
        }
        if (!JobAssignments.insideBorder(site.get().settlement(), pos)) {
            return Toggle.OUTSIDE;
        }
        if (!standable) {
            return Toggle.NOT_STANDABLE;
        }
        if (block.workPoints().size() >= MAX_POINTS) {
            return Toggle.TOO_MANY;
        }
        block.mutableWorkPoints().add(new WorkPoint(pos, facing));
        data.changed();
        return Toggle.ADDED;
    }

    /**
     * @param day     the day
     * @param dayTime the day time
     * @return the half day: two per day, the first from 06:00 and the second from 18:30
     */
    public static long period(long day, long dayTime) {
        return day * 2 + (dayTime >= EVENING ? 1 : 0);
    }

    /**
     * The guard point a Guard holds now. A pick stays for the whole half day; a new one prefers a point no other Guard of
     * the block holds in the same half day, and never one a Guard found out of reach today.
     *
     * @param data    the dimension's settlement data
     * @param post    the Guard Post JobBlock
     * @param guard   the Guard
     * @param day     the day
     * @param dayTime the day time
     * @param random  random source for a new pick
     * @return the point, empty if the Guard Post has none that can be reached
     */
    public static Optional<WorkPoint> pointOf(SettlementData data, BlockPos post, UUID guard, long day, long dayTime, RandomSource random) {
        Optional<SettlementBlock> found = JobBlockSite.blockAt(data, post);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SettlementBlock block = found.get();
        List<WorkPoint> candidates = block.workPoints().stream().filter(point -> !outOfReachToday(block, point.pos(), day)).toList();
        long period = period(day, dayTime);
        Optional<WorkPoint> kept = block.guard().pickOf(guard).filter(pick -> pick.period() == period)
                .flatMap(pick -> candidates.stream().filter(point -> point.pos().equals(pick.point())).findFirst());
        if (kept.isPresent() || candidates.isEmpty()) {
            return kept;
        }
        WorkPoint chosen = choose(block, candidates, guard, period, random);
        block.guard().mutablePicks().put(guard, new GuardPost.Pick(period, chosen.pos().immutable()));
        prune(block, period);
        data.changed();
        return Optional.of(chosen);
    }

    private static WorkPoint choose(SettlementBlock block, List<WorkPoint> candidates, UUID guard, long period, RandomSource random) {
        Set<BlockPos> held = new HashSet<>();
        block.guard().mutablePicks().forEach((other, pick) -> {
            if (!other.equals(guard) && pick.period() == period) {
                held.add(pick.point());
            }
        });
        List<WorkPoint> free = candidates.stream().filter(point -> !held.contains(point.pos())).toList();
        List<WorkPoint> from = free.isEmpty() ? candidates : free;
        return from.get(random.nextInt(from.size()));
    }

    /** Drops the picks of earlier half days and of settlers that no longer work at the block. */
    private static void prune(SettlementBlock block, long period) {
        block.guard().mutablePicks().entrySet().removeIf(entry -> entry.getValue().period() != period || block.assignee(entry.getKey()).isEmpty());
    }

    private static boolean outOfReachToday(SettlementBlock block, BlockPos point, long day) {
        return block.guard().reachOf(point).filter(reach -> reach.day() == day && reach.unreachable()).isPresent();
    }

    /**
     * @param data  the dimension's settlement data
     * @param post  the Guard Post JobBlock
     * @param point a guard point
     * @param day   the day
     * @return whether a Guard already checked today whether the point can be reached
     */
    public static boolean reachChecked(SettlementData data, BlockPos post, BlockPos point, long day) {
        return JobBlockSite.blockAt(data, post).flatMap(block -> block.guard().reachOf(point)).filter(reach -> reach.day() == day).isPresent();
    }

    /**
     * Records the result of a Guard's path check of a guard point. A changed result is sent to players holding the scroll
     * (the settlement revision changes), which outline an unreachable point in red.
     *
     * @param data        the dimension's settlement data
     * @param post        the Guard Post JobBlock
     * @param point       the guard point
     * @param day         the day of the check
     * @param unreachable whether the path ended short of the point
     */
    public static void recordReach(SettlementData data, BlockPos post, BlockPos point, long day, boolean unreachable) {
        JobBlockSite.blockAt(data, post).ifPresent(block -> {
            boolean before = block.guard().unreachable(point);
            block.guard().mutableReach().put(point.immutable(), new GuardPost.Reach(day, unreachable));
            Set<BlockPos> existing = new HashSet<>(block.workPositions());
            block.guard().mutableReach().keySet().retainAll(existing);
            if (before != unreachable) {
                data.changed();
            }
        });
    }

    /**
     * Sets the mode of a Guard Post.
     *
     * @param data the dimension's settlement data
     * @param post the Guard Post JobBlock
     * @param mode the new mode
     * @return whether the mode changed (false for a block that is no Guard Post or already had it)
     */
    public static boolean setMode(SettlementData data, BlockPos post, GuardMode mode) {
        Optional<SettlementBlock> found = JobBlockSite.blockAt(data, post)
                .filter(block -> block.jobType().category() == JobCategory.GUARD);
        if (found.isEmpty() || found.get().guard().mode() == mode) {
            return false;
        }
        found.get().guard().setMode(mode);
        data.changed();
        return true;
    }
}
