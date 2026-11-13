package com.gilfort.civmod.job;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.SlipPlan;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SlipOrders;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Delivery Slip trips of a Deliveryman (vault {@code Jobs/Deliveryman.md}), split from {@link DeliverymanLoop}: the
 * visit at the source of a slip ({@link #collect}) works out what goes where ({@link SlipPlan}) and takes it, the stops
 * that follow are the {@link #legs}, each unloaded into its target ({@link #unloadLeg}). The loop asks for the current stop
 * when it decides where to unload.
 */
final class SlipTrips {

    private final SettlerWork shell;
    private final Settler settler;
    /** Sources and slip targets found unreachable today (shared with the loop, which owns the day change). */
    private final Set<BlockPos> unreachable;
    /** The stops of the slip trip under way, still to unload at (not saved: a reload sends the load to the Warehouse). */
    private final Deque<SlipPlan.Leg> legs = new ArrayDeque<>();

    /**
     * @param shell       the Settler Shell driving the loop
     * @param unreachable the places the Deliveryman leaves out today
     */
    SlipTrips(SettlerWork shell, Set<BlockPos> unreachable) {
        this.shell = shell;
        this.settler = shell.settler();
        this.unreachable = unreachable;
    }

    /** Brings the slip tasks of the Clerks of the Warehouse up to date before the pick. */
    static void sync(ServerLevel level, SettlementData data, Settlement settlement, SettlementBlock warehouseBlock) {
        SlipOrders.World world = SlipOrders.World.of(level);
        for (BlockPos clerk : Warehouses.handled(settlement, warehouseBlock)) {
            settlement.block(clerk).ifPresent(block -> SlipOrders.sync(data, settlement, block, level.getGameTime(), world));
        }
    }

    /** Forgets the stops (the job is removed). */
    void clear() {
        legs.clear();
    }

    /**
     * The visit of a Delivery Slip: scans the source, works out what goes where and takes it; the stops follow as
     * {@link #legs}. Nothing to move: the Deliveryman is done here and takes the next task. Either way the slip rests for
     * its cooldown and its task is gone until then.
     *
     * @param visit the visit as {@link DeliverymanLoop} drives it
     * @param task  the slip task
     */
    void collect(DeliverymanLoop.Collection visit, DeliveryTask task) {
        ServerLevel level = visit.level();
        Settlement settlement = visit.settlement();
        SettlementData data = SettlementData.get(level);
        Optional<SlipOrders.Order> found = SlipOrders.order(settlement, task);
        if (found.isEmpty() || !level.isLoaded(task.source())) {
            // The slip was taken out, or the source cannot be read now: no visit happened.
            Deliveries.done(data, settlement, task.id());
            return;
        }
        SlipOrders.Order order = found.get();
        Stock stock = visit.stockAt(task.source());
        SlipPlan plan = SlipPlan.of(order.slip(), order.clerk().orders().clock(order.slip().id()), stock,
                target -> unreachable.contains(target) ? Stock.EMPTY : ClerkStorage.stock(level, target, true), visit.capacity(), level.getRandom());
        int before = visit.load().count();
        plan.take().forEach((item, count) -> stock.take(item, count).forEach(visit.load()::add));
        boolean moved = visit.load().count() > before;
        if (moved) {
            visit.handedOut(stock, task.source());
        }
        legs.clear();
        if (moved) {
            legs.addAll(plan.legs());
        }
        SlipOrders.visited(data, order.clerk(), order.slip().id(),
                new SlipOrders.Visit(level.getGameTime(), moved, SlipOrders.World.of(level).baseSeconds(), plan.next()));
        Deliveries.done(data, settlement, task.id());
    }

    /**
     * The 16-block rule for the target of a slip trip: a target a path search ends short of is left out for the rest of
     * the day (the plan of the next visit skips it), its stop is dropped.
     */
    void skipUnreachableLeg(ServerLevel level) {
        currentLeg(level).filter(leg -> settler.position().distanceTo(Vec3.atCenterOf(leg.target())) <= DeliverymanLoop.PATH_CHECK
                && shell.pathEndsShort(leg.target(), SettlerWork.REACH)).ifPresent(leg -> {
                    unreachable.add(leg.target());
                    legs.remove(leg);
                });
    }

    /**
     * @param level the level
     * @return the next stop of the slip trip whose target is still there, empty without a slip trip
     */
    Optional<SlipPlan.Leg> currentLeg(ServerLevel level) {
        return legs.stream().filter(leg -> DeliverymanLoop.deliverable(level, leg.target())).findFirst();
    }

    /**
     * Unloads what a stop of a slip trip gets into its target: no more of an item than the plan gave it. The stop is done
     * afterwards, whatever fitted; what is left goes to the next stop or, after the last one, into the Warehouse. A target
     * that is full is no reason to wait here, so this never blocks the job with "Storage full". EXP per stack delivered,
     * also when the target took only part of a stack.
     *
     * @param level the level
     * @param job   the Deliveryman job
     * @param leg   the stop
     * @return true: the job goes on
     */
    boolean unloadLeg(ServerLevel level, SettlerJob job, SlipPlan.Leg leg) {
        Stock stock = ClerkStorage.stock(level, leg.target(), true);
        Map<Item, Integer> allowed = new HashMap<>(leg.items());
        shell.workplace(job.jobBlock()).buffer().deliver(stack -> {
            int part = Math.min(stack.getCount(), allowed.getOrDefault(stack.getItem(), 0));
            if (part <= 0) {
                return 0;
            }
            int moved = part - stock.insert(stack.copyWithCount(part)).getCount();
            allowed.merge(stack.getItem(), -moved, Integer::sum);
            return moved;
        });
        int stacks = leg.items().entrySet().stream().mapToInt(entry -> stacksOf(entry.getKey(), entry.getValue() - allowed.getOrDefault(entry.getKey(), 0))).sum();
        if (stacks > 0) {
            int jobBlockLevel = shell.jobBlockLevel(job);
            shell.gainExp(level, job, stacks * job.jobType().baseExp() * jobBlockLevel, SettlementRules.skillCap(jobBlockLevel));
        }
        // The stop and the stops before it (their targets are gone) are done.
        List<SlipPlan.Leg> all = List.copyOf(legs);
        legs.clear();
        legs.addAll(all.subList(all.indexOf(leg) + 1, all.size()));
        StockRules.check(level, leg.target(), DayClock.dayTime(level));
        return true;
    }

    private static int stacksOf(Item item, int count) {
        return (int) Math.ceil(count / (double) new ItemStack(item).getMaxStackSize());
    }
}
