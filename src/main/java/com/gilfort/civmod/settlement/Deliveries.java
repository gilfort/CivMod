package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import com.gilfort.civmod.logistics.DeliveryTask;

import net.minecraft.core.BlockPos;

/**
 * The Delivery tasks of a settlement (vault {@code Jobs/Deliveryman.md}), kept in its {@link Settlement}. A pickup is
 * created when a settler delivers into a drop-off of a Resource Collection Job that is then full enough, by the
 * end-of-work sweep, or urgently when the drop-off is full (at most one task per source). A Deliveryman picks the best
 * free task of its Warehouse ({@link #pick}), adds further sources of the same Home Clerk ({@link #batch}) and locks
 * them ({@link #take}); a locked task is freed when the Deliveryman changes job, the day ends or the source is
 * unreachable ({@link #release}, {@link #releaseAll}), and removed once the source is empty ({@link #done}).
 * <p>
 * Pure logic on {@link SettlementData}; facts about the world (how much a source holds) are passed in.
 */
public final class Deliveries {

    /** Score of an urgent task ("Drop-off full"): as much as 10 minutes of waiting. */
    static final double URGENT_SCORE = 600.0;
    /** Score per second of waiting. */
    private static final double SCORE_PER_SECOND = 1.0;
    /** Score lost per block of distance. */
    private static final double SCORE_PER_BLOCK = 1.0;

    private Deliveries() {
    }

    /**
     * Creates a pickup for a source unless it has one; an urgent request makes an existing task urgent.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param source     the drop-off
     * @param jobBlock   the JobBlock whose drop-off it is
     * @param gameTime   the game time now
     * @param urgent     whether the drop-off is full
     * @return whether a new task was created
     */
    public static boolean request(SettlementData data, Settlement settlement, BlockPos source, BlockPos jobBlock, long gameTime, boolean urgent) {
        Optional<DeliveryTask> existing = get(settlement, source);
        if (existing.isPresent()) {
            if (urgent && !existing.get().urgent()) {
                update(data, settlement, source, DeliveryTask::asUrgent);
            }
            return false;
        }
        settlement.mutableDeliveries().add(new DeliveryTask(source.immutable(), jobBlock.immutable(), gameTime, urgent, Optional.empty()));
        data.setDirty();
        return true;
    }

    /**
     * @param settlement a settlement
     * @param source     a drop-off
     * @return the open task of that source
     */
    public static Optional<DeliveryTask> get(Settlement settlement, BlockPos source) {
        return settlement.deliveries().stream().filter(task -> task.source().equals(source)).findFirst();
    }

    /**
     * @param settlement a settlement
     * @param task       a task
     * @return the Clerk JobBlock its items go to: the Home Clerk of its JobBlock
     */
    public static Optional<BlockPos> homeClerk(Settlement settlement, DeliveryTask task) {
        return settlement.block(task.jobBlock()).flatMap(SettlementBlock::homeClerk);
    }

    /**
     * @param settlement a settlement
     * @param task       a task
     * @return the Warehouse whose Deliverymen handle it, empty if none ("No Deliveryman")
     */
    public static Optional<SettlementBlock> warehouseOf(Settlement settlement, DeliveryTask task) {
        return homeClerk(settlement, task).flatMap(clerk -> Warehouses.warehouseOf(settlement, clerk));
    }

    /**
     * @param settlement a settlement
     * @param clerks     Clerk JobBlocks
     * @return the tasks whose items go to one of them, in creation order
     */
    public static List<DeliveryTask> toClerks(Settlement settlement, List<BlockPos> clerks) {
        return settlement.deliveries().stream().filter(task -> homeClerk(settlement, task).filter(clerks::contains).isPresent()).toList();
    }

    /**
     * How good a task is for a Deliveryman: urgency, then waiting time, then distance.
     *
     * @param task     a task
     * @param gameTime the game time now
     * @param from     where the Deliveryman is
     * @return the score, higher first
     */
    public static double score(DeliveryTask task, long gameTime, BlockPos from) {
        double waited = Math.max(0L, gameTime - task.created()) / 20.0;
        return (task.urgent() ? URGENT_SCORE : 0.0) + waited * SCORE_PER_SECOND - Math.sqrt(task.source().distSqr(from)) * SCORE_PER_BLOCK;
    }

