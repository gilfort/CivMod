package com.gilfort.civmod.job;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.logistics.SlipPlan;
import com.gilfort.civmod.logistics.SlipSites;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Reservations;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SlipOrders;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Work loop of the Deliveryman, the second Logistics Job (vault {@code Jobs/Deliveryman.md}), Automatic mode: it empties
 * the drop-offs of Resource Collection Jobs into their Home Clerk's store and carries producers' results to the
 * requesting Clerk (or into the producer's Home Clerk, where the requesting Clerk's Warehouse fetches them) and producers'
 * inputs from a store into their collect inventory (reserved at the store while it has the task). At its
 * decision a free Deliveryman picks the best task of its Warehouse (the Clerk JobBlock it has its place at) and adds
 * further tasks to the same store while its carry inventory has room ({@link Deliveries}); the tasks are locked to it.
 * It walks from source to source – a result waiting at a Clerk its Warehouse does not serve is picked up at the counter
 * under the access rule – then unloads into the whole store (the linked containers in linking order) as staff:
 * no counter, no working Clerk needed; inputs go into the producer's collect inventory. A full store keeps the rest in its
 * carry inventory ("Storage full", retry after 30 s).
 * <p>
 * The carry inventory is the job's buffer (saved with the settler, delivered at the end of work, dropped at the
 * settler when the job is removed), 9 stacks at skill level 1 to 27 at 100. EXP per delivered stack. Catch-up: per
 * simulated hour at most the carry capacity times the trips the distance allows.
 */
final class DeliverymanLoop implements CategoryLoop {

    /** Carry stacks at skill level 1. */
    static final int MIN_CARRY = 9;
    /** Carry stacks at skill level 100. */
    static final int MAX_CARRY = 27;
    /** Walking speed of a settler for the catch-up trip estimate (about 4 blocks per second). */
    static final double WALK_BLOCKS_PER_TICK = 0.2;
    /** Ticks a Deliveryman spends at each stop of a trip (catch-up estimate). */
    static final int STOP_TICKS = 40;
    /** Sources further away count as reachable: a path search is only conclusive within the follow range. */
    private static final double PATH_CHECK = 16.0;

    private final SettlerWork shell;
    private final Settler settler;

    /** The Warehouse of the Deliveryman job seen last (one Deliveryman job per settler). */
    private @Nullable BlockPos warehouse;
    /** Tasks of the current trip still to pick up, locked to this Deliveryman. */
    private final List<UUID> route = new ArrayList<>();
    /** The store the current load goes to (null: the destination of what it carries, else the Warehouse). */
    private @Nullable BlockPos store;
    /** The source whose path was already checked on this walk. */
    private @Nullable BlockPos checked;
    /** The source walked to and how it is reached (a foreign Clerk's counter). */
    private @Nullable BlockPos visiting;
    private ClerkStorage.@Nullable Visit visit;
    /** The stops of the slip trip under way, still to unload at (not saved: a reload sends the load to the Warehouse). */
    private final Deque<SlipPlan.Leg> legs = new ArrayDeque<>();
    /** Sources and slip targets found unreachable today. */
    private final Set<BlockPos> unreachable = new HashSet<>();
    private long unreachableDay = Long.MIN_VALUE;

