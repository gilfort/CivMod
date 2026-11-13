package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.gilfort.civmod.logistics.ReservedFor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * The stock a settlement's Clerks hold back for someone (vault {@code Systems/Sub-Requests.md}): the result of a
 * sub-request stays in the Clerk's store, reserved for its parent request ({@link ReservedFor}), so no other customer
 * takes it. Everybody else sees the stock minus the holds ({@link #held}, applied by {@code ClerkStorage.stock}); the
 * holder counts its hold into a plan ({@link #heldFor}) and uses it up ({@link #consume}).
 * <p>
 * A hold ends when its holder is gone ({@link #valid}), on the next read: a request that is done or withdrawn, a settler
 * that left the settlement, a Delivery task that is done. A tool made for a settler is held for the settler until it is
 * handed over to a Delivery task ({@link #handOver}) or released ({@link #release}). Kept in the {@link Settlement}, not saved: after
 * a reload the amount is free stock and the parent asks again if it still lacks it. Not the {@link Reservations} of
 * Delivery tasks, which are tied to a Deliveryman's day.
 */
public final class Holds {

    private Holds() {
    }

    /**
     * An amount of an item in a Clerk's storage held for one holder.
     *
     * @param item  the item
     * @param count how many
     * @param owner who it is held for
     */
    record Hold(Item item, int count, ReservedFor owner) {
    }

    /**
     * Holds an amount at a Clerk for a holder.
     *
     * @param settlement the settlement
     * @param clerk      the Clerk JobBlock holding the items
     * @param owner      who it is held for
     * @param item       the item
     * @param count      how many
     */
    public static void hold(Settlement settlement, BlockPos clerk, ReservedFor owner, Item item, int count) {
        if (count > 0) {
            settlement.mutableHolds().computeIfAbsent(clerk.immutable(), key -> new ArrayList<>()).add(new Hold(item, count, owner));
        }
    }

    /**
     * What a Clerk holds back; holds whose holder is gone are dropped on the way.
     *
     * @param settlement the settlement
     * @param clerk      the Clerk JobBlock
     * @return the held amounts by item, empty if nothing is held
     */
    public static Map<Item, Integer> held(Settlement settlement, BlockPos clerk) {
        List<Hold> ledger = ledger(settlement, clerk);
        if (ledger.isEmpty()) {
            return Map.of();
        }
        Map<Item, Integer> held = new HashMap<>();
        ledger.forEach(hold -> held.merge(hold.item(), hold.count(), Integer::sum));
        return held;
    }

    /**
     * What is held for one holder, in every Clerk.
     *
     * @param settlement the settlement
     * @param owner      the holder
     * @return the amounts by Clerk and item, empty if nothing is held for it
     */
    public static Map<BlockPos, Map<Item, Integer>> heldFor(Settlement settlement, ReservedFor owner) {
        Map<BlockPos, Map<Item, Integer>> held = new HashMap<>();
        for (BlockPos clerk : List.copyOf(settlement.mutableHolds().keySet())) {
            for (Hold hold : ledger(settlement, clerk)) {
                if (hold.owner().equals(owner)) {
                    held.computeIfAbsent(clerk, key -> new HashMap<>()).merge(hold.item(), hold.count(), Integer::sum);
                }
            }
        }
        return held;
    }

    /**
     * The holder uses up (part of) its hold: the amounts it takes are no longer held, anything left over stays.
     *
     * @param settlement the settlement
     * @param owner      the holder
     * @param takes      what it takes, by item
     */
    public static void consume(Settlement settlement, ReservedFor owner, Map<Item, Integer> takes) {
        Map<Item, Integer> rest = new HashMap<>(takes);
        for (List<Hold> ledger : settlement.mutableHolds().values()) {
            List<Hold> kept = new ArrayList<>();
            for (Hold hold : ledger) {
                int used = hold.owner().equals(owner) ? Math.min(rest.getOrDefault(hold.item(), 0), hold.count()) : 0;
                rest.merge(hold.item(), -used, Integer::sum);
                if (hold.count() > used) {
                    kept.add(new Hold(hold.item(), hold.count() - used, hold.owner()));
                }
            }
            ledger.clear();
            ledger.addAll(kept);
        }
        settlement.mutableHolds().values().removeIf(List::isEmpty);
    }

    /**
     * Ends the holds of a holder (the tool is free stock again).
     *
     * @param settlement the settlement
     * @param owner      the holder
     * @param kind       which items: only holds of items that pass
     */
    public static void release(Settlement settlement, ReservedFor owner, Predicate<Item> kind) {
        settlement.mutableHolds().values().forEach(ledger -> ledger.removeIf(hold -> hold.owner().equals(owner) && kind.test(hold.item())));
        settlement.mutableHolds().values().removeIf(List::isEmpty);
    }

    /**
     * Hands what a holder holds over to another holder (the tool made for a settler goes to the Delivery task that carries
     * it).
     *
     * @param settlement the settlement
     * @param from       the holder now
     * @param to         the holder after
     * @param kind       which items: only holds of items that pass
     */
    public static void handOver(Settlement settlement, ReservedFor from, ReservedFor to, Predicate<Item> kind) {
        settlement.mutableHolds().values().forEach(ledger -> ledger.replaceAll(hold -> hold.owner().equals(from) && kind.test(hold.item())
                ? new Hold(hold.item(), hold.count(), to) : hold));
    }

    /** The valid holds of a Clerk; holds of a gone holder are dropped. */
    private static List<Hold> ledger(Settlement settlement, BlockPos clerk) {
        List<Hold> ledger = settlement.mutableHolds().get(clerk);
        if (ledger == null) {
            return List.of();
        }
        ledger.removeIf(hold -> !valid(settlement, hold));
        if (ledger.isEmpty()) {
            settlement.mutableHolds().remove(clerk);
        }
        return ledger;
    }

    /**
     * A hold lasts as long as its holder: a request while it is still there (open or being worked on), a settler while it
     * belongs to the settlement, a Delivery task while it is open.
     */
    private static boolean valid(Settlement settlement, Hold hold) {
        return switch (hold.owner().kind()) {
            case REQUEST -> Requests.get(settlement, hold.owner().id()).isPresent();
            case SETTLER -> settlement.members().contains(hold.owner().id());
            case DELIVERY -> Deliveries.get(settlement, hold.owner().id()).isPresent();
        };
    }
}
