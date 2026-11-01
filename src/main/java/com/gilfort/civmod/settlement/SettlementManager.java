package com.gilfort.civmod.settlement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.Job;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Settlement rules for placing and removing JobBlocks (vault {@code Systems/Settlement.md}).
 * <p>
 * Pure logic on {@link SettlementData}; world side effects (block entity sync, messages, particles) are reported
 * to a {@link Listener}, which keeps this class testable without a world.
 */
public final class SettlementManager {

    /** Delay between founding and the arrival of the starting settlers. */
    public static final long SETTLER_DELAY_TICKS = 600L;

    private SettlementManager() {
    }

    /**
     * @param data the dimension's settlement data
     * @param pos  a position
     * @return whether a Center Block placed there would found a new settlement (outside every exclusion zone)
     */
    public static boolean wouldFound(SettlementData data, BlockPos pos) {
        return zoneOwner(data, pos) == null;
    }

    /**
     * @param data the dimension's settlement data
     * @param pos  a position
     * @param race the race of a Center Block
     * @return whether a Center Block placed there would be refused: the settlement there already has one of that race
     */
    public static boolean wouldRefuse(SettlementData data, BlockPos pos, Identifier race) {
        Settlement owner = zoneOwner(data, pos);
        return owner != null && owner.hasCenter(race);
    }

    /**
     * Finds the settlement a homeless NPC at the given position can join.
     *
     * @param data     the dimension's settlement data
     * @param pos      the NPC position
     * @param distance the maximum horizontal distance to a connected JobBlock
     * @return the settlement with the nearest connected JobBlock within that distance
     */
    public static Optional<Settlement> settlementNear(SettlementData data, BlockPos pos, double distance) {
        Nearest nearest = nearestConnected(data, pos, (block, d) -> d <= distance);
        return nearest == null ? Optional.empty() : Optional.of(nearest.settlement());
    }

    /**
     * @param settlement a settlement
     * @param pos        a position
     * @return the connected JobBlock of the settlement nearest to the position
     */
    public static Optional<SettlementBlock> nearestBlock(Settlement settlement, BlockPos pos) {
        return settlement.blocks().stream().filter(SettlementBlock::connected)
                .min(Comparator.comparingDouble(block -> SettlementRules.horizontalDistance(pos, block.pos())));
    }

    /**
     * Receives world side effects of settlement changes.
     */
    public interface Listener {

        /**
         * A JobBlock's settlement or connection state changed.
         *
         * @param pos          the block position
         * @param settlementId the settlement id, {@link Settlement#NO_SETTLEMENT} if none
         * @param connected    whether the block is connected
         */
        void blockChanged(BlockPos pos, int settlementId, boolean connected);

        /**
         * A previously unconnected JobBlock got connected automatically.
         *
         * @param pos the block position
         */
        void autoConnected(BlockPos pos);

        /**
         * A settlement was dissolved because its time without a Center Block ran out; its NPCs become homeless.
         *
         * @param settlement the dissolved settlement
         */
        default void dissolved(Settlement settlement) {
        }

        /**
         * A JobBlock with an assigned settler was removed; the settler loses the job.
         *
         * @param settler  the settler UUID
         * @param jobBlock the removed JobBlock position
         */
        default void jobRemoved(UUID settler, BlockPos jobBlock) {
        }

        /** Listener that ignores everything. */
        Listener NONE = new Listener() {
            @Override
            public void blockChanged(BlockPos pos, int settlementId, boolean connected) {
            }

            @Override
            public void autoConnected(BlockPos pos) {
            }
        };
    }

    /** How a placed JobBlock was integrated. */
    public enum Outcome {
        /** The Center Block founded a new settlement. */
        FOUNDED,
        /** The Center Block gave a settlement without one its Center Block back. */
        REVIVED,
        /** The Center Block was not registered: the settlement already has one of that race. */
        REFUSED,
        /** The block joined an existing settlement. */
        JOINED,
        /** The block lies in an exclusion zone but out of reach; it waits unconnected. */
        UNCONNECTED,
        /** The block lies outside every exclusion zone; it belongs to no settlement until one reaches it. */
        UNSETTLED
    }

    /**
     * Result of placing a JobBlock.
     *
     * @param settlement the settlement the block belongs to; for {@link Outcome#REFUSED} the one that refused it, empty
     *                   for {@link Outcome#UNSETTLED}
     * @param outcome    how it was integrated
     * @param distance   for {@link Outcome#UNCONNECTED}: horizontal distance to the nearest connected block
     * @param required   for {@link Outcome#UNCONNECTED}: the distance needed to connect to that block, {@code 0} if the
     *                   settlement has no connected block to reach
     */
    public record Placement(Optional<Settlement> settlement, Outcome outcome, int distance, int required) {

