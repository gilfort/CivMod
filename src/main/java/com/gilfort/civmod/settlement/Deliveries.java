package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * The Delivery tasks of a settlement (vault {@code Jobs/Deliveryman.md}), kept in its {@link Settlement}. A pickup is
 * created when a settler delivers into a drop-off of a Resource Collection Job that is then full enough, by the
 * end-of-work sweep, or urgently when the drop-off is full (at most one task per source). A result delivery is posted
 * by a producer on "Deliveryman" that put a result into its drop-off ({@link #postResult}); it counts as in delivery for
 * the requesting Clerk ({@link #inDelivery}) until it arrives there. An input delivery is posted by a producer on
 * "Deliveryman" for the inputs of a round a store holds ({@link #postInput}); taking it reserves them there, picking them
 * up consumes the reservation ({@link #pickUpInput}). A surplus task is an input delivery from a Served Clerk into its
 * Warehouse's store for the stock above a stock rule's Max ({@link #setSurplus}). A Deliveryman picks the best free task of its
 * Warehouse ({@link #pick}), adds further tasks to the same destination ({@link #batch}) and locks them ({@link #take});
 * a locked task is freed when the Deliveryman changes job, the day ends or the source is unreachable ({@link #release},
 * {@link #releaseAll}), and removed once the source is empty ({@link #done}). A picked-up result stays carried
 * ({@link #carry}) until it is unloaded ({@link #arrived}).
 * <p>
 * Routing of a result ({@link #destination}): straight to the requesting Clerk when the producer's Warehouse serves it,
 * otherwise into the producer's Home Clerk, where it waits (relayed) for the requesting Clerk's Warehouse.
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
                update(data, settlement, existing.get().id(), DeliveryTask::asUrgent);
            }
            return false;
        }
        settlement.mutableDeliveries().add(new DeliveryTask(source.immutable(), jobBlock.immutable(), gameTime, urgent, Optional.empty()));
        data.setDirty();
        return true;
    }

    /**
     * Posts a result delivery: a producer put {@code count} of {@code item} into its drop-off for a Clerk's request. A
     * result of the same kind still waiting at that drop-off for that Clerk grows instead.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param source     the producer's drop-off
     * @param jobBlock   the producer's JobBlock
     * @param item       the result
     * @param count      how many were put in
     * @param clerk      the requesting Clerk JobBlock
     * @param gameTime   the game time now
     */
    public static void postResult(SettlementData data, Settlement settlement, BlockPos source, BlockPos jobBlock, Item item, int count,
            BlockPos clerk, long gameTime) {
        if (count <= 0) {
            return;
        }
        Optional<DeliveryTask> waiting = settlement.deliveries().stream()
                .filter(task -> task.source().equals(source) && !task.carried() && task.result()
                        .filter(result -> !result.relayed() && result.item() == item && result.clerk().equals(clerk)).isPresent())
                .findFirst();
        if (waiting.isPresent()) {
            DeliveryTask.Result result = waiting.get().result().orElseThrow();
            update(data, settlement, waiting.get().id(), task -> task.withResult(
                    new DeliveryTask.Result(item, result.count() + count, clerk, false, Optional.empty())));
            return;
        }
        settlement.mutableDeliveries().add(new DeliveryTask(UUID.randomUUID(), source.immutable(), jobBlock.immutable(), gameTime, false,
                Optional.of(new DeliveryTask.Result(item, count, clerk.immutable(), false, Optional.empty())), Optional.empty(), Optional.empty()));
        data.setDirty();
    }

    /**
     * Posts an input delivery: a producer on "Deliveryman" needs inputs of a round that a store holds, carried into its
     * collect inventory. The amounts are reserved at the store once a Deliveryman takes the task ({@link #take}).
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param store      the Clerk JobBlock holding the inputs
     * @param jobBlock   the producer's JobBlock
     * @param to         the producer's collect inventory
     * @param items      the inputs, by item
     * @param gameTime   the game time now
     * @return the new task's id
     */
    public static UUID postInput(SettlementData data, Settlement settlement, BlockPos store, BlockPos jobBlock, BlockPos to,
            Map<Item, Integer> items, long gameTime) {
        UUID id = UUID.randomUUID();
        settlement.mutableDeliveries().add(new DeliveryTask(id, store.immutable(), jobBlock.immutable(), gameTime, false, Optional.empty(),
                Optional.of(new DeliveryTask.Input(items, to.immutable(), Optional.empty())), Optional.empty()));
        data.setDirty();
        return id;
    }

    /**
     * Sets the surplus of an item at a Served Clerk that waits for a Deliveryman of its Warehouse: the free surplus task
     * of that Clerk and item (not taken today, not carried) is created, resized or removed; taken ones stay as they are
     * (their items are reserved and no longer counted as stock).
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param clerk      the Served Clerk JobBlock holding the surplus
     * @param warehouse  where it goes: the Warehouse's store
     * @param item       the item
     * @param count      how many should go (0 or less: none)
     * @param gameTime   the game time now
     * @param day        the day now (a lock of an earlier day is over)
     */
    public static void setSurplus(SettlementData data, Settlement settlement, BlockPos clerk, BlockPos warehouse, Item item, int count,
            long gameTime, long day) {
        Optional<DeliveryTask> free = freeSurplus(settlement, clerk, item, day);
        if (free.isPresent()) {
            if (count <= 0) {
                done(data, settlement, free.get().id());
            } else if (free.get().input().orElseThrow().count() != count || !free.get().input().orElseThrow().to().equals(warehouse)) {
                update(data, settlement, free.get().id(), task -> task.withInput(new DeliveryTask.Input(Map.of(item, count), warehouse.immutable(),
                        Optional.empty())).withTaker(Optional.empty()));
            }
            return;
        }
        if (count > 0) {
            settlement.mutableDeliveries().add(new DeliveryTask(UUID.randomUUID(), clerk.immutable(), clerk.immutable(), gameTime, false,
                    Optional.empty(), Optional.of(new DeliveryTask.Input(Map.of(item, count), warehouse.immutable(), Optional.empty())),
                    Optional.empty()));
            data.setDirty();
        }
    }

    /**
     * @param settlement a settlement
     * @param clerk      a Clerk JobBlock
     * @param item       an item
     * @param day        the day now
     * @return the surplus task of that Clerk and item no Deliveryman has taken today
     */
    public static Optional<DeliveryTask> freeSurplus(Settlement settlement, BlockPos clerk, Item item, long day) {
        return settlement.deliveries().stream().filter(task -> task.surplus() && task.source().equals(clerk) && !task.carried()
                && task.takerOn(day).isEmpty() && task.input().orElseThrow().items().containsKey(item)).findFirst();
    }

    /**
     * @param settlement a settlement
     * @param warehouse  a Warehouse
     * @param item       an item
     * @return how many of it surplus tasks bring into that Warehouse's store
     */
    static int surplusTo(Settlement settlement, BlockPos warehouse, Item item) {
        return settlement.deliveries().stream().filter(DeliveryTask::surplus).flatMap(task -> task.input().stream())
                .filter(input -> input.to().equals(warehouse)).mapToInt(input -> input.items().getOrDefault(item, 0)).sum();
    }

    /**
     * @param settlement a settlement
     * @param source     a drop-off
     * @return the open pickup of that source
     */
    public static Optional<DeliveryTask> get(Settlement settlement, BlockPos source) {
        return settlement.deliveries().stream().filter(task -> task.pickup() && task.source().equals(source)).findFirst();
    }

    /**
     * @param settlement a settlement
     * @param id         a task id
     * @return that task, if it is still open
     */
    public static Optional<DeliveryTask> get(Settlement settlement, UUID id) {
        return settlement.deliveries().stream().filter(task -> task.id().equals(id)).findFirst();
    }

    /**
     * @param settlement a settlement
     * @param task       a task
     * @return the Home Clerk of its JobBlock (a pickup's target)
     */
    public static Optional<BlockPos> homeClerk(Settlement settlement, DeliveryTask task) {
        return settlement.block(task.jobBlock()).flatMap(SettlementBlock::homeClerk);
    }

    /**
     * @param settlement a settlement
     * @param task       a task
     * @return the Clerk whose Warehouse handles it: the requesting Clerk for a relayed result, else the Home Clerk of
     *         its JobBlock (the producer's for an input delivery)
     */
    static Optional<BlockPos> handler(Settlement settlement, DeliveryTask task) {
        Optional<DeliveryTask.Result> relayed = task.result().filter(DeliveryTask.Result::relayed);
        return relayed.isPresent() ? relayed.map(DeliveryTask.Result::clerk) : homeClerk(settlement, task);
    }

    /**
     * @param settlement a settlement
     * @param task       a task
     * @return the Warehouse whose Deliverymen handle it, empty if none ("No Deliveryman")
     */
    public static Optional<SettlementBlock> warehouseOf(Settlement settlement, DeliveryTask task) {
        return handler(settlement, task).flatMap(clerk -> Warehouses.warehouseOf(settlement, clerk));
    }

    /**
     * @param settlement a settlement
     * @param task       a task
     * @return whether its Deliverymen are staff at its source: anything but a Clerk JobBlock, or a Clerk their
     *         Warehouse handles (elsewhere they are served at the counter under the access rule)
     */
    public static boolean staff(Settlement settlement, DeliveryTask task) {
        if (settlement.block(task.source()).filter(block -> block.jobType() == ModJobs.CLERK.get()).isEmpty()) {
            return true;
        }
        return warehouseOf(settlement, task).filter(warehouse -> Warehouses.handled(settlement, warehouse).contains(task.source())).isPresent();
    }

    /**
     * Where a task's items are unloaded: a pickup into the Home Clerk; a result into the requesting Clerk if it waits
     * relayed or the producer's Warehouse serves that Clerk, else into the producer's Home Clerk; inputs into the
     * producer's collect inventory.
     *
     * @param settlement a settlement
     * @param task       a task
     * @return the Clerk JobBlock (the collect inventory for inputs), empty while the producer has no Home Clerk
     */
    public static Optional<BlockPos> destination(Settlement settlement, DeliveryTask task) {
        if (task.input().isPresent()) {
            return Optional.of(task.input().get().to());
        }
        Optional<BlockPos> home = homeClerk(settlement, task);
        if (task.result().isEmpty()) {
            return home;
        }
        DeliveryTask.Result result = task.result().get();
        if (result.relayed() || home.filter(result.clerk()::equals).isPresent()) {
            return Optional.of(result.clerk());
        }
        Optional<SettlementBlock> producer = home.flatMap(clerk -> Warehouses.warehouseOf(settlement, clerk));
        boolean served = producer.isPresent() && Warehouses.warehouseOf(settlement, result.clerk()).equals(producer);
        return served ? Optional.of(result.clerk()) : home;
    }

    /**
     * @param settlement a settlement
     * @param clerks     Clerk JobBlocks
     * @return the tasks handled for them or bringing results to them, in creation order (the Deliveries tab)
     */
    public static List<DeliveryTask> toClerks(Settlement settlement, List<BlockPos> clerks) {
        return settlement.deliveries().stream().filter(task -> handler(settlement, task).filter(clerks::contains).isPresent()
                || task.result().filter(result -> clerks.contains(result.clerk())).isPresent()).toList();
    }

    /**
     * @param settlement a settlement
     * @param clerk      a Clerk JobBlock
     * @param item       an item
     * @return how many of it are on their way to that Clerk as results (stock rules count them as there)
     */
    public static int inDelivery(Settlement settlement, BlockPos clerk, Item item) {
        return settlement.deliveries().stream().flatMap(task -> task.result().stream())
                .filter(result -> result.item() == item && result.clerk().equals(clerk)).mapToInt(DeliveryTask.Result::count).sum();
    }

    /**
     * @param settlement a settlement
     * @param source     a drop-off
     * @return the results waiting there for a Deliveryman, by item (a producer must not use them as inputs)
     */
    public static Map<Item, Integer> waitingAt(Settlement settlement, BlockPos source) {
        Map<Item, Integer> waiting = new HashMap<>();
        settlement.deliveries().stream().filter(task -> task.source().equals(source) && !task.carried()).flatMap(task -> task.result().stream())
                .forEach(result -> waiting.merge(result.item(), result.count(), Integer::sum));
        return waiting;
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
     * Further tasks for a trip: starting at the first task, the nearest free task to the same destination next, as long
     * as its estimated stacks still fit the carry capacity.
     *
     * @param settlement the settlement
     * @param warehouse  the Deliveryman's Warehouse
     * @param first      the picked task
     * @param capacity   the carry capacity in stacks
     * @param stacksOf   how many stacks a task would carry (estimate)
     * @param day        the day now
     * @param skipped    sources the Deliveryman leaves out
     * @return the trip's tasks, the first one first
     */
    public static List<DeliveryTask> batch(Settlement settlement, SettlementBlock warehouse, DeliveryTask first, int capacity,
            ToIntFunction<DeliveryTask> stacksOf, long day, Set<BlockPos> skipped) {
        List<DeliveryTask> trip = new ArrayList<>(List.of(first));
        Optional<BlockPos> target = destination(settlement, first);
        List<DeliveryTask> candidates = new ArrayList<>(free(settlement, warehouse, day, skipped).stream()
                .filter(task -> !task.id().equals(first.id()) && destination(settlement, task).equals(target)).toList());
        int stacks = stacksOf.applyAsInt(first);
        while (!candidates.isEmpty()) {
            BlockPos last = trip.get(trip.size() - 1).source();
            DeliveryTask next = candidates.stream().min(Comparator.comparingDouble(task -> task.source().distSqr(last))).orElseThrow();
            int more = stacksOf.applyAsInt(next);
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
        return settlement.deliveries().stream()
                .filter(task -> handler(settlement, task).filter(clerks::contains).isPresent())
                .filter(task -> task.takerOn(day).isEmpty() && !task.carried() && !skipped.contains(task.source())).toList();
    }

    /**
     * Locks the pickup of a source to a Deliveryman.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param source     the task's source
     * @param settler    the Deliveryman
     * @param name       its name
     * @param day        the day now
     */
    public static void take(SettlementData data, Settlement settlement, BlockPos source, UUID settler, String name, long day) {
        get(settlement, source).ifPresent(task -> take(data, settlement, task.id(), settler, name, day));
    }

    /**
     * Locks a task to a Deliveryman; an input delivery reserves its inputs at its store.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the task id
     * @param settler    the Deliveryman
     * @param name       its name
     * @param day        the day now
     */
    public static void take(SettlementData data, Settlement settlement, UUID id, UUID settler, String name, long day) {
        update(data, settlement, id, task -> task.withTaker(Optional.of(new DeliveryTask.Taker(settler, name, day))));
        get(settlement, id).ifPresent(task -> task.input().ifPresent(input -> {
            Reservations.release(settlement, id);
            input.items().forEach((item, count) -> Reservations.reserve(settlement, task.source(), id, item, count, day));
        }));
    }

    /**
     * Frees a task again (it stays open); its reservations are released.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the task id
     */
    public static void release(SettlementData data, Settlement settlement, UUID id) {
        Reservations.release(settlement, id);
        update(data, settlement, id, task -> task.withTaker(Optional.empty()));
    }

    /**
     * Frees every task a Deliveryman holds, in every settlement of the dimension (results it carries stay carried); their
     * reservations are released.
     *
     * @param data    the dimension's settlement data
     * @param settler the Deliveryman
     */
    public static void releaseAll(SettlementData data, UUID settler) {
        for (Settlement settlement : data.settlements()) {
            settlement.deliveries().stream().filter(task -> task.taker().filter(taker -> taker.settler().equals(settler)).isPresent())
                    .forEach(task -> Reservations.release(settlement, task.id()));
            settlement.mutableDeliveries().replaceAll(task -> task.taker().filter(taker -> taker.settler().equals(settler)).isPresent()
                    ? task.withTaker(Optional.empty()) : task);
        }
    }

    /**
     * A Deliveryman picked up a result: it is carried until unloaded; nothing picked up ends the task (the Clerk's
     * stock rule asks again).
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the task id
     * @param settler    the Deliveryman
     * @param count      how many it took (a shortfall at the source shrinks the task)
     */
    public static void carry(SettlementData data, Settlement settlement, UUID id, UUID settler, int count) {
        carry(data, settlement, id, settler, count, 0);
    }

    /**
     * A Deliveryman picked up part of a result: what it took is carried, the rest that did not fit its carry inventory
     * waits at the source as a task of its own.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the task id
     * @param settler    the Deliveryman
     * @param count      how many it took
     * @param left       how many still wait at the source
     */
    public static void carry(SettlementData data, Settlement settlement, UUID id, UUID settler, int count, int left) {
        Optional<DeliveryTask> found = get(settlement, id).filter(task -> task.result().isPresent());
        if (found.isEmpty()) {
            return;
        }
        DeliveryTask task = found.get();
        DeliveryTask.Result result = task.result().get();
        if (left > 0) {
            settlement.mutableDeliveries().add(new DeliveryTask(UUID.randomUUID(), task.source(), task.jobBlock(), task.created(), task.urgent(),
                    Optional.of(new DeliveryTask.Result(result.item(), left, result.clerk(), result.relayed(), Optional.empty())), Optional.empty(),
                    Optional.empty()));
        }
        if (count <= 0) {
            done(data, settlement, id);
            return;
        }
        update(data, settlement, id, changed -> changed.withResult(
                new DeliveryTask.Result(result.item(), count, result.clerk(), result.relayed(), Optional.of(settler))));
    }

    /**
     * The Deliveryman holding an input delivery picks its reserved inputs up at the store: on success they are carried
     * until unloaded; a shortfall puts back what was taken and frees the task for another try (see
     * {@link Reservations#pickUp}).
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the task id
     * @param settler    the Deliveryman
     * @param stock      the store's stock as the task sees it ({@code ClerkStorage.stock(…, task)})
     * @param day        the day now
     * @return what was taken, or that the pickup failed
     */
    public static Reservations.Pickup pickUpInput(SettlementData data, Settlement settlement, UUID id, UUID settler, Stock stock, long day) {
        Optional<DeliveryTask> found = get(settlement, id).filter(task -> task.input().isPresent());
        if (found.isEmpty()) {
            return new Reservations.Pickup(List.of(), true);
        }
        Reservations.Pickup pickup = Reservations.pickUp(data, settlement, found.get().source(), id, stock, day);
        if (!pickup.failed()) {
            DeliveryTask.Input input = found.get().input().get();
            update(data, settlement, id, task -> task.withInput(new DeliveryTask.Input(input.items(), input.to(), Optional.of(settler))));
        }
        return pickup;
    }

    /**
     * Drops the input deliveries of a producer that are not carried yet (its round ended); carried ones still arrive.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param jobBlock   the producer's JobBlock
     */
    public static void dropInputs(SettlementData data, Settlement settlement, BlockPos jobBlock) {
        removeIf(data, settlement, task -> task.input().isPresent() && !task.carried() && task.jobBlock().equals(jobBlock));
    }

    /**
     * @param settlement a settlement
     * @param settler    a Deliveryman
     * @return the results and inputs it carries
     */
    public static List<DeliveryTask> carriedBy(Settlement settlement, UUID settler) {
        return settlement.deliveries().stream().filter(task -> task.carrier().filter(settler::equals).isPresent()).toList();
    }

    /**
     * A Deliveryman unloaded its load into a store: the results it carried for that Clerk and the inputs it carried
     * are done (inputs unloaded elsewhere, e.g. into the Warehouse after a reload, are lost to their round), the other
     * results wait there (relayed) for the requesting Clerk's Warehouse.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param settler    the Deliveryman
     * @param store      the Clerk JobBlock it unloaded into
     */
    public static void arrived(SettlementData data, Settlement settlement, UUID settler, BlockPos store) {
        for (DeliveryTask task : carriedBy(settlement, settler)) {
            if (task.input().isPresent() || task.result().orElseThrow().clerk().equals(store)) {
                done(data, settlement, task.id());
            } else {
                update(data, settlement, task.id(), found -> found.relayedAt(store));
            }
        }
    }

    /**
     * The results and inputs a Deliveryman carries are lost (its load was dropped), in every settlement of the dimension.
     *
     * @param data    the dimension's settlement data
     * @param settler the Deliveryman
     */
    public static void dropCarried(SettlementData data, UUID settler) {
        for (Settlement settlement : data.settlements()) {
            removeIf(data, settlement, task -> task.carrier().filter(settler::equals).isPresent());
        }
    }

    /**
     * Removes the pickup of a source: it is empty.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param source     the task's source
     */
    public static void done(SettlementData data, Settlement settlement, BlockPos source) {
        removeIf(data, settlement, task -> task.pickup() && task.source().equals(source));
    }

    /**
     * Removes a task.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the task id
     */
    public static void done(SettlementData data, Settlement settlement, UUID id) {
        removeIf(data, settlement, task -> task.id().equals(id));
    }

    private static void removeIf(SettlementData data, Settlement settlement, Predicate<DeliveryTask> filter) {
        settlement.deliveries().stream().filter(filter).forEach(task -> Reservations.release(settlement, task.id()));
        if (settlement.mutableDeliveries().removeIf(filter)) {
            data.setDirty();
        }
    }

    private static void update(SettlementData data, Settlement settlement, UUID id, UnaryOperator<DeliveryTask> change) {
        settlement.mutableDeliveries().replaceAll(task -> task.id().equals(id) ? change.apply(task) : task);
        data.setDirty();
    }
}
