package com.gilfort.civmod.settlement;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.SlipPlan;
import com.gilfort.civmod.logistics.SlipSites;
import com.gilfort.civmod.registry.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;

/**
 * Delivery Slips as tasks of the shared pool (vault {@code Jobs/Deliveryman.md}): {@link #sync} keeps one task per filled
 * slip of a Clerk JobBlock's Delivery Orders that may be visited, {@link #visited} sets the cooldown after a visit.
 * A slip may be visited when its source is there, a target has room and its cooldown is over; the slot order is the
 * priority ({@link Deliveries#pick}). Pure logic on {@link SettlementData}; facts about the world (is a container gone, has
 * a target room) are passed in as {@link World}.
 */
public final class SlipOrders {

    /** Empty visits in a row that double the cooldown. */
    static final int EMPTY_VISITS_PER_STEP = 3;
    /** Game ticks in a second, to turn the cooldown of the config into game time. */
    static final long TICKS_PER_SECOND = 20L;

    private SlipOrders() {
    }

    /**
     * A slip with the place it sits in.
     *
     * @param clerk the Clerk JobBlock whose Delivery Orders hold it
     * @param slot  its slot
     * @param slip  the slip
     */
    public record Order(SettlementBlock clerk, int slot, DeliverySlip slip) {
    }

    /**
     * What a visit of a slip found, for its clock.
     *
     * @param gameTime    the game time now
     * @param moved       whether the visit moved items
     * @param baseSeconds the base cooldown in seconds (config)
     * @param next        where the round robin goes next
     */
    public record Visit(long gameTime, boolean moved, int baseSeconds, int next) {
    }

    /**
     * The facts about the world that {@link #sync} cannot know.
     *
     * @param missing     whether a container is gone (an unloaded one counts as there)
     * @param roomLeft    whether a target of the slip may take something (cheap and local: no scan of the source)
     * @param baseSeconds the base cooldown in seconds (config)
     */
    public record World(Predicate<BlockPos> missing, Predicate<DeliverySlip> roomLeft, int baseSeconds) {

        /**
         * @param level the level
         * @return the world as the Deliverymen see it: containers and targets of that level, the cooldown of the config
         */
        public static World of(ServerLevel level) {
            return new World(pos -> SlipSites.isMissing(level, pos),
                    slip -> SlipPlan.roomLeft(slip, target -> ClerkStorage.stock(level, target, true)), CivModConfig.SLIP_COOLDOWN_SECONDS.get());
        }

        /**
         * @param missing whether a container is gone
         * @return a world where every target has room and the cooldown is 30 s (tests)
         */
        public static World open(Predicate<BlockPos> missing) {
            return new World(missing, slip -> true, 30);
        }
    }

    /**
     * @param emptyVisits visits in a row that moved nothing
     * @return the factor the base cooldown is multiplied with: 1 below three empty visits, 2 up to five, then 4 (the cap)
     */
    static int cooldownFactor(int emptyVisits) {
        return 1 << Math.min(2, emptyVisits / EMPTY_VISITS_PER_STEP);
    }

    /**
     * Sets the cooldown of a slip after a visit: the base value, doubled after three empty visits in a row and doubled
     * again after three more; a visit that moved items starts over.
     *
     * @param data  the dimension's settlement data
     * @param clerk the Clerk JobBlock holding the slip
     * @param slip  the slip id
     * @param visit what the visit found
     */
    public static void visited(SettlementData data, SettlementBlock clerk, UUID slip, Visit visit) {
        int empty = visit.moved() ? 0 : clerk.orders().clock(slip).emptyVisits() + 1;
        long ready = visit.gameTime() + visit.baseSeconds() * TICKS_PER_SECOND * cooldownFactor(empty);
        clerk.orders().setClock(slip, new DeliveryOrders.Clock(ready, empty, visit.next()));
        data.setDirty();
    }