        /**
         * @return for {@link Outcome#UNCONNECTED}: whether the settlement has no connected block to reach
         */
        public boolean nothingToReach() {
            return required == 0;
        }
    }

    /**
     * Connection status of a JobBlock, for the GUI.
     *
     * @param settlement the settlement, if any
     * @param connected  whether the block is connected
     * @param distance   distance to the nearest connected block of the settlement ({@code 0} if connected)
     * @param required   distance needed to connect ({@code 0} if connected or if the settlement has no connected block)
     */
    public record Status(Optional<Settlement> settlement, boolean connected, int distance, int required) {
    }

    private record Nearest(Settlement settlement, SettlementBlock block, double distance) {
    }

    /**
     * Registers a placed JobBlock: joins the settlement with the nearest connected JobBlock whose join radius covers
     * it (the new block's own level does not count), else waits
     * unconnected inside the nearest exclusion zone, else stays without a settlement: only a Center Block founds one
     * ({@link #placeCenter}). Joining connects further waiting blocks that come into reach.
     *
     * @param data     the dimension's settlement data
     * @param pos      the JobBlock position
     * @param jobType  the offered job
     * @param level    the JobBlock level
     * @param listener receives side effects
     * @return how the block was integrated
     */
    public static Placement place(SettlementData data, BlockPos pos, Job jobType, int level, Listener listener) {
        data.removeBlock(pos);
        int clamped = SettlementRules.clampLevel(level);

        Nearest join = nearestConnected(data, pos, (block, distance) -> distance <= SettlementRules.joinRadius(block));
        if (join != null) {
            data.addBlock(join.settlement(), new SettlementBlock(pos, jobType, clamped, true));
            listener.blockChanged(pos, join.settlement().id(), true);
            connectChain(data, join.settlement(), listener);
            return new Placement(Optional.of(join.settlement()), Outcome.JOINED, 0, 0);
        }

        Nearest exclusion = nearestInExclusion(data, pos);
        if (exclusion != null) {
            data.addBlock(exclusion.settlement(), new SettlementBlock(pos, jobType, clamped, false));
            listener.blockChanged(pos, exclusion.settlement().id(), false);
            Status status = status(data, pos);
            return new Placement(Optional.of(exclusion.settlement()), Outcome.UNCONNECTED, status.distance(), status.required());
        }

        data.addUnsettled(new SettlementBlock(pos, jobType, clamped, false));
        listener.blockChanged(pos, Settlement.NO_SETTLEMENT, false);
        return new Placement(Optional.empty(), Outcome.UNSETTLED, 0, 0);
    }

    /**
     * Registers a placed Center Block: outside every exclusion zone it founds a settlement; in the zone of a settlement
     * without a Center Block of its race it joins that settlement, connected, and ends the Center grace of one that had none;
     * otherwise it is refused and not registered. A position already registered as a Center Block stays as it is.
     *
     * @param data     the dimension's settlement data
     * @param pos      the Center Block position
     * @param jobType  the offered job
     * @param level    the JobBlock level
     * @param race     the race of the Center Block
     * @param gameTime the current game time
     * @param random   random source for the colour of a new settlement
     * @param listener receives side effects
     * @return how the block was integrated
     */
    public static Placement placeCenter(SettlementData data, BlockPos pos, Job jobType, int level, Identifier race, long gameTime,
            RandomSource random, Listener listener) {
        Optional<JobBlockSite> registered = JobBlockSite.at(data, pos).filter(site -> site.block().isCenter());
        if (registered.isPresent()) {
            return new Placement(Optional.of(registered.get().settlement()), Outcome.JOINED, 0, 0);
        }
        data.removeBlock(pos);
        SettlementBlock center = SettlementBlock.center(pos, jobType, SettlementRules.clampLevel(level), race);
        Settlement owner = zoneOwner(data, pos);
        if (owner == null) {
            Settlement settlement = data.create(gameTime, SettlementColors.random(random), gameTime + SETTLER_DELAY_TICKS);
            data.addBlock(settlement, center);
            listener.blockChanged(pos, settlement.id(), true);
            connectChain(data, settlement, listener);
            return new Placement(Optional.of(settlement), Outcome.FOUNDED, 0, 0);
        }
        if (owner.hasCenter(race)) {
            return new Placement(Optional.of(owner), Outcome.REFUSED, 0, 0);
        }
        boolean revived = !owner.hasCenter();
        owner.setCenterGrace(Optional.empty());
        data.addBlock(owner, center);
        listener.blockChanged(pos, owner.id(), true);
        recheck(data, owner, listener);
        return new Placement(Optional.of(owner), revived ? Outcome.REVIVED : Outcome.JOINED, 0, 0);
    }

