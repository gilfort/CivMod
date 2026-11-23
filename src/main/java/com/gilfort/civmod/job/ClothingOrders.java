package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.clothing.ClothingRules;
import com.gilfort.civmod.clothing.SettlerClothing;
import com.gilfort.civmod.clothing.SettlerClothing.Want;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The clothing supply (vault {@code Systems/Needs.md}, decisions of 2026-11-21): a settler with a missing or worn-out piece asks the
 * Home Clerk of one of its JobBlocks for it, the way a settler asks for a tool ({@link ToolOrders}). One request per piece, for the piece
 * of the set its tier needs ({@link Want#minRank}): there is no step down, because the need lowers the work tier by itself when the
 * cloth is missing. A fitting piece in the Clerk's store is held for the settler first; otherwise the request goes to the Clerk and
 * the Tailor makes it through the sub-requests ({@code Systems/Sub-Requests.md}). The finished piece waits in the Clerk for the settler
 * and {@link HeldSupply} carries it into the collect inventory, where the settler puts it on ({@link SettlerClothing#dress}); the worn
 * piece it replaces goes back into the collect inventory.
 * <p>
 * The check runs inside the hourly search of the collect inventory, after a search that found nothing to put on, so it adds no
 * polling. The Clerk's switch "Auto supply" turns it off. A request ends when the piece is worn ({@link #settled}), the Job goes away
 * ({@link #jobGone}) or the settler is gone ({@link ToolOrders#settlerGone}).
 */
public final class ClothingOrders {

    private ClothingOrders() {
    }

    /**
     * Where a settler's clothing is supplied: the Job whose JobBlock has a Home Clerk with the supply switched on.
     *
     * @param job  the Job
     * @param site its JobBlock
     * @param home the Home Clerk
     */
    private record Place(SettlerJob job, JobBlockSite site, BlockPos home) {
    }

    /**
     * Asks for the pieces a settler wants, takes over what the Clerk's store has and hands over what the settlement made.
     *
     * @param level   the level
     * @param settler the settler
     * @param wants   the pieces it wants, from {@link SettlerClothing#wants}
     * @return whether a piece reached the collect inventory meanwhile: the settler looks there again at once
     */
    public static boolean supply(ServerLevel level, Settler settler, List<Want> wants) {
        Optional<Place> place = place(level, settler);
        if (place.isEmpty()) {
            return false;
        }
        boolean arrived = false;
        for (Want want : wants) {
            arrived |= supply(level, settler, place.get(), want);
        }
        return arrived;
    }

    /** The first Job of the settler whose JobBlock has a Home Clerk that supplies. */
    private static Optional<Place> place(ServerLevel level, Settler settler) {
        for (SettlerJob job : settler.getJobs()) {
            Optional<Place> place = JobBlockSite.at(level, job.jobBlock())
                    .flatMap(site -> ToolOrders.supplyClerk(site).map(home -> new Place(job, site, home)));
            if (place.isPresent()) {
                return place;
            }
        }
        return Optional.empty();
    }

    /** @return whether the piece reached the collect inventory */
    private static boolean supply(ServerLevel level, Settler settler, Place place, Want want) {
        Settlement settlement = place.site().settlement();
        ReservedFor me = ReservedFor.settler(settler.getUUID());
        // A request or a held piece of an older, lower want does not fit any more: it ends here.
        ToolOrders.cancel(level, settler.getUUID(), item -> isPieceFor(item, want.slot()) && !fits(item, want));
        HeldSupply.Want held = new HeldSupply.Want(level, settlement, place.site(), place.job(), me, item -> fits(item, want),
                stack -> !ClothingRules.isWornOut(stack));
        HeldSupply.Held state = HeldSupply.handOver(held);
        if (state != HeldSupply.Held.NOTHING) {
            return state == HeldSupply.Held.IN_COLLECT;
        }
        if (HeldSupply.onItsWay(held) || HeldSupply.asked(held).isPresent()) {
            return false;
        }
        if (reserveFromStore(level, settlement, place.home(), me, want)) {
            return HeldSupply.handOver(held) == HeldSupply.Held.IN_COLLECT;
        }
        Requests.postReserved(SettlementData.get(level), settlement, ItemKey.of(ClothingRules.clothOf(want.minRank(), want.slot())), 1, place.home(), me);
        return false;
    }

    /**
     * A piece that fits the want and lies in the Home Clerk's store is held for the settler, the lowest rank first so that a better
     * set stays there for the settlers that need it.
     *
     * @return whether a piece is held for the settler now
     */
    private static boolean reserveFromStore(ServerLevel level, Settlement settlement, BlockPos home, ReservedFor me, Want want) {
        Stock store = ClerkStorage.stock(level, home, true);
        Optional<ItemKey> best = store.counts().keySet().stream().filter(key -> fits(key.item(), want))
                .min(Comparator.comparingInt((ItemKey key) -> ClothingRules.rank(key.toStack())));
        best.ifPresent(key -> Holds.hold(settlement, home, me, key, 1));
        return best.isPresent();
    }

    /**
     * The settler put a piece on in a slot (by itself or by the player's hand): what it asked for there ends, as far as the piece
     * satisfies it. A request for a higher rank stays, so a piece the Tailor is making for it is not lost.
     *
     * @param level   the level
     * @param settler the settler
     * @param slot    the slot
     * @param rank    the rank of the piece that went on
     */
    public static void settled(ServerLevel level, Settler settler, EquipmentSlot slot, int rank) {
        ToolOrders.cancel(level, settler.getUUID(), item -> isPieceFor(item, slot) && ClothingRules.rank(new ItemStack(item)) <= rank);
    }

    /**
     * A Job leaves the settler: all its requests for clothing end and are asked again from the Job it has left.
     *
     * @param level   the level
     * @param settler the settler
     */
    public static void jobGone(ServerLevel level, Settler settler) {
        ToolOrders.cancel(level, settler.getUUID(), item -> ClothingRules.isClothing(new ItemStack(item)));
    }

    /** Whether the item is a piece of clothing that fits the slot. */
    private static boolean isPieceFor(Item item, EquipmentSlot slot) {
        ItemStack stack = new ItemStack(item);
        return ClothingRules.isClothing(stack) && ClothingRules.slotOf(stack).filter(slot::equals).isPresent();
    }

    /** Whether the item is a piece for the slot of the want with at least the wanted rank. */
    private static boolean fits(Item item, Want want) {
        return isPieceFor(item, want.slot()) && ClothingRules.rank(new ItemStack(item)) >= want.minRank();
    }
}