    /**
     * @param shell the Settler Shell driving this loop
     */
    DeliverymanLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    /**
     * @param skillLevel the Deliveryman's effective skill level
     * @return its carry capacity in stacks: 9 at level 1 growing linearly to 27 at level 100
     */
    static int carryStacks(int skillLevel) {
        return MIN_CARRY + (int) Math.round((Math.clamp(skillLevel, 1, 100) - 1) * (MAX_CARRY - MIN_CARRY) / 99.0);
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        warehouse = job.jobBlock();
        if (unreachableDay != day) {
            unreachable.clear();
            unreachableDay = day;
        }
        Settlement settlement = site.settlement();
        JobBuffer load = shell.workplace(job.jobBlock()).buffer();
        int capacity = carryStacks(shell.effectiveLevel(job));
        route.removeIf(id -> !mine(settlement, id, day));
        if (!load.isEmpty() && (route.isEmpty() || load.stacks().size() >= capacity)) {
            releaseRoute(level, settlement);
            skipUnreachableLeg(level);
            return JobStep.DELIVER;
        }
        while (true) {
            if (route.isEmpty() && !startTrip(level, settlement, site.block(), capacity, day)) {
                return JobStep.skip(JobStatus.NO_PICKUPS);
            }
            DeliveryTask task = Deliveries.get(settlement, route.get(0)).orElseThrow();
            BlockPos source = task.source();
            if (reachable(source)) {
                visiting = source;
                ClerkStorage.Visit next = staff(settlement, job, source) ? new ClerkStorage.Visit(source, SettlerWork.REACH, false)
                        : shell.reachableVisit(level, source, ClerkStorage.visit(level, source, dayTime));
                visit = next;
                return JobStep.walk(next.target(), next.reach(), JobStatus.COLLECTING, sourceName(settlement, task));
            }
            // Unreachable: its tasks are free again, this Deliveryman leaves the source out for the rest of the day.
            unreachable.add(source);
            SettlementData data = SettlementData.get(level);
            for (UUID id : List.copyOf(route)) {
                if (Deliveries.get(settlement, id).filter(found -> found.source().equals(source)).isPresent()) {
                    route.remove(id);
                    Deliveries.release(data, settlement, id);
                }
            }
        }
    }

    /** Picks the best free task and the tasks batched with it, and locks them. */
    private boolean startTrip(ServerLevel level, Settlement settlement, SettlementBlock warehouseBlock, int capacity, long day) {
        SettlementData data = SettlementData.get(level);
        syncSlips(level, data, settlement, warehouseBlock);
        Deliveries.Scope scope = warehouseBlock.orders().auto() ? Deliveries.Scope.ALL : Deliveries.Scope.SLIPS_ONLY;
        Optional<DeliveryTask> first = Deliveries.pick(settlement, warehouseBlock, settler.blockPosition(), level.getGameTime(), day, unreachable, scope);
        if (first.isEmpty()) {
            return false;
        }
        List<DeliveryTask> trip = Deliveries.batch(settlement, warehouseBlock, first.get(), capacity, task -> stacksOf(level, task), day, unreachable);
        for (DeliveryTask task : trip) {
            Deliveries.take(data, settlement, task.id(), settler.getUUID(), settler.getName().getString(), day);
            route.add(task.id());
        }
        store = Deliveries.destination(settlement, first.get()).orElse(null);
        checked = null;
        return true;
    }

    /**
     * The 16-block rule for the target of a slip trip: a target a path search ends short of is left out for the rest of
     * the day (the plan of the next visit skips it), its stop is dropped.
     */
    private void skipUnreachableLeg(ServerLevel level) {
        currentLeg(level).filter(leg -> settler.position().distanceTo(Vec3.atCenterOf(leg.target())) <= PATH_CHECK
                && shell.pathEndsShort(leg.target(), SettlerWork.REACH)).ifPresent(leg -> {
                    unreachable.add(leg.target());
                    legs.remove(leg);
                });
    }

    /** Brings the slip tasks of the Clerks of the Warehouse up to date before the pick. */
    private static void syncSlips(ServerLevel level, SettlementData data, Settlement settlement, SettlementBlock warehouseBlock) {
        for (BlockPos clerk : Warehouses.handled(settlement, warehouseBlock)) {
            settlement.block(clerk).ifPresent(block -> SlipOrders.sync(data, settlement, block, level.getGameTime(), pos -> SlipSites.isMissing(level, pos)));
        }
    }

    private boolean mine(Settlement settlement, UUID id, long day) {
        return Deliveries.get(settlement, id).flatMap(task -> task.takerOn(day))
                .filter(taker -> taker.settler().equals(settler.getUUID())).isPresent();
    }

    private void releaseRoute(ServerLevel level, Settlement settlement) {
        SettlementData data = SettlementData.get(level);
        route.forEach(id -> Deliveries.release(data, settlement, id));
        route.clear();
        visiting = null;
        visit = null;
    }

