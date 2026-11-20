package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.Set;

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

/**
 * What the Animal Farmer's work uses up, fetched like a tool ({@link ToolOrders}; vault {@code Jobs/Animal Farmer.md},
 * decisions of 2026-11-15): the food of a type it has none of, and shears. A thing that lies in the Home Clerk's store is held
 * for the settler first; otherwise a request goes to the Home Clerk, reserved for the settler, for the first item of the kind
 * a producer of the settlement can make (or the first in registry order when none can). The result waits in the Clerk and
 * {@link HeldSupply} carries it into the collect inventory.
 * <p>
 * Nothing polls: the check runs before every roll and only does anything while something is missing. The switch "Auto tool
 * supply" of the Home Clerk covers it too.
 */
final class ConsumableOrders {

    /**
     * Who is supplied.
     *
     * @param level   the level
     * @param settler the Animal Farmer
     * @param job     its Job
     * @param site    the JobBlock of the Job
     */
    record Worker(ServerLevel level, Settler settler, SettlerJob job, JobBlockSite site) {
    }

    private ConsumableOrders() {
    }

    /**
     * Asks for what the collect inventory lacks, and takes over what lies in the store. When something reached the collect
     * inventory the view is read again.
     *
     * @param who     the settler and its Job
     * @param profile the Job's profile
     * @param context the production context
     * @param tools   the collect inventory, one view for the whole roll
     * @return what is still lacking after that; {@link ConsumablesProfile.Needs#stuck()} says the Job has no food
     */
    static ConsumablesProfile.Needs orderMissing(Worker who, ConsumablesProfile profile, ResourceCollectionProfile.Context context, CollectTools tools) {
        ConsumablesProfile.Needs needs = profile.needs(context, tools);
        if (needs.missing().isEmpty()) {
            return needs;
        }
        Optional<BlockPos> home = ToolOrders.supplyClerk(who.site());
        if (home.isEmpty()) {
            return needs;
        }
        boolean arrived = false;
        for (ConsumablesProfile.Need need : needs.missing()) {
            arrived |= supply(who, home.get(), need);
        }
        if (!arrived) {
            return needs;
        }
        // Something reached the collect inventory: look again with a fresh view of it.
        tools.refresh();
        return profile.needs(context, tools);
    }

    /** @return whether the need reached the collect inventory */
    private static boolean supply(Worker who, BlockPos home, ConsumablesProfile.Need need) {
        ServerLevel level = who.level();
        Settlement settlement = who.site().settlement();
        ReservedFor me = ReservedFor.settler(who.settler().getUUID());
        Set<Item> kind = Set.copyOf(need.items());
        HeldSupply.Want want = new HeldSupply.Want(level, settlement, who.site(), who.job(), me, kind::contains, stack -> true);
        HeldSupply.Held held = HeldSupply.handOver(want);
        if (held != HeldSupply.Held.NOTHING) {
            return held == HeldSupply.Held.IN_COLLECT;
        }
        if (HeldSupply.onItsWay(want) || HeldSupply.asked(want).isPresent()) {
            return false;
        }
        if (reserveFromStore(want, home, need)) {
            return HeldSupply.handOver(want) == HeldSupply.Held.IN_COLLECT;
        }
        Item item = need.items().stream().filter(candidate -> Producers.canMake(level, settlement, ItemKey.of(candidate))).findFirst()
                .orElse(need.items().getFirst());
        Requests.postReserved(SettlementData.get(level), settlement, ItemKey.of(item), need.amount(), home, me);
        return false;
    }

    /**
     * What of the need lies in the Home Clerk's store is held for the settler, the first item in registry order. One kind at
     * a time, so the hand-over carries it whole.
     */
    private static boolean reserveFromStore(HeldSupply.Want want, BlockPos home, ConsumablesProfile.Need need) {
        Stock store = ClerkStorage.stock(want.level(), home, true);
        for (Item item : need.items()) {
            ItemKey key = ItemKey.of(item);
            int count = Math.min(store.count(key), need.amount());
            if (count > 0) {
                Holds.hold(want.settlement(), home, want.me(), key, count);
                return true;
            }
        }
        return false;
    }
}
