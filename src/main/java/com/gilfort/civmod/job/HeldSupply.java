package com.gilfort.civmod.job;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Takes what the settlement made for a settler (vault {@code Systems/Needs.md}) from the Clerk where it waits to the
 * settler's collect inventory. The tool supply ({@link ToolOrders}) and the Animal Farmer's food supply
 * ({@link ConsumableOrders}) hand their requests over the same way.
 */
final class HeldSupply {

    private HeldSupply() {
    }

    /**
     * What is held for a settler.
     *
     * @param level      the level
     * @param settlement the settlement
     * @param site       the JobBlock of the Job
     * @param job        the settler's Job there
     * @param me         who the stock is held for
     * @param kind       the kind of item that is handed over
     * @param allowed    which stacks of that kind the settler takes
     */
    record Want(ServerLevel level, Settlement settlement, JobBlockSite site, SettlerJob job, ReservedFor me, Predicate<Item> kind,
            Predicate<ItemStack> allowed) {
    }

    /** What became of the stock held for the settler. */
    enum Held {
        /** Nothing is held for it. */
        NOTHING,
        /** A delivery carries it, or it waits for one. */
        ON_ITS_WAY,
        /** It is free stock in the collect inventory now. */
        IN_COLLECT
    }

    /**
     * Stock held for the settler: at the collect inventory it is released to the settler. Elsewhere an input delivery
     * carries it and the Delivery task holds it from then on; where no Deliveryman serves the Clerk, the settler carries
     * it over to its collect inventory itself on this search (no walk is simulated), so it arrives in every mode.
     */
    static Held handOver(Want want) {
        Settlement settlement = want.settlement();
        for (Map.Entry<BlockPos, Map<ItemKey, Integer>> store : Holds.heldFor(settlement, want.me()).entrySet()) {
            Optional<Map.Entry<ItemKey, Integer>> found = store.getValue().entrySet().stream()
                    .filter(entry -> want.kind().test(entry.getKey().item()) && want.allowed().test(entry.getKey().toStack())).findFirst();
            if (found.isEmpty()) {
                continue;
            }
            BlockPos collect = want.site().collect();
            if (store.getKey().equals(collect)) {
                Holds.release(settlement, want.me(), want.kind());
                return Held.IN_COLLECT;
            }
            if (Warehouses.warehouseOf(settlement, store.getKey()).isEmpty()) {
                return carryYourself(want, store.getKey(), found.get().getKey(), found.get().getValue()) ? Held.IN_COLLECT : Held.ON_ITS_WAY;
            }
            UUID task = Deliveries.postInput(SettlementData.get(want.level()), settlement, store.getKey(), want.job().jobBlock(), collect,
                    Map.of(found.get().getKey(), found.get().getValue()), true, want.level().getGameTime());
            Holds.handOver(settlement, want.me(), ReservedFor.delivery(task), want.kind());
            return Held.ON_ITS_WAY;
        }
        return Held.NOTHING;
    }

    /**
     * @return whether an input delivery carries something of the kind to the collect inventory of the Job already
     */
    static boolean onItsWay(Want want) {
        return want.settlement().deliveries().stream().anyMatch(task -> !task.surplus() && task.jobBlock().equals(want.job().jobBlock())
                && task.input().filter(input -> input.to().equals(want.site().collect())
                        && input.items().keySet().stream().anyMatch(key -> want.kind().test(key.item()))).isPresent());
    }

    /**
     * @return the open request of the settler for an item of the kind, if it asked for one
     */
    static Optional<Request> asked(Want want) {
        return want.settlement().requests().stream()
                .filter(request -> request.reservedFor().filter(want.me()::equals).isPresent() && want.kind().test(request.item().item())).findFirst();
    }

    /**
     * Self mode: the settler takes the held items out of the Clerk and puts them into its collect inventory. What does not
     * fit goes back and stays held, so the next search tries again.
     *
     * @return whether everything is in the collect inventory now
     */
    private static boolean carryYourself(Want want, BlockPos store, ItemKey item, int count) {
        // Released first: a stock hides what is held for someone else.
        Holds.release(want.settlement(), want.me(), want.kind());
        Stock from = ClerkStorage.stock(want.level(), store, true);
        Stock to = ClerkStorage.stock(want.level(), want.site().collect(), true);
        int left = 0;
        for (ItemStack stack : from.take(item, count)) {
            left += to.insert(stack).getCount();
        }
        if (left > 0) {
            from.insert(item.toStack(left));
            Holds.hold(want.settlement(), store, want.me(), item, left);
        }
        return left < count;
    }
}