    /**
     * @return whether the Deliveryman is staff at a source: anything but a Clerk JobBlock, or a Clerk its Warehouse
     *         serves
     */
    private static boolean staff(Settlement settlement, SettlerJob job, BlockPos source) {
        return settlement.block(source).filter(ClerkStorage::isClerk).isEmpty()
                || Warehouses.warehouseOf(settlement, source).map(SettlementBlock::pos).filter(job.jobBlock()::equals).isPresent();
    }

    /** One path search per walk, once the source is close enough for it to be conclusive. */
    private boolean reachable(BlockPos source) {
        if (source.equals(checked) || settler.position().distanceTo(Vec3.atCenterOf(source)) > PATH_CHECK) {
            return true;
        }
        checked = source;
        return !shell.pathEndsShort(source, SettlerWork.REACH);
    }

    /** How many stacks a task would carry: what a pickup's drop-off holds, a result's count, an input delivery's items. */
    private static int stacksOf(ServerLevel level, DeliveryTask task) {
        if (task.input().isPresent()) {
            return task.input().get().items().entrySet().stream()
                    .mapToInt(entry -> (int) Math.ceil(entry.getValue() / (double) new ItemStack(entry.getKey()).getMaxStackSize())).sum();
        }
        if (task.result().isPresent()) {
            DeliveryTask.Result result = task.result().get();
            return (int) Math.ceil(result.count() / (double) new ItemStack(result.item()).getMaxStackSize());
        }
        return JobBlockSite.at(level, task.jobBlock()).map(site -> Pickups.contents(level, site, task.source()).stacks()).orElse(0);
    }

