package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.Job;

import net.minecraft.core.BlockPos;
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
     * @return whether a JobBlock placed there would found a new settlement (outside every exclusion zone)
     */
    public static boolean wouldFound(SettlementData data, BlockPos pos) {
        return nearestConnected(data, pos, (block, distance) -> distance <= SettlementRules.exclusionRadius(block.level())) == null;
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
         * @param settlementId the settlement id, {@code -1} if none
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
         * A settlement was dissolved because its last JobBlock was removed; its NPCs become homeless.
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
        /** The block founded a new settlement. */
        FOUNDED,
        /** The block joined an existing settlement. */
        JOINED,
        /** The block lies in an exclusion zone but out of reach; it waits unconnected. */
        UNCONNECTED
    }

    /**
     * Result of placing a JobBlock.
     *
     * @param settlement the settlement the block belongs to
     * @param outcome    how it was integrated
     * @param distance   for {@link Outcome#UNCONNECTED}: horizontal distance to the nearest connected block
     * @param required   for {@link Outcome#UNCONNECTED}: the distance needed to connect to that block
     */
    public record Placement(Settlement settlement, Outcome outcome, int distance, int required) {
    }

    /**
     * Connection status of a JobBlock, for the GUI.
     *
     * @param settlement the settlement, if any
     * @param connected  whether the block is connected
     * @param distance   distance to the nearest connected block of the settlement ({@code 0} if connected)
     * @param required   distance needed to connect ({@code 0} if connected)
     */
    public record Status(Optional<Settlement> settlement, boolean connected, int distance, int required) {
    }

    private record Nearest(Settlement settlement, SettlementBlock block, double distance) {
    }

    /**
     * Registers a placed JobBlock: joins the settlement with the nearest connected JobBlock whose join radius covers
     * it (the new block's own level does not count), else waits
     * unconnected inside the nearest exclusion zone, else founds a new settlement. Joining connects further
     * waiting blocks of the same settlement that come into reach.
     *
     * @param data     the dimension's settlement data
     * @param pos      the JobBlock position
     * @param jobType  the offered job
     * @param level    the JobBlock level
     * @param gameTime the current game time
     * @param random   random source for the colour of a new settlement
     * @param listener receives side effects
     * @return how the block was integrated
     */
    public static Placement place(SettlementData data, BlockPos pos, Job jobType, int level, long gameTime, RandomSource random,
            Listener listener) {
        data.removeBlock(pos);
        int clamped = SettlementRules.clampLevel(level);

        Nearest join = nearestConnected(data, pos, (block, distance) -> distance <= SettlementRules.joinRadius(block.level()));
        if (join != null) {
            data.addBlock(join.settlement(), new SettlementBlock(pos, jobType, clamped, true));
            listener.blockChanged(pos, join.settlement().id(), true);
            connectChain(data, join.settlement(), listener);
            return new Placement(join.settlement(), Outcome.JOINED, 0, 0);
        }

        Nearest exclusion = nearestConnected(data, pos, (block, distance) -> distance <= SettlementRules.exclusionRadius(block.level()));
        if (exclusion != null) {
            data.addBlock(exclusion.settlement(), new SettlementBlock(pos, jobType, clamped, false));
            listener.blockChanged(pos, exclusion.settlement().id(), false);
            Status status = status(data, pos);
            return new Placement(exclusion.settlement(), Outcome.UNCONNECTED, status.distance(), status.required());
        }

        Settlement settlement = data.create(gameTime, SettlementColors.random(random), gameTime + SETTLER_DELAY_TICKS);
        data.addBlock(settlement, new SettlementBlock(pos, jobType, clamped, true));
        listener.blockChanged(pos, settlement.id(), true);
        return new Placement(settlement, Outcome.FOUNDED, 0, 0);
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
     * Unregisters a removed JobBlock. An empty settlement is dissolved; if only unconnected blocks remain, the
     * first one becomes the new anchor and pulls in all blocks in reach.
     *
     * @param data     the dimension's settlement data
     * @param pos      the removed JobBlock position
     * @param listener receives side effects
     * @return the settlement the block belonged to, if any
     */
    public static Optional<Settlement> remove(SettlementData data, BlockPos pos, Listener listener) {
        List<SettlementBlock.Assignee> assignees = JobBlockSite.at(data, pos).map(site -> site.block().assignees())
                .map(List::copyOf).orElse(List.of());
        Optional<Settlement> removed = data.removeBlock(pos);
        assignees.forEach(assignee -> listener.jobRemoved(assignee.id(), pos));
        removed.ifPresent(settlement -> {
            if (settlement.isEmpty()) {
                data.dissolve(settlement);
                listener.dissolved(settlement);
            } else if (settlement.connectedCount() == 0) {
                SettlementBlock anchor = settlement.blocks().iterator().next();
                anchor.setConnected(true);
                data.changed();
                listener.blockChanged(anchor.pos(), settlement.id(), true);
                connectChain(data, settlement, listener);
            }
        });
        return removed;
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
                        SettlementRules.joinRadius(nearest.level())))
                .orElse(new Status(settlement, false, 0, 0));
    }

    private static void connectChain(SettlementData data, Settlement settlement, Listener listener) {
        boolean changed = true;
        while (changed) {
            changed = false;
            List<SettlementBlock> waiting = new ArrayList<>(settlement.blocks().stream().filter(block -> !block.connected()).toList());
            for (SettlementBlock candidate : waiting) {
                boolean inReach = settlement.blocks().stream().anyMatch(other -> other.connected()
                        && SettlementRules.horizontalDistance(candidate.pos(), other.pos()) <= SettlementRules.joinRadius(other.level()));
                if (inReach) {
                    candidate.setConnected(true);
                    data.changed();
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
        Nearest best = null;
        for (Settlement settlement : data.settlements()) {
            for (SettlementBlock block : settlement.blocks()) {
                if (!block.connected()) {
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