    /**
     * @param settlement a settlement
     * @param task       a slip task
     * @return the slip it carries out with its slot, empty if the slip left the Delivery Orders
     */
    public static Optional<Order> order(Settlement settlement, DeliveryTask task) {
        Optional<SettlementBlock> clerk = settlement.block(task.jobBlock());
        Optional<DeliveryTask.SlipRef> ref = task.slip();
        if (clerk.isEmpty() || ref.isEmpty()) {
            return Optional.empty();
        }
        DeliveryOrders orders = clerk.get().orders();
        for (int slot = 0; slot < clerk.get().stockSlots(); slot++) {
            Optional<DeliverySlip> slip = DeliveryOrders.slip(orders.slip(slot));
            if (slip.isPresent() && slip.get().id().equals(ref.get().id())) {
                return Optional.of(new Order(clerk.get(), slot, slip.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Brings the slip tasks of a Clerk JobBlock up to date: every filled slip in its slots in use whose source is there
     * and whose cooldown is over has one task, as long as a target has room; the others have none (a task a Deliveryman
     * already took stays until he is done). The status of a slip ("Source missing", "Target missing") is written onto it;
     * a slip with a destroyed target still serves the targets that are left. Without room anywhere the slip rests for
     * its cooldown, so the check runs once per cooldown and not per sync.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param clerk      the Clerk JobBlock
     * @param gameTime   the game time now
     * @param world      what the world says about the containers
     */
    public static void sync(SettlementData data, Settlement settlement, SettlementBlock clerk, long gameTime, World world) {
        DeliveryOrders orders = clerk.orders();
        Set<UUID> active = new HashSet<>();
        for (int slot = 0; slot < clerk.stockSlots(); slot++) {
            ItemStack stack = orders.slip(slot);
            Optional<DeliverySlip> found = DeliveryOrders.slip(stack);
            if (found.isEmpty()) {
                continue;
            }
            DeliverySlip slip = found.get();
            DeliverySlip.Status status = status(slip, world.missing());
            if (status != slip.status()) {
                stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip.withStatus(status));
                data.setDirty();
            }
            active.add(slip.id());
            boolean ready = status != DeliverySlip.Status.SOURCE_MISSING && orders.clock(slip.id()).readyAt() <= gameTime;
            if (ready && task(settlement, slip).isEmpty() && !world.roomLeft().test(slip)) {
                rest(data, clerk, slip.id(), gameTime, world.baseSeconds());
                ready = false;
            }
            syncTask(data, settlement, new Order(clerk, slot, slip), ready, gameTime);
        }
        // Slips that left the slots or the slots in use.
        List<DeliveryTask> gone = settlement.deliveries().stream().filter(task -> task.slip().isPresent() && task.jobBlock().equals(clerk.pos())
                && !active.contains(task.slip().get().id())).toList();
        gone.forEach(task -> Deliveries.done(data, settlement, task.id()));
    }

    /**
     * A Clerk JobBlock is removed: its slips drop as items and their tasks are gone.
     *
     * @param level      the level
     * @param settlement the settlement
     * @param clerk      the Clerk JobBlock
     */
    public static void dropOrders(ServerLevel level, Settlement settlement, SettlementBlock clerk) {
        DeliveryOrders orders = clerk.orders();
        for (int slot = 0; slot < DeliveryOrders.MAX_SLOTS; slot++) {
            ItemStack stack = orders.slip(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, clerk.pos().getX() + 0.5, clerk.pos().getY() + 0.5, clerk.pos().getZ() + 0.5, stack.copy());
                orders.setSlip(slot, ItemStack.EMPTY);
            }
        }
        sync(SettlementData.get(level), settlement, clerk, level.getGameTime(), World.open(pos -> false));
    }

    /** A slip with a missing target is still worked (the plan leaves the gone target out); only a missing source stops it. */
    private static DeliverySlip.Status status(DeliverySlip slip, Predicate<BlockPos> missing) {
        if (slip.source().filter(missing).isPresent()) {
            return DeliverySlip.Status.SOURCE_MISSING;
        }
        return slip.targets().stream().anyMatch(missing) ? DeliverySlip.Status.TARGET_MISSING : DeliverySlip.Status.OK;
    }

    /** Nothing to bring anywhere: the slip waits for its cooldown like after an empty visit, without counting as one. */
    private static void rest(SettlementData data, SettlementBlock clerk, UUID slip, long gameTime, int baseSeconds) {
        DeliveryOrders.Clock clock = clerk.orders().clock(slip);
        long ready = gameTime + baseSeconds * TICKS_PER_SECOND * cooldownFactor(clock.emptyVisits());
        clerk.orders().setClock(slip, new DeliveryOrders.Clock(ready, clock.emptyVisits(), clock.next()));
        data.setDirty();
    }

    private static Optional<DeliveryTask> task(Settlement settlement, DeliverySlip slip) {
        return settlement.deliveries().stream().filter(task -> task.slip().filter(ref -> ref.id().equals(slip.id())).isPresent()).findFirst();
    }

    private static DeliveryTask slipTask(UUID id, Order order, long created) {
        return new DeliveryTask(id, order.slip().source().orElseThrow(() -> new IllegalStateException("A filled slip has a source: " + order.slip())),
                order.clerk().pos().immutable(), created, false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new DeliveryTask.SlipRef(order.slip().id(), order.slot())));
    }

    private static void syncTask(SettlementData data, Settlement settlement, Order order, boolean ready, long gameTime) {
        Optional<DeliveryTask> existing = task(settlement, order.slip());
        if (existing.isEmpty()) {
            if (ready) {
                settlement.mutableDeliveries().add(slipTask(UUID.randomUUID(), order, gameTime));
                data.setDirty();
            }
            return;
        }
        DeliveryTask task = existing.get();
        if (!ready) {
            // A Deliveryman on his way keeps the task; it is gone when he is done.
            if (task.taker().isEmpty()) {
                Deliveries.done(data, settlement, task.id());
            }
            return;
        }
        boolean changed = task.slip().orElseThrow(() -> new IllegalStateException("Not a slip task: " + task)).slot() != order.slot()
                || !task.source().equals(order.slip().source().orElse(task.source()));
        if (changed && task.taker().isEmpty()) {
            settlement.mutableDeliveries().replaceAll(old -> old.id().equals(task.id()) ? slipTask(old.id(), order, old.created()) : old);
            data.setDirty();
        }
    }
}