    /**
     * The settlement whose zones cover a position, for a Center Block: the nearest join radius, else the nearest
     * exclusion zone, else the zone a settlement keeps around its last Center Block while it has none.
     */
    private static @Nullable Settlement zoneOwner(SettlementData data, BlockPos pos) {
        Nearest nearest = nearestConnected(data, pos, (block, distance) -> distance <= SettlementRules.joinRadius(block));
        if (nearest == null) {
            nearest = nearestInExclusion(data, pos);
        }
        if (nearest != null) {
            return nearest.settlement();
        }
        return data.settlements().stream()
                .filter(settlement -> settlement.centerGrace().filter(grace -> SettlementRules.horizontalDistance(pos, grace.lastCenter())
                        <= SettlementRules.CENTER_EXCLUSION_RADIUS).isPresent())
                .min(Comparator.comparingDouble(settlement -> SettlementRules.horizontalDistance(pos, settlement.centerGrace().orElseThrow().lastCenter())))
                .orElse(null);
    }

    /**
     * Changes the level of a registered JobBlock. A connected block with a larger radius may connect waiting blocks.
     *
     * @param data     the dimension settlement data
     * @param pos      the JobBlock position
     * @param level    the new level
     * @param listener receives side effects
     */
    public static void setLevel(SettlementData data, BlockPos pos, int level, Listener listener) {
        data.unsettledAt(pos).ifPresent(block -> {
            block.setLevel(level);
            data.changed();
        });
        JobBlockSite.at(data, pos).ifPresent(site -> {
            SettlementBlock block = site.block();
            if (block.level() == SettlementRules.clampLevel(level)) {
                return;
            }
            block.setLevel(level);
            data.changed();
            if (block.connected()) {
                connectChain(data, site.settlement(), listener);
            }
        });
    }

    /**
     * Unregisters a removed JobBlock and rechecks the connection from the center. A settlement left without a Center
     * Block gets its Center grace: it stays for {@value SettlementRules#CENTERLESS_DAYS} days and dissolves on the morning
     * after them ({@link #morning}).
     *
     * @param data     the dimension's settlement data
     * @param pos      the removed JobBlock position
     * @param day      the current settler day
     * @param listener receives side effects
     * @return the settlement the block belonged to, if any
     */
    public static Optional<Settlement> remove(SettlementData data, BlockPos pos, long day, Listener listener) {
        List<SettlementBlock.Assignee> assignees = JobBlockSite.at(data, pos).map(site -> site.block().assignees())
                .map(List::copyOf).orElse(List.of());
        Optional<Settlement> removed = data.removeBlock(pos);
        assignees.forEach(assignee -> listener.jobRemoved(assignee.id(), pos));
        removed.ifPresent(settlement -> {
            if (!settlement.hasCenter() && settlement.centerGrace().isEmpty()) {
                settlement.setCenterGrace(Optional.of(new Settlement.CenterGrace(pos.immutable(), day + SettlementRules.CENTERLESS_DAYS + 1)));
                data.changed();
            }
            recheck(data, settlement, listener);
        });
        return removed;
    }

    /**
     * The morning check of a dimension: settlements whose time without a Center Block has run out dissolve, however
     * many mornings passed. Their JobBlocks stay in the world without a settlement.
     *
     * @param data     the dimension's settlement data
     * @param day      the settler day that began
     * @param listener receives side effects
     */
    public static void morning(SettlementData data, long day, Listener listener) {
        for (Settlement settlement : List.copyOf(data.settlements())) {
            if (settlement.centerGrace().filter(grace -> day >= grace.dissolveDay()).isPresent()) {
                dissolve(data, settlement, listener);
            }
        }
    }

    private static void dissolve(SettlementData data, Settlement settlement, Listener listener) {
        for (SettlementBlock block : List.copyOf(settlement.blocks())) {
            data.removeBlock(block.pos());
            data.addUnsettled(new SettlementBlock(block.pos(), block.jobType(), block.level(), false));
            listener.blockChanged(block.pos(), Settlement.NO_SETTLEMENT, false);
        }
        data.dissolve(settlement);
        listener.dissolved(settlement);
    }