    /**
     * The best free task of a Warehouse.
     *
     * @param settlement the settlement
     * @param warehouse  the Deliveryman's Warehouse
     * @param from       where the Deliveryman is
     * @param gameTime   the game time now
     * @param day        the day now (locks of earlier days are over)
     * @param skipped    sources the Deliveryman leaves out (unreachable today)
     * @return the task, empty if there is none ("No pickups")
     */
    public static Optional<DeliveryTask> pick(Settlement settlement, SettlementBlock warehouse, BlockPos from, long gameTime, long day,
            Set<BlockPos> skipped) {
        return free(settlement, warehouse, day, skipped).stream()
                .max(Comparator.comparingDouble(task -> score(task, gameTime, from)));
    }

    /**
     * Further sources for a trip: starting at the first task, the nearest free task of the same Home Clerk next, as
     * long as its estimated stacks still fit the carry capacity.
     *
     * @param settlement the settlement
     * @param warehouse  the Deliveryman's Warehouse
     * @param first      the picked task
     * @param capacity   the carry capacity in stacks
     * @param stacksAt   how many stacks a source holds (estimate)
     * @param day        the day now
     * @param skipped    sources the Deliveryman leaves out
     * @return the trip's tasks, the first one first
     */
    public static List<DeliveryTask> batch(Settlement settlement, SettlementBlock warehouse, DeliveryTask first, int capacity,
            ToIntFunction<BlockPos> stacksAt, long day, Set<BlockPos> skipped) {
        List<DeliveryTask> trip = new ArrayList<>(List.of(first));
        Optional<BlockPos> clerk = homeClerk(settlement, first);
        List<DeliveryTask> candidates = new ArrayList<>(free(settlement, warehouse, day, skipped).stream()
                .filter(task -> !task.source().equals(first.source()) && homeClerk(settlement, task).equals(clerk)).toList());
        int stacks = stacksAt.applyAsInt(first.source());
        while (!candidates.isEmpty()) {
            BlockPos last = trip.get(trip.size() - 1).source();
            DeliveryTask next = candidates.stream().min(Comparator.comparingDouble(task -> task.source().distSqr(last))).orElseThrow();
            int more = stacksAt.applyAsInt(next.source());
            if (stacks + more > capacity) {
                break;
            }
            candidates.remove(next);
            trip.add(next);
            stacks += more;
        }
        return List.copyOf(trip);
    }

    private static List<DeliveryTask> free(Settlement settlement, SettlementBlock warehouse, long day, Set<BlockPos> skipped) {
        List<BlockPos> clerks = Warehouses.handled(settlement, warehouse);
        return toClerks(settlement, clerks).stream()
                .filter(task -> task.takerOn(day).isEmpty() && !skipped.contains(task.source())).toList();
    }

    /**
     * Locks a task to a Deliveryman.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param source     the task's source
     * @param settler    the Deliveryman
     * @param name       its name
     * @param day        the day now
     */
    public static void take(SettlementData data, Settlement settlement, BlockPos source, UUID settler, String name, long day) {
        update(data, settlement, source, task -> task.withTaker(Optional.of(new DeliveryTask.Taker(settler, name, day))));
    }

    /**
     * Frees a task again (it stays open).
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param source     the task's source
     */
    public static void release(SettlementData data, Settlement settlement, BlockPos source) {
        update(data, settlement, source, task -> task.withTaker(Optional.empty()));
    }

    /**
     * Frees every task a Deliveryman holds, in every settlement of the dimension.
     *
     * @param data    the dimension's settlement data
     * @param settler the Deliveryman
     */
    public static void releaseAll(SettlementData data, UUID settler) {
        for (Settlement settlement : data.settlements()) {
            settlement.mutableDeliveries().replaceAll(task -> task.taker().filter(taker -> taker.settler().equals(settler)).isPresent()
                    ? task.withTaker(Optional.empty()) : task);
        }
    }

    /**
     * Removes a task: its source is empty.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param source     the task's source
     */
    public static void done(SettlementData data, Settlement settlement, BlockPos source) {
        if (settlement.mutableDeliveries().removeIf(task -> task.source().equals(source))) {
            data.setDirty();
        }
    }

    private static void update(SettlementData data, Settlement settlement, BlockPos source, UnaryOperator<DeliveryTask> change) {
        settlement.mutableDeliveries().replaceAll(task -> task.source().equals(source) ? change.apply(task) : task);
        data.setDirty();
    }
}
