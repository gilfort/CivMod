package com.gilfort.civmod.settlement;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.registry.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;

/**
 * Delivery Slips as tasks of the shared pool (vault {@code Jobs/Deliveryman.md}): {@link #sync} keeps one task per filled
 * slip of a Clerk JobBlock's Delivery Orders that may be visited, {@link #visited} sets the cooldown after a visit.
 * A slip may be visited when its containers are there and its cooldown is over; the slot order is the priority
 * ({@link Deliveries#pick}). Pure logic on {@link SettlementData}; facts about the world (is a container gone) are passed
 * in.
 */
public final class SlipOrders {

    /** Empty visits in a row that double the cooldown. */
    static final int EMPTY_VISITS_PER_STEP = 3;

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
     * @param data        the dimension's settlement data
     * @param clerk       the Clerk JobBlock holding the slip
     * @param slip        the slip id
     * @param gameTime    the game time now
     * @param moved       whether the visit moved items
     * @param baseSeconds the base cooldown in seconds (config)
     * @param next        where the round robin goes next
     */
    public static void visited(SettlementData data, SettlementBlock clerk, UUID slip, long gameTime, boolean moved, int baseSeconds, int next) {
        int empty = moved ? 0 : clerk.orders().clock(slip).emptyVisits() + 1;
        long ready = gameTime + baseSeconds * 20L * cooldownFactor(empty);
        clerk.orders().setClock(slip, new DeliveryOrders.Clock(ready, empty, next));
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
        for (int slot = 0; slot < clerk.get().orderSlots(); slot++) {
            Optional<DeliverySlip> slip = DeliveryOrders.slip(orders.slip(slot));
            if (slip.isPresent() && slip.get().id().equals(ref.get().id())) {
                return Optional.of(new Order(clerk.get(), slot, slip.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Brings the slip tasks of a Clerk JobBlock up to date: every filled slip in its slots in use whose containers are
     * there and whose cooldown is over has one task, the others have none (a task a Deliveryman already took stays until
     * he is done). The status of a slip ("Source missing", "Target missing") is written onto it.
     *
     * @param data     the dimension's settlement data
     * @param settlement the settlement
     * @param clerk    the Clerk JobBlock
     * @param gameTime the game time now
     * @param missing  whether a container is gone
     */
    public static void sync(SettlementData data, Settlement settlement, SettlementBlock clerk, long gameTime, Predicate<BlockPos> missing) {
        DeliveryOrders orders = clerk.orders();
        Set<UUID> active = new HashSet<>();
        for (int slot = 0; slot < clerk.orderSlots(); slot++) {
            ItemStack stack = orders.slip(slot);
            Optional<DeliverySlip> found = DeliveryOrders.slip(stack);
            if (found.isEmpty()) {
                continue;
            }
            DeliverySlip slip = found.get();
            DeliverySlip.Status status = status(slip, missing);
            if (status != slip.status()) {
                stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip.withStatus(status));
                data.setDirty();
            }
            active.add(slip.id());
            boolean ready = status == DeliverySlip.Status.OK && orders.clock(slip.id()).readyAt() <= gameTime;
            syncTask(data, settlement, clerk.pos(), slip, slot, ready, gameTime);
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
        sync(SettlementData.get(level), settlement, clerk, level.getGameTime(), pos -> false);
    }

    private static DeliverySlip.Status status(DeliverySlip slip, Predicate<BlockPos> missing) {
        if (slip.source().filter(missing).isPresent()) {
            return DeliverySlip.Status.SOURCE_MISSING;
        }
        return slip.targets().stream().anyMatch(missing) ? DeliverySlip.Status.TARGET_MISSING : DeliverySlip.Status.OK;
    }

    private static void syncTask(SettlementData data, Settlement settlement, BlockPos clerk, DeliverySlip slip, int slot, boolean ready, long gameTime) {
        Optional<DeliveryTask> existing = settlement.deliveries().stream()
                .filter(task -> task.slip().filter(ref -> ref.id().equals(slip.id())).isPresent()).findFirst();
        if (existing.isEmpty()) {
            if (ready) {
                settlement.mutableDeliveries().add(new DeliveryTask(UUID.randomUUID(), slip.source().orElseThrow(), clerk.immutable(), gameTime, false,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(new DeliveryTask.SlipRef(slip.id(), slot))));
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
        boolean changed = task.slip().orElseThrow().slot() != slot || !task.source().equals(slip.source().orElseThrow());
        if (changed && task.taker().isEmpty()) {
            settlement.mutableDeliveries().replaceAll(old -> old.id().equals(task.id()) ? new DeliveryTask(old.id(), slip.source().orElseThrow(),
                    clerk.immutable(), old.created(), false, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(new DeliveryTask.SlipRef(slip.id(), slot))) : old);
            data.setDirty();
        }
    }
}
