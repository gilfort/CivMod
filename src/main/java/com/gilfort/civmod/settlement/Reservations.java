package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.Stock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The reservations of a settlement's Clerks (vault {@code Jobs/Deliveryman.md}): every Clerk keeps a ledger of amounts
 * held for Delivery tasks. Customers, producers and other Deliverymen see the stock minus what is reserved
 * ({@link #reserved}, applied by {@code ClerkStorage.stock}); the task holding a reservation sees all of it and consumes it
 * on pickup ({@link #pickUp}).
 * <p>
 * A reservation lasts as long as its task is locked to a Deliveryman on the day it was made: releasing or finishing the
 * task (cancel, {@link Deliveries}) releases it at once, the end of the day or a removed task on the next read. Kept in the {@link Settlement}, not saved (like the lock, which a
 * reload frees). Pure logic on the settlement; the Stock passed in is the world.
 */
public final class Reservations {

    private Reservations() {
    }

    /**
     * An amount of an item in a Clerk's storage held for one Delivery task.
     *
     * @param item  the item
     * @param count how many
     * @param task  the Delivery task it is held for
     * @param day   the day it was made; it expires when that day ends
     */
    record Reservation(Item item, int count, UUID task, long day) {
    }

    /**
     * The outcome of a pickup of reserved items.
     *
     * @param taken  the taken stacks; after a failed pickup what did not fit back into the storage (normally nothing)
     * @param failed whether the storage held less than reserved: the task was freed for another try
     */
    public record Pickup(List<ItemStack> taken, boolean failed) {
    }

    /**
     * Reserves an amount at a Clerk for a task locked to a Deliveryman today.
     *
     * @param settlement the settlement
     * @param clerk      the Clerk JobBlock holding the items
     * @param task       the Delivery task
     * @param item       the item
     * @param count      how many
     * @param day        the day now
     */
    public static void reserve(Settlement settlement, BlockPos clerk, UUID task, Item item, int count, long day) {
        if (count > 0) {
            settlement.mutableReservations().computeIfAbsent(clerk.immutable(), key -> new ArrayList<>())
                    .add(new Reservation(item, count, task, day));
        }
    }

    /**
     * What a Clerk holds for tasks; expired and cancelled reservations are dropped on the way.
     *
     * @param settlement the settlement
     * @param clerk      the Clerk JobBlock
     * @param day        the day now
     * @param except     the task asking (it sees its own reservations), null for everybody else
     * @return the reserved amounts by item, empty if nothing is reserved
     */
    public static Map<Item, Integer> reserved(Settlement settlement, BlockPos clerk, long day, @Nullable UUID except) {
        List<Reservation> ledger = ledger(settlement, clerk, day);
        if (ledger.isEmpty()) {
            return Map.of();
        }
        Map<Item, Integer> reserved = new HashMap<>();
        for (Reservation reservation : ledger) {
            if (!reservation.task().equals(except)) {
                reserved.merge(reservation.item(), reservation.count(), Integer::sum);
            }
        }
        return reserved;
    }

    /** The valid reservations of a Clerk; expired and cancelled ones are dropped. */
    private static List<Reservation> ledger(Settlement settlement, BlockPos clerk, long day) {
        List<Reservation> ledger = settlement.mutableReservations().get(clerk);
        if (ledger == null) {
            return List.of();
        }
        ledger.removeIf(reservation -> !valid(settlement, reservation, day));
        if (ledger.isEmpty()) {
            settlement.mutableReservations().remove(clerk);
        }
        return ledger;
    }

    /** A reservation holds while its task is open and locked on the day it was made. */
    private static boolean valid(Settlement settlement, Reservation reservation, long day) {
        return reservation.day() == day && Deliveries.get(settlement, reservation.task()).flatMap(task -> task.takerOn(day)).isPresent();
    }

    /**
     * The task holding reservations at a Clerk picks them up: every reserved amount is taken out of the real storage
     * and the reservations are consumed. A shortfall (someone took items the view still counted) fails the pickup
     * cheaply: what was taken goes back, the reservations are released and the task is freed for another try. A task
     * without a valid reservation there (expired, or never made) fails the same way.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param clerk      the Clerk JobBlock
     * @param task       the Delivery task
     * @param stock      the Clerk's stock as the task sees it ({@code ClerkStorage.stock(…, task)})
     * @param day        the day now
     * @return what was taken, or that the pickup failed
     */
    public static Pickup pickUp(SettlementData data, Settlement settlement, BlockPos clerk, UUID task, Stock stock, long day) {
        List<Reservation> held = ledger(settlement, clerk, day).stream().filter(reservation -> reservation.task().equals(task)).toList();
        List<ItemStack> taken = new ArrayList<>();
        boolean shortfall = held.isEmpty();
        for (Reservation reservation : held) {
            List<ItemStack> stacks = stock.take(reservation.item(), reservation.count());
            taken.addAll(stacks);
            shortfall |= stacks.stream().mapToInt(ItemStack::getCount).sum() < reservation.count();
        }
        release(settlement, task);
        if (!shortfall) {
            return new Pickup(List.copyOf(taken), false);
        }
        List<ItemStack> left = taken.stream().map(stock::insert).filter(rest -> !rest.isEmpty()).toList();
        Deliveries.release(data, settlement, task);
        return new Pickup(left, true);
    }

    /**
     * Releases every reservation of a task (cancelled, or consumed).
     *
     * @param settlement the settlement
     * @param task       the Delivery task
     */
    public static void release(Settlement settlement, UUID task) {
        settlement.mutableReservations().values().removeIf(ledger -> {
            ledger.removeIf(reservation -> reservation.task().equals(task));
            return ledger.isEmpty();
        });
    }
}
