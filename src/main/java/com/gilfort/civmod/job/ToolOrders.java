package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The automatic tool supply (vault {@code Systems/Needs.md}, decisions of 2026-11-13): a settler whose collect inventory
 * has no tool of the tier its skill requires asks the settlement for one. The request goes to the Home Clerk of its
 * JobBlock and is reserved for that settler; the chain of producers makes the tool ({@code Systems/Sub-Requests.md}),
 * the result waits in the Clerk for the settler ({@link Holds}), and an input delivery carries it into the collect
 * inventory. With the Clerk as collect inventory nothing needs to be carried.
 * <p>
 * The check runs inside the hourly search of the collect inventory ({@link ToolNeeds#search}) and only after a search that
 * found nothing, so it adds no polling. Tiers that need an enchanted tool stay manual until the Enchanter exists. A
 * request ends when the tool is on the belt, the Job goes away or the settler is gone ({@link #cancel}).
 */
public final class ToolOrders {

    private ToolOrders() {
    }

    /**
     * Called after a search of the collect inventory found no tool that meets the requirement. Hands a finished tool on
     * (releases it where the collect inventory is the Clerk, posts an input delivery otherwise) or, when nothing is on its
     * way, posts the request for one.
     *
     * @param level   the level
     * @param settler the settler without the tool
     * @param job     its tool Job
     * @param profile the Job's profile
     * @param site    the JobBlock of the Job
     * @return whether a held tool is free stock in the collect inventory now: the settler looks again at once
     */
    static boolean supply(ServerLevel level, Settler settler, SettlerJob job, ResourceCollectionProfile profile, JobBlockSite site) {
        ToolStaircase.Rule rule = settler.getWork().toolRule(job, profile);
        if (!rule.requiresTool() || rule.enchanted() || !ToolStaircase.appliesTo(profile)) {
            return false;
        }
        Settlement settlement = site.settlement();
        Optional<BlockPos> home = site.block().homeClerk();
        if (home.isEmpty() || settlement.block(home.get()).filter(SettlementBlock::autoToolSupply).isEmpty()) {
            return false;
        }
        ReservedFor me = ReservedFor.settler(settler.getUUID());
        Predicate<Item> kind = kindOf(profile);
        Need need = new Need(level, settlement, site, job, me, kind, rule);
        Held held = handOver(need);
        if (held != Held.NOTHING) {
            return held == Held.IN_COLLECT;
        }
        boolean onItsWay = settlement.deliveries().stream().anyMatch(task -> !task.surplus() && task.jobBlock().equals(job.jobBlock())
                && task.input().filter(input -> input.to().equals(site.collect()) && input.items().keySet().stream().anyMatch(kind)).isPresent());
        boolean asked = settlement.requests().stream().anyMatch(request -> request.reservedFor().filter(me::equals).isPresent() && kind.test(request.item()));
        if (!onItsWay && !asked) {
            chooseTool(level, settlement, kind, rule).ifPresent(tool -> Requests.postReserved(SettlementData.get(level), settlement, tool, home.get(), me));
        }
        return false;
    }

    /** What the tool supply works with: the settler's need and where it stands. */
    private record Need(ServerLevel level, Settlement settlement, JobBlockSite site, SettlerJob job, ReservedFor me, Predicate<Item> kind,
            ToolStaircase.Rule rule) {
    }

    /** What became of a tool held for the settler. */
    private enum Held {
        /** Nothing is held for it. */
        NOTHING,
        /** A delivery carries it, or it waits for one. */
        ON_ITS_WAY,
        /** It is free stock in the collect inventory now. */
        IN_COLLECT
    }

    /**
     * A tool held for the settler: at the collect inventory it is released to the settler. Elsewhere an input delivery
     * carries it and the Delivery task holds it from then on; where no Deliveryman serves the Clerk, the settler carries
     * it over to its collect inventory itself on this search (no walk is simulated), so the tool arrives in every mode.
     */
    private static Held handOver(Need need) {
        Settlement settlement = need.settlement();
        for (Map.Entry<BlockPos, Map<Item, Integer>> store : Holds.heldFor(settlement, need.me()).entrySet()) {
            Optional<Map.Entry<Item, Integer>> tool = store.getValue().entrySet().stream()
                    .filter(entry -> need.kind().test(entry.getKey()) && need.rule().meets(new ItemStack(entry.getKey()))).findFirst();
            if (tool.isEmpty()) {
                continue;
            }
            BlockPos collect = need.site().collect();
            if (store.getKey().equals(collect)) {
                Holds.release(settlement, need.me(), need.kind());
                return Held.IN_COLLECT;
            }
            if (Warehouses.warehouseOf(settlement, store.getKey()).isEmpty()) {
                return carryYourself(need, store.getKey(), tool.get().getKey(), tool.get().getValue()) ? Held.IN_COLLECT : Held.ON_ITS_WAY;
            }
            UUID task = Deliveries.postInput(SettlementData.get(need.level()), settlement, store.getKey(), need.job().jobBlock(), collect,
                    Map.of(tool.get().getKey(), tool.get().getValue()), true, need.level().getGameTime());
            Holds.handOver(settlement, need.me(), ReservedFor.delivery(task), need.kind());
            return Held.ON_ITS_WAY;
        }
        return Held.NOTHING;
    }

    /**
     * Self mode: the settler takes the held tool out of the Clerk and puts it into its collect inventory. What does not fit
     * goes back and the tool stays held, so the next search tries again.
     *
     * @return whether the tool is in the collect inventory now
     */
    private static boolean carryYourself(Need need, BlockPos store, Item tool, int count) {
        // Released first: a stock hides what is held for someone else.
        Holds.release(need.settlement(), need.me(), need.kind());
        Stock from = ClerkStorage.stock(need.level(), store, true);
        Stock to = ClerkStorage.stock(need.level(), need.site().collect(), true);
        int left = 0;
        for (ItemStack stack : from.take(tool, count)) {
            left += to.insert(stack).getCount();
        }
        if (left > 0) {
            from.insert(new ItemStack(tool, left));
            Holds.hold(need.settlement(), store, need.me(), tool, left);
        }
        return left < count;
    }

    /**
     * The tool to ask for: the weakest item of the Job's tool kind that meets the requirement and that a producer of the
     * settlement can make, so a settler gets the cheapest tool its skill asks for. When no producer can make any, the
     * weakest one is asked for anyway: the request stays open with "Missing: ..." until somebody can (no timeout).
     */
    private static Optional<Item> chooseTool(ServerLevel level, Settlement settlement, Predicate<Item> kind, ToolStaircase.Rule rule) {
        List<Item> candidates = BuiltInRegistries.ITEM.stream().filter(item -> new ItemStack(item).has(DataComponents.TOOL) && kind.test(item)
                        && rule.meets(new ItemStack(item)))
                .sorted(Comparator.comparingInt((Item item) -> ToolTiers.tier(new ItemStack(item)))
                        .thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .toList();
        return candidates.stream().filter(item -> Producers.canMake(level, settlement, item)).findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    /**
     * The settler got a tool of its requirement on the belt: its request for that kind of tool ends ({@link #cancel}).
     *
     * @param level   the level
     * @param settler the settler
     * @param job     its tool Job
     * @param profile the Job's profile
     */
    static void settled(ServerLevel level, Settler settler, SettlerJob job, ResourceCollectionProfile profile) {
        Optional<ItemStack> belt = settler.getWork().tool(job, profile);
        if (belt.isPresent() && settler.getWork().toolRule(job, profile).meets(belt.get())) {
            cancel(level, settler.getUUID(), kindOf(profile));
        }
    }

    /**
     * The settler's Job is gone (or another Job took its place): its request for that Job's tool ends.
     *
     * @param level   the level
     * @param settler the settler
     * @param job     the Job that goes away
     */
    public static void jobGone(ServerLevel level, Settler settler, SettlerJob job) {
        job.jobType().profile(ResourceCollectionProfile.class).filter(ToolStaircase::appliesTo)
                .ifPresent(profile -> cancel(level, settler.getUUID(), kindOf(profile)));
    }

    /**
     * The settler is gone: all its requests for tools end.
     *
     * @param level   the level
     * @param settler the settler
     */
    public static void settlerGone(ServerLevel level, UUID settler) {
        cancel(level, settler, item -> true);
    }

    /**
     * Ends the requests reserved for a settler and the stock held for it: a request nobody works on is withdrawn, one a
     * producer is crafting loses its reservation, so the finished tool stays as normal stock.
     */
    private static void cancel(ServerLevel level, UUID settler, Predicate<Item> kind) {
        SettlementData data = SettlementData.get(level);
        ReservedFor me = ReservedFor.settler(settler);
        for (Settlement settlement : data.settlements()) {
            for (Request request : settlement.requests().stream().filter(found -> found.reservedFor().filter(me::equals).isPresent() && kind.test(found.item())).toList()) {
                Requests.dropReservation(data, settlement, request.id());
                Requests.withdraw(data, settlement, request.id());
            }
            Holds.release(settlement, me, kind);
        }
    }

    private static Predicate<Item> kindOf(ResourceCollectionProfile profile) {
        return item -> profile.isTool(new ItemStack(item));
    }
}