    /**
     * @param data the dimension's settlement data
     * @param pos  a JobBlock position
     * @return the connection status of that JobBlock
     */
    public static Status status(SettlementData data, BlockPos pos) {
        Optional<JobBlockSite> site = JobBlockSite.at(data, pos);
        if (site.isEmpty()) {
            return new Status(Optional.empty(), false, 0, 0);
        }
        Optional<Settlement> settlement = site.map(JobBlockSite::settlement);
        if (site.get().block().connected()) {
            return new Status(settlement, true, 0, 0);
        }
        return settlement.get().blocks().stream()
                .filter(SettlementBlock::connected)
                .min(Comparator.comparingDouble(other -> SettlementRules.horizontalDistance(pos, other.pos())))
                .map(nearest -> new Status(settlement, false,
                        (int) Math.ceil(SettlementRules.horizontalDistance(pos, nearest.pos())),
                        SettlementRules.joinRadius(nearest)))
                .orElse(new Status(settlement, false, 0, 0));
    }

    /**
     * Recomputes a settlement's connection from its Center Blocks with the join rule: blocks no longer chained to one
     * become unconnected, blocks in reach connect. Blocks that stay connected are not reported.
     */
    private static void recheck(SettlementData data, Settlement settlement, Listener listener) {
        Set<BlockPos> chained = chainedToCenter(settlement);
        for (SettlementBlock block : settlement.blocks()) {
            if (block.connected() && !chained.contains(block.pos())) {
                block.setConnected(false);
                data.changed();
                listener.blockChanged(block.pos(), settlement.id(), false);
            }
        }
        connectChain(data, settlement, listener);
    }

    private static Set<BlockPos> chainedToCenter(Settlement settlement) {
        Set<BlockPos> chained = new HashSet<>();
        Deque<SettlementBlock> open = new ArrayDeque<>();
        for (SettlementBlock block : settlement.blocks()) {
            if (block.isCenter()) {
                chained.add(block.pos());
                open.add(block);
            }
        }
        while (!open.isEmpty()) {
            SettlementBlock reacher = open.poll();
            for (SettlementBlock candidate : settlement.blocks()) {
                if (!chained.contains(candidate.pos())
                        && SettlementRules.horizontalDistance(candidate.pos(), reacher.pos()) <= SettlementRules.joinRadius(reacher)) {
                    chained.add(candidate.pos());
                    open.add(candidate);
                }
            }
        }
        return chained;
    }

    /** Connects the waiting blocks of a settlement and the blocks without a settlement that its chain reaches. */
    private static void connectChain(SettlementData data, Settlement settlement, Listener listener) {
        boolean changed = true;
        while (changed) {
            changed = false;
            List<SettlementBlock> waiting = new ArrayList<>(settlement.blocks().stream().filter(block -> !block.connected()).toList());
            for (SettlementBlock candidate : waiting) {
                if (JobAssignments.insideBorder(settlement, candidate.pos())) {
                    candidate.setConnected(true);
                    data.changed();
                    listener.blockChanged(candidate.pos(), settlement.id(), true);
                    listener.autoConnected(candidate.pos());
                    changed = true;
                }
            }
            for (SettlementBlock candidate : List.copyOf(data.unsettled())) {
                if (JobAssignments.insideBorder(settlement, candidate.pos())) {
                    data.settle(settlement, candidate);
                    listener.blockChanged(candidate.pos(), settlement.id(), true);
                    listener.autoConnected(candidate.pos());
                    changed = true;
                }
            }
        }
    }

    @FunctionalInterface
    private interface Reach {
        boolean test(SettlementBlock block, double distance);
    }

    private static @Nullable Nearest nearestConnected(SettlementData data, BlockPos pos, Reach reach) {
        return nearest(data, pos, false, reach);
    }

    /** The nearest block whose exclusion zone covers a position: connected ones, and all of a settlement without a center. */
    private static @Nullable Nearest nearestInExclusion(SettlementData data, BlockPos pos) {
        return nearest(data, pos, true, (block, distance) -> distance <= SettlementRules.exclusionRadius(block));
    }

    private static @Nullable Nearest nearest(SettlementData data, BlockPos pos, boolean allWithoutCenter, Reach reach) {
        Nearest best = null;
        for (Settlement settlement : data.settlements()) {
            boolean everyBlock = allWithoutCenter && !settlement.hasCenter();
            for (SettlementBlock block : settlement.blocks()) {
                if (!everyBlock && !block.connected()) {
                    continue;
                }
                double distance = SettlementRules.horizontalDistance(pos, block.pos());
                if (reach.test(block, distance) && (best == null || distance < best.distance())) {
                    best = new Nearest(settlement, block, distance);
                }
            }
        }
        return best;
    }
}