    private static Component sourceName(Settlement settlement, DeliveryTask task) {
        if (task.slip().isPresent()) {
            return Component.translatable("item.civmod.delivery_slip");
        }
        // An input delivery is collected at its store, the others at their JobBlock's drop-off.
        return settlement.block(task.input().isPresent() ? task.source() : task.jobBlock())
                .map(block -> ClerkStorage.isClerk(block) ? ClerkStorage.name(block) : block.jobType().displayName())
                .orElseGet(() -> Component.literal(task.source().toShortString()));
    }

    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        return JobStep.skip();
    }

    // ---------------------------------------------------------------- per-tick work

    /** At a source: takes what fits of every task there; at a foreign Clerk's counter it waits for its turn first. */
    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        BlockPos source = visiting != null ? visiting : target;
        boolean counter = visit != null && visit.counter();
        if (counter && !shell.waitAtCounter(level, source)) {
            // Waiting for the Clerk.
            return JobStep.STAY;
        }
        checked = null;
        visiting = null;
        visit = null;
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (site.isPresent()) {
            Settlement settlement = site.get().settlement();
            JobBuffer load = shell.workplace(job.jobBlock()).buffer();
            int capacity = carryStacks(shell.effectiveLevel(job));
            long dayTime = DayClock.dayTime(level);
            for (UUID id : List.copyOf(route)) {
                Optional<DeliveryTask> task = Deliveries.get(settlement, id).filter(found -> found.source().equals(source));
                if (task.isPresent()) {
                    route.remove(id);
                    collect(level, settlement, job, task.get(), load, capacity, staff(settlement, job, source) ? null : counter, dayTime, true);
                }
            }
        }
        return JobStep.DONE_STANDING;
    }

    /**
     * Takes what fits of one task. A pickup is done once its source is empty, otherwise free again. A result is carried
     * from now on (what did not fit stays as a task of its own); nothing found ends it. Inputs are picked up whole as
     * reserved; a shortfall frees the task for another try.
     *
     * @param counter null when the Deliveryman is staff at the source, else whether it stands at the counter
     */
    private void collect(ServerLevel level, Settlement settlement, SettlerJob job, DeliveryTask task, JobBuffer load, int capacity,
            @Nullable Boolean counter, long dayTime, boolean effects) {
        SettlementData data = SettlementData.get(level);
        if (task.slip().isPresent()) {
            collectSlip(level, settlement, task, load, capacity, counter, dayTime, effects);
            return;
        }
        if (task.input().isPresent()) {
            collectInputs(level, settlement, task, load, capacity, counter, dayTime, effects);
            return;
        }
        if (task.result().isEmpty()) {
            Optional<JobBlockSite> sourceSite = JobBlockSite.at(level, task.jobBlock());
            if (sourceSite.isEmpty() || !level.isLoaded(task.source())) {
                Deliveries.release(data, settlement, task.id());
                return;
            }
            Pickups.take(level, sourceSite.get(), task.source(), kind -> load.room(kind, capacity), load::add);
            if (Pickups.contents(level, sourceSite.get(), task.source()).items() == 0) {
                Deliveries.done(data, settlement, task.id());
            } else {
                Deliveries.release(data, settlement, task.id());
            }
            return;
        }
        DeliveryTask.Result result = task.result().get();
        if (!level.isLoaded(task.source())) {
            Deliveries.release(data, settlement, task.id());
            return;
        }
        Stock stock = counter == null ? ClerkStorage.stock(level, task.source(), true) : ClerkStorage.stock(level, task.source(), dayTime, counter);
        int want = Math.min(result.count(), load.room(new ItemStack(result.item()), capacity));
        List<ItemStack> got = want > 0 ? stock.take(result.item(), want) : List.of();
        int taken = got.stream().mapToInt(ItemStack::getCount).sum();
        got.forEach(load::add);
        if (taken > 0 && counter != null && stock.counter()) {
            ClerkCounter.handOut(level, task.source(), effects);
        }
        // Less than wanted: the source ran short; less wanted than the task holds: the rest did not fit and waits.
        int left = taken == want ? result.count() - want : 0;
        Deliveries.carry(data, settlement, task.id(), settler.getUUID(), taken, left);
    }

    /**
     * The visit of a Delivery Slip: scans the source, works out what goes where ({@link SlipPlan}) and takes it; the stops
     * follow as {@link #legs}. Nothing to move: the Deliveryman is done here and takes the next task. Either way the slip
     * rests for its cooldown and its task is gone until then.
     */
    private void collectSlip(ServerLevel level, Settlement settlement, DeliveryTask task, JobBuffer load, int capacity,
            @Nullable Boolean counter, long dayTime, boolean effects) {
        SettlementData data = SettlementData.get(level);
        Optional<SlipOrders.Order> found = SlipOrders.order(settlement, task);
        if (found.isEmpty() || !level.isLoaded(task.source())) {
            // The slip was taken out, or the source cannot be read now: no visit happened.
            Deliveries.done(data, settlement, task.id());
            return;
        }
        SlipOrders.Order order = found.get();
        Stock stock = counter == null ? ClerkStorage.stock(level, task.source(), true) : ClerkStorage.stock(level, task.source(), dayTime, counter);
        SlipPlan plan = SlipPlan.of(order.slip(), order.clerk().orders().clock(order.slip().id()), stock,
                target -> unreachable.contains(target) ? Stock.EMPTY : ClerkStorage.stock(level, target, true), capacity, level.getRandom());
        int before = load.count();
        plan.take().forEach((item, count) -> stock.take(item, count).forEach(load::add));
        boolean moved = load.count() > before;
        if (moved && counter != null && stock.counter()) {
            ClerkCounter.handOut(level, task.source(), effects);
        }
        legs.clear();
        if (moved) {
            legs.addAll(plan.legs());
        }
        SlipOrders.visited(data, order.clerk(), order.slip().id(), level.getGameTime(), moved, CivModConfig.SLIP_COOLDOWN_SECONDS.get(), plan.next());
        Deliveries.done(data, settlement, task.id());
    }

    /**
     * Picks up the reserved inputs of an input delivery (in catch-up the task is taken and reserved right here). What
     * does not fit the carry inventory stays at the store (the task is free again); a shortfall frees it as well.
     */
    private void collectInputs(ServerLevel level, Settlement settlement, DeliveryTask task, JobBuffer load, int capacity,
            @Nullable Boolean counter, long dayTime, boolean effects) {
        SettlementData data = SettlementData.get(level);
        long day = DayClock.day(level);
        Optional<DeliveryTask.Taker> taker = task.takerOn(day);
        if (taker.isPresent() && !taker.get().settler().equals(settler.getUUID())) {
            // Taken by another Deliveryman (catch-up meets a live lock): its inputs are reserved for that one.
            return;
        }
        if (!level.isLoaded(task.source()) || capacity - load.stacks().size() < stacksOf(level, task)) {
            Deliveries.release(data, settlement, task.id());
            return;
        }
        if (taker.isEmpty()) {
            Deliveries.take(data, settlement, task.id(), settler.getUUID(), settler.getName().getString(), day);
        }
        Stock stock = counter == null ? ClerkStorage.stock(level, task.source(), true, task.id())
                : ClerkStorage.stock(level, task.source(), dayTime, counter, task.id());
        Reservations.Pickup pickup = Deliveries.pickUpInput(data, settlement, task.id(), settler.getUUID(), stock, day);
        pickup.taken().forEach(load::add);
        if (!pickup.failed() && counter != null && stock.counter()) {
            ClerkCounter.handOut(level, task.source(), effects);
        }
    }

    // ---------------------------------------------------------------- unloading (through the shell's delivery)

    /**
     * @param level the level
     * @param job   the Deliveryman job
     * @return the store the load goes to: the destination of the trip (or, without a trip, of the results and inputs it
     *         carries; inputs go to a producer's collect inventory); else (nothing known, or that place is gone) the
     *         Warehouse
     */
    BlockPos unloadTarget(ServerLevel level, SettlerJob job) {
        Optional<SlipPlan.Leg> leg = currentLeg(level);
        if (leg.isPresent()) {
            return leg.get().target();
        }
        BlockPos target = store;
        if (target == null) {
            target = JobBlockSite.at(level, job.jobBlock()).flatMap(site -> Deliveries.carriedBy(site.settlement(), settler.getUUID()).stream()
                    .findFirst().flatMap(task -> Deliveries.destination(site.settlement(), task))).orElse(null);
        }
        return target != null && deliverable(level, target) ? target : job.jobBlock();
    }

    /** A Clerk JobBlock, or a loaded container (a producer's collect inventory). */
    private static boolean deliverable(ServerLevel level, BlockPos target) {
        return JobBlockSite.blockAt(level, target).filter(ClerkStorage::isClerk).isPresent()
                || level.isLoaded(target) && level.getCapability(Capabilities.Item.BLOCK, target, null) != null;
    }

    /**
     * @param level the level
     * @param job   the Deliveryman job
     * @return the name of the store the load goes to, for "Delivering to <Clerk>" (a producer's Job for inputs)
     */
    Component unloadName(ServerLevel level, SettlerJob job) {
        BlockPos target = unloadTarget(level, job);
        return JobBlockSite.blockAt(level, target).map(block -> ClerkStorage.isClerk(block) ? ClerkStorage.name(block) : block.jobType().displayName())
                .or(() -> JobBlockSite.at(level, job.jobBlock()).flatMap(site -> Deliveries.carriedBy(site.settlement(), settler.getUUID()).stream()
                        .filter(task -> task.input().filter(input -> input.to().equals(target)).isPresent()).findFirst()
                        .flatMap(task -> site.settlement().block(task.jobBlock())).map(block -> block.jobType().displayName())))
                .orElseGet(Component::empty);
    }

    /**
     * Unloads the carry inventory into the whole store as staff; EXP per delivered stack. What does not fit stays (the
     * shell blocks the job with "Storage full", retried after 30 s).
     *
     * @param level the level
     * @param job   the Deliveryman job
     * @return whether everything was delivered
     */
    boolean unload(ServerLevel level, SettlerJob job) {
        Optional<SlipPlan.Leg> leg = currentLeg(level);
        if (leg.isPresent()) {
            return unloadLeg(level, job, leg.get());
        }
        BlockPos target = unloadTarget(level, job);
        boolean all = unload(level, job, target, shell.workplace(job.jobBlock()).buffer());
        if (all) {
            store = null;
        }
        StockRules.check(level, target, DayClock.dayTime(level));
        return all;
    }

    /** The next stop of the slip trip whose target is still there, empty without a slip trip. */
    private Optional<SlipPlan.Leg> currentLeg(ServerLevel level) {
        return legs.stream().filter(leg -> deliverable(level, leg.target())).findFirst();
    }

    /**
     * Unloads what a stop of a slip trip gets into its target: no more of an item than the plan gave it. The stop is done
     * afterwards, whatever fitted; what is left goes to the next stop or, after the last one, into the Warehouse. A target
     * that is full is no reason to wait here, so this never blocks the job with "Storage full".
     *
     * @return true: the job goes on
     */
    private boolean unloadLeg(ServerLevel level, SettlerJob job, SlipPlan.Leg leg) {
        Stock stock = ClerkStorage.stock(level, leg.target(), true);
        Map<Item, Integer> allowed = new HashMap<>(leg.items());
        JobBuffer load = shell.workplace(job.jobBlock()).buffer();
        int before = load.stacks().size();
        load.deliver(stack -> {
            int part = Math.min(stack.getCount(), allowed.getOrDefault(stack.getItem(), 0));
            if (part <= 0) {
                return 0;
            }
            int delivered = part - stock.insert(stack.copyWithCount(part)).getCount();
            allowed.merge(stack.getItem(), -delivered, Integer::sum);
            return delivered;
        });
        int delivered = before - load.stacks().size();
        if (delivered > 0) {
            int jobBlockLevel = shell.jobBlockLevel(job);
            shell.gainExp(level, job, delivered * job.jobType().baseExp() * jobBlockLevel, SettlementRules.skillCap(jobBlockLevel));
        }
        // The stop and the stops before it (their targets are gone) are done.
        List<SlipPlan.Leg> all = List.copyOf(legs);
        legs.clear();
        legs.addAll(all.subList(all.indexOf(leg) + 1, all.size()));
        StockRules.check(level, leg.target(), DayClock.dayTime(level));
        return true;
    }

    /**
     * @return whether everything was delivered into the store (then the results it carried arrived there)
     */
    private boolean unload(ServerLevel level, SettlerJob job, BlockPos target, JobBuffer load) {
        Stock stock = ClerkStorage.stock(level, target, true);
        int before = load.stacks().size();
        boolean all = !stock.isEmpty() && load.deliver(stack -> stack.getCount() - stock.insert(stack).getCount());
        // EXP per stack delivered whole; a stack the store took only part of counts when its rest is delivered.
        int delivered = before - load.stacks().size();
        if (delivered > 0) {
            int jobBlockLevel = shell.jobBlockLevel(job);
            shell.gainExp(level, job, delivered * job.jobType().baseExp() * jobBlockLevel, SettlementRules.skillCap(jobBlockLevel));
        }
        if (all) {
            // The Deliveryman's settlement: a collect inventory may stand outside the border.
            SettlementData data = SettlementData.get(level);
            JobBlockSite.at(level, job.jobBlock()).ifPresent(site -> Deliveries.arrived(data, site.settlement(), settler.getUUID(), target));
        }
        return all;
    }

    // ---------------------------------------------------------------- end of day, removal

    /**
     * End of work: its tasks are free again (results it carries stay carried until unloaded), every drop-off of the
     * Warehouse's JobBlocks with something left gets a pickup (also JobBlocks without a settler: items a player put in)
     * and the surplus of the Clerks it handles is checked.
     */
    @Override
    public void dayEnded(ServerLevel level, SettlerJob job) {
        Deliveries.releaseAll(SettlementData.get(level), settler.getUUID());
        route.clear();
        checked = null;
        visiting = null;
        visit = null;
        JobBlockSite.at(level, job.jobBlock()).ifPresent(site -> {
            List<BlockPos> clerks = Warehouses.handled(site.settlement(), site.block());
            for (SettlementBlock block : List.copyOf(site.settlement().blocks())) {
                if (block.homeClerk().filter(clerks::contains).isPresent()) {
                    JobBlockSite.at(level, block.pos()).ifPresent(source -> Pickups.sweep(level, source));
                }
            }
            // Surplus above Max, also of items a player put in.
            clerks.forEach(clerk -> StockRules.checkSurplus(level, clerk));
        });
    }

    /** The job is removed: its tasks are free again; the load is dropped at the settler, with the results it carried. */
    @Override
    public void forget(BlockPos jobBlock) {
        if (!jobBlock.equals(warehouse)) {
            return;
        }
        if (settler.level() instanceof ServerLevel level) {
            SettlementData data = SettlementData.get(level);
            Deliveries.releaseAll(data, settler.getUUID());
            Deliveries.dropCarried(data, settler.getUUID());
        }
        route.clear();
        legs.clear();
        store = null;
        checked = null;
        visiting = null;
        visit = null;
        warehouse = null;
        unreachable.clear();
        unreachableDay = Long.MIN_VALUE;
    }

    // ---------------------------------------------------------------- catch-up

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        // Unloaded sources are skipped.
        return true;
    }

    /**
     * Moves items of the Warehouse's tasks into their stores: trip by trip (best task, batched like live play), each
     * trip costing the walk from the store to its sources and back plus a stop at each; at most the carry capacity per
     * trip. A full store ends the hour with the rest carried.
     */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks, SimulatedSpan span) {
        Optional<JobBlockSite> found = JobBlockSite.at(level, job.jobBlock());
        if (found.isEmpty()) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        warehouse = job.jobBlock();
        Settlement settlement = found.get().settlement();
        JobBuffer load = shell.workplace(job.jobBlock()).buffer();
        int capacity = carryStacks(shell.effectiveLevel(job));
        double time = workTicks;
        int moved = 0;
        if (!load.isEmpty()) {
            // What it still carries goes first.
            BlockPos target = unloadTarget(level, job);
            time -= STOP_TICKS;
            int before = load.stacks().size();
            if (!unload(level, job, target, load)) {
                return new SimulatedWork(before - load.stacks().size(), 0.0);
            }
            moved += before;
            store = null;
        }
        Set<BlockPos> skipped = new HashSet<>();
        while (time > 0) {
            Optional<DeliveryTask> first = Deliveries.pick(settlement, found.get().block(), job.jobBlock(), level.getGameTime(), day, skipped,
                    Deliveries.Scope.AUTOMATIC_ONLY);
            if (first.isEmpty()) {
                break;
            }
            List<DeliveryTask> trip = Deliveries.batch(settlement, found.get().block(), first.get(), capacity, task -> stacksOf(level, task), day,
                    skipped);
            BlockPos target = Deliveries.destination(settlement, first.get()).orElse(job.jobBlock());
            double ticks = tripTicks(target, trip.stream().map(DeliveryTask::source).distinct().toList());
            if (ticks > time) {
                break;
            }
            time -= ticks;
            long dayTime = span.dayTime();
            for (DeliveryTask task : trip) {
                skipped.add(task.source());
                // Catch-up reaches every counter.
                collect(level, settlement, job, task, load, capacity, staff(settlement, job, task.source()) ? null : true, dayTime, false);
            }
            int carried = load.stacks().size();
            store = target;
            if (!unload(level, job, target, load)) {
                // Storage full: the rest is carried and delivered live.
                return new SimulatedWork(moved + carried - load.stacks().size(), 0.0);
            }
            store = null;
            moved += carried;
        }
        // Nothing (more) to collect: the rest of the hour goes to the next job.
        return new SimulatedWork(moved, Math.max(0.0, time));
    }

    /**
     * @param target  the store
     * @param sources the trip's sources in order
     * @return the ticks of the trip: from the store along the sources and back, plus a stop at each source and the
     *         store
     */
    static double tripTicks(BlockPos target, List<BlockPos> sources) {
        double distance = 0.0;
        BlockPos at = target;
        for (BlockPos source : sources) {
            distance += Math.sqrt(at.distSqr(source));
            at = source;
        }
        distance += Math.sqrt(at.distSqr(target));
        return distance / WALK_BLOCKS_PER_TICK + STOP_TICKS * (sources.size() + 1);
    }
}
