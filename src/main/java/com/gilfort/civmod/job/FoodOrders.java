package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Producers;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The food supply of the Animal Farmer (vault {@code Jobs/Animal Farmer.md}, decisions of 2026-11-15): food for a type it
 * has none of is fetched like a tool ({@link ToolOrders}). Food that lies in the Home Clerk's store is held for the settler
 * first; otherwise a request for one of the type's foods goes to the Home Clerk, reserved for the settler, the first food a
 * producer of the settlement can make (or the first in registry order when none can). The result waits in the Clerk and
 * {@link HeldSupply} carries it into the collect inventory.
 * <p>
 * Nothing polls: the check runs when the farmer decides what to do and when a roll finds no food, and only does anything
 * while a type lacks food. The switch "Auto tool supply" of the Home Clerk covers the food too.
 */
final class FoodOrders {

    /** How many rolls of food one request asks for. */
    private static final int REQUEST_ROLLS = 8;
    private static final int REQUEST_ITEMS = REQUEST_ROLLS * AnimalFood.PER_ROLL;
    private static final Predicate<Item> SHEARS = item -> item == Items.SHEARS;

    private FoodOrders() {
    }

    /**
     * Asks for the food a type lacks and says whether the farmer has no food at all.
     *
     * @param level   the level
     * @param settler the Animal Farmer
     * @param job     its Job
     * @param site    the JobBlock of the Job
     * @param profile the Job's profile
     * @param context the production context
     * @param collect the collect inventory, read when food is counted
     * @return whether the Job could roll something but has no food for any of it
     */
    static boolean noFood(ServerLevel level, Settler settler, SettlerJob job, JobBlockSite site, FoodCostProfile profile,
            ResourceCollectionProfile.Context context, Supplier<Stock> collect) {
        JobDrops.Tools tools = JobDrops.Tools.of(level, settler, collect);
        List<List<Item>> unfed = profile.unfed(context, tools);
        if (!unfed.isEmpty() && supply(level, settler, job, site, unfed)) {
            // Food reached the collect inventory: look again with a fresh view of it.
            tools = JobDrops.Tools.of(level, settler, collect);
        }
        if (profile.noShears(context, tools) && supplyShears(level, settler, job, site)) {
            tools = JobDrops.Tools.of(level, settler, collect);
        }
        return profile.noFood(context, tools);
    }

    /** @return whether a pair of shears reached the collect inventory */
    private static boolean supplyShears(ServerLevel level, Settler settler, SettlerJob job, JobBlockSite site) {
        Optional<BlockPos> home = ToolOrders.supplyClerk(site);
        if (home.isEmpty()) {
            return false;
        }
        Settlement settlement = site.settlement();
        ReservedFor me = ReservedFor.settler(settler.getUUID());
        HeldSupply.Want want = new HeldSupply.Want(level, settlement, site, job, me, SHEARS, stack -> true);
        HeldSupply.Held held = HeldSupply.handOver(want);
        if (held != HeldSupply.Held.NOTHING) {
            return held == HeldSupply.Held.IN_COLLECT;
        }
        if (HeldSupply.onItsWay(want) || HeldSupply.asked(want).isPresent()) {
            return false;
        }
        if (reserveFromStore(level, settlement, home.get(), me, List.of(Items.SHEARS), 1)) {
            return HeldSupply.handOver(want) == HeldSupply.Held.IN_COLLECT;
        }
        Requests.postReserved(SettlementData.get(level), settlement, ItemKey.of(Items.SHEARS), 1, home.get(), me);
        return false;
    }

    /** @return whether food reached the collect inventory */
    private static boolean supply(ServerLevel level, Settler settler, SettlerJob job, JobBlockSite site, List<List<Item>> unfed) {
        Optional<BlockPos> home = ToolOrders.supplyClerk(site);
        if (home.isEmpty()) {
            return false;
        }
        boolean arrived = false;
        for (List<Item> foods : unfed) {
            arrived |= supplyFood(level, settler, job, site, home.get(), foods);
        }
        return arrived;
    }

    private static boolean supplyFood(ServerLevel level, Settler settler, SettlerJob job, JobBlockSite site, BlockPos home, List<Item> foods) {
        Settlement settlement = site.settlement();
        Set<Item> kind = Set.copyOf(foods);
        ReservedFor me = ReservedFor.settler(settler.getUUID());
        HeldSupply.Want want = new HeldSupply.Want(level, settlement, site, job, me, kind::contains, stack -> true);
        HeldSupply.Held held = HeldSupply.handOver(want);
        if (held != HeldSupply.Held.NOTHING) {
            return held == HeldSupply.Held.IN_COLLECT;
        }
        if (HeldSupply.onItsWay(want) || HeldSupply.asked(want).isPresent()) {
            return false;
        }
        if (reserveFromStore(level, settlement, home, me, foods, REQUEST_ITEMS)) {
            return HeldSupply.handOver(want) == HeldSupply.Held.IN_COLLECT;
        }
        Item food = foods.stream().filter(item -> Producers.canMake(level, settlement, ItemKey.of(item))).findFirst().orElse(foods.getFirst());
        Requests.postReserved(SettlementData.get(level), settlement, ItemKey.of(food), REQUEST_ITEMS, home, me);
        return false;
    }

    /**
     * Food of the type that lies in the Home Clerk's store is held for the settler, the first one in registry order. One
     * kind at a time, so the hand-over carries it whole.
     */
    private static boolean reserveFromStore(ServerLevel level, Settlement settlement, BlockPos home, ReservedFor me, List<Item> foods, int most) {
        Stock store = ClerkStorage.stock(level, home, true);
        for (Item food : foods) {
            ItemKey key = ItemKey.of(food);
            int count = Math.min(store.count(key), most);
            if (count > 0) {
                Holds.hold(settlement, home, me, key, count);
                return true;
            }
        }
        return false;
    }

    /**
     * The settler is gone or its Job ended: its requests for food end, and what is held for it goes back to the store.
     *
     * @param level   the level
     * @param settler the settler
     */
    static void cancel(ServerLevel level, UUID settler) {
        ToolOrders.cancel(level, settler, item -> AnimalFood.isFood(item) || SHEARS.test(item));
    }
}
