package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Deliveryman, the second Logistics Job (vault {@code Jobs/Deliveryman.md}), Automatic mode: it empties
 * the drop-offs of Resource Collection Jobs into their Home Clerk's store. At its decision a free Deliveryman picks the
 * best pickup of its Warehouse (the Clerk JobBlock it has its place at) and adds further sources of the same Home Clerk
 * while its carry inventory has room ({@link Deliveries}); the tasks are locked to it. It walks from source to source,
 * then unloads into the whole store (block slots first, then the linked containers) as staff: no counter, no working
 * Clerk needed. A full store keeps the rest in its carry inventory ("Storage full", retry after 30 s).
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
    /** Sources of the current trip still to empty, locked to this Deliveryman. */
    private final List<BlockPos> route = new ArrayList<>();
    /** The store the current load goes to (null: the Warehouse). */
    private @Nullable BlockPos store;
    /** The source whose path was already checked on this walk. */
    private @Nullable BlockPos checked;
    /** Sources found unreachable today. */
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
    public boolean plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        warehouse = job.jobBlock();
        if (unreachableDay != day) {
            unreachable.clear();
            unreachableDay = day;
        }
        Settlement settlement = site.settlement();
        JobBuffer load = shell.workplace(job.jobBlock()).buffer();
        int capacity = carryStacks(shell.effectiveLevel(job));
        route.removeIf(source -> !mine(settlement, source, day));
        if (!load.isEmpty() && (route.isEmpty() || load.stacks().size() >= capacity)) {
            releaseRoute(level, settlement);
            return shell.startDelivery(level, job);
        }
        while (true) {
            if (route.isEmpty() && !startTrip(level, settlement, site.block(), capacity, day)) {
                shell.setStatus(job.jobBlock(), JobStatus.NO_PICKUPS);
                return false;
            }
            BlockPos source = route.get(0);
            if (reachable(source)) {
                shell.setActiveJob(job);
                if (!shell.walkingTo(source)) {
                    shell.moveToJob(level, source);
                }
                shell.setStatus(job.jobBlock(), JobStatus.COLLECTING, sourceName(settlement, source));
                return true;
            }
            // Unreachable: the task is free again, this Deliveryman leaves it out for the rest of the day.
            route.remove(0);
            unreachable.add(source);
            Deliveries.release(SettlementData.get(level), settlement, source);
        }
    }

    /** Picks the best free pickup and the sources batched with it, and locks them. */
    private boolean startTrip(ServerLevel level, Settlement settlement, SettlementBlock warehouseBlock, int capacity, long day) {
        Optional<DeliveryTask> first = Deliveries.pick(settlement, warehouseBlock, settler.blockPosition(), level.getGameTime(), day, unreachable);
        if (first.isEmpty()) {
            return false;
        }
        List<DeliveryTask> trip = Deliveries.batch(settlement, warehouseBlock, first.get(), capacity,
                source -> stacksAt(level, settlement, source), day, unreachable);
        SettlementData data = SettlementData.get(level);
        for (DeliveryTask task : trip) {
            Deliveries.take(data, settlement, task.source(), settler.getUUID(), settler.getName().getString(), day);
            route.add(task.source());
        }
        store = Deliveries.homeClerk(settlement, first.get()).orElse(null);
        checked = null;
        return true;
    }

    private boolean mine(Settlement settlement, BlockPos source, long day) {
        return Deliveries.get(settlement, source).flatMap(task -> task.takerOn(day))
                .filter(taker -> taker.settler().equals(settler.getUUID())).isPresent();
    }

    private void releaseRoute(ServerLevel level, Settlement settlement) {
        SettlementData data = SettlementData.get(level);
        route.forEach(source -> Deliveries.release(data, settlement, source));
        route.clear();
    }

    /** One path search per walk, once the source is close enough for it to be conclusive. */
    private boolean reachable(BlockPos source) {
        if (source.equals(checked) || settler.position().distanceTo(Vec3.atCenterOf(source)) > PATH_CHECK) {
            return true;
        }
        checked = source;
        Path path = settler.getNavigation().createPath(source, (int) SettlerWork.REACH);
        // No path at all says nothing (vanilla searches none while the settler is in the air).
        return path == null || path.canReach();
    }

    private static int stacksAt(ServerLevel level, Settlement settlement, BlockPos source) {
        return Deliveries.get(settlement, source).flatMap(task -> JobBlockSite.at(level, task.jobBlock()))
                .map(site -> Pickups.contents(level, site, source).stacks()).orElse(0);
    }

    private static Component sourceName(Settlement settlement, BlockPos source) {
        return Deliveries.get(settlement, source).flatMap(task -> settlement.block(task.jobBlock()))
                .map(block -> block.jobType().displayName()).orElseGet(() -> Component.literal(source.toShortString()));
    }

    @Override
    public boolean holdsDecision(ServerLevel level, SettlerJob job) {
        return false;
    }

    @Override
    public double reach(SettlerJob job) {
        return SettlerWork.REACH;
    }

    // ---------------------------------------------------------------- per-tick work

    /** At a source: takes what fits; an emptied source's task is done, otherwise it is free again. */
    @Override
    public void tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        settler.getNavigation().stop();
        route.remove(target);
        checked = null;
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (site.isPresent()) {
            Settlement settlement = site.get().settlement();
            Optional<JobBlockSite> sourceSite = Deliveries.get(settlement, target).flatMap(task -> JobBlockSite.at(level, task.jobBlock()));
            if (sourceSite.isPresent()) {
                collect(level, settlement, sourceSite.get(), target, shell.workplace(job.jobBlock()).buffer(), carryStacks(shell.effectiveLevel(job)));
            }
        }
        shell.stepDone(level);
    }

    /** Takes what fits out of a source; an emptied source's task is done, otherwise it is free again. */
    private static void collect(ServerLevel level, Settlement settlement, JobBlockSite sourceSite, BlockPos source, JobBuffer load, int capacity) {
        Pickups.take(level, sourceSite, source, kind -> load.room(kind, capacity), load::add);
        SettlementData data = SettlementData.get(level);
        if (Pickups.contents(level, sourceSite, source).items() == 0) {
            Deliveries.done(data, settlement, source);
        } else {
            Deliveries.release(data, settlement, source);
        }
    }

    // ---------------------------------------------------------------- unloading (through the shell's delivery)

    /**
     * @param level the level
     * @param job   the Deliveryman job
     * @return the store the load goes to: the Home Clerk of the trip, else (no trip, or that Clerk is gone) the
     *         Warehouse
     */
    BlockPos unloadTarget(ServerLevel level, SettlerJob job) {
        BlockPos target = store;
        return target != null && JobBlockSite.blockAt(level, target).filter(ClerkStorage::isClerk).isPresent() ? target : job.jobBlock();
    }

    /**
     * @param level the level
     * @param job   the Deliveryman job
     * @return the name of the store the load goes to, for "Delivering to <Clerk>"
     */
    Component unloadName(ServerLevel level, SettlerJob job) {
        return JobBlockSite.blockAt(level, unloadTarget(level, job)).map(ClerkStorage::name).orElseGet(Component::empty);
    }

    /**
     * Unloads the carry inventory into the whole store as staff; EXP per delivered stack. What does not fit stays
     * ("Storage full", retried after 30 s).
     *
     * @param level the level
     * @param job   the Deliveryman job
     */
    void unload(ServerLevel level, SettlerJob job) {
        BlockPos target = unloadTarget(level, job);
        if (unload(level, job, target, shell.workplace(job.jobBlock()).buffer())) {
            store = null;
        } else {
            shell.block(level, job.jobBlock(), JobStatus.STORAGE_FULL);
        }
        StockRules.check(level, target, DayClock.dayTime(level));
    }

    /**
     * @return whether everything was delivered into the store
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
        return all;
    }

    // ---------------------------------------------------------------- end of day, removal

    /**
     * End of work: its tasks are free again, and every drop-off of the Warehouse's JobBlocks with something left gets a
     * pickup (also JobBlocks without a settler: items a player put in).
     */
    @Override
    public void dayEnded(ServerLevel level, SettlerJob job) {
        Deliveries.releaseAll(SettlementData.get(level), settler.getUUID());
        route.clear();
        checked = null;
        JobBlockSite.at(level, job.jobBlock()).ifPresent(site -> {
            List<BlockPos> clerks = Warehouses.handled(site.settlement(), site.block());
            for (SettlementBlock block : List.copyOf(site.settlement().blocks())) {
                if (block.homeClerk().filter(clerks::contains).isPresent()) {
                    JobBlockSite.at(level, block.pos()).ifPresent(source -> Pickups.sweep(level, source));
                }
            }
        });
    }

    @Override
    public void forget(BlockPos jobBlock) {
        if (!jobBlock.equals(warehouse)) {
            return;
        }
        if (settler.level() instanceof ServerLevel level) {
            Deliveries.releaseAll(SettlementData.get(level), settler.getUUID());
        }
        route.clear();
        store = null;
        checked = null;
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
     * Moves items from the Warehouse's pickups into their stores: trip by trip (best task, batched like live play),
     * each trip costing the walk from the store to its sources and back plus a stop at each; at most the carry capacity
     * per trip. A full store ends the hour with the rest carried.
     */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
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
            Optional<DeliveryTask> first = Deliveries.pick(settlement, found.get().block(), job.jobBlock(), level.getGameTime(), day, skipped);
            if (first.isEmpty()) {
                break;
            }
            List<DeliveryTask> trip = Deliveries.batch(settlement, found.get().block(), first.get(), capacity,
                    source -> stacksAt(level, settlement, source), day, skipped);
            BlockPos target = Deliveries.homeClerk(settlement, first.get()).orElse(job.jobBlock());
            double ticks = tripTicks(target, trip.stream().map(DeliveryTask::source).toList());
            if (ticks > time) {
                break;
            }
            time -= ticks;
            for (DeliveryTask task : trip) {
                skipped.add(task.source());
                Optional<JobBlockSite> sourceSite = JobBlockSite.at(level, task.jobBlock());
                if (sourceSite.isPresent() && level.isLoaded(task.source())) {
                    collect(level, settlement, sourceSite.get(), task.source(), load, capacity);
                }
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
