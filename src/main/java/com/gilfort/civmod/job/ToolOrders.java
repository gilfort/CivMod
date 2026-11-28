package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * The automatic tool supply (vault {@code Systems/Needs.md}, decisions of 2026-11-15): a settler of any Job with a tool
 * (fishing rod and shears included) that has no tool meeting its requirement asks the settlement for one, from skill 1.
 * A fitting tool in the Home Clerk's store is held for it and nothing is ordered (a golden tool counts there). Otherwise the
 * request goes to the Home Clerk of its JobBlock, reserved for that settler, for the highest tier its skill allows for which
 * a producer has a checked pattern; where no tier has one, for the highest tier its skill allows. Golden tools are never
 * ordered (decision of 2026-11-16). The chain of producers makes the tool ({@code Systems/Sub-Requests.md}), the result
 * waits in the Clerk for the settler ({@link Holds}), and an input delivery carries it into the collect inventory. With the
 * Clerk as collect inventory nothing needs to be carried.
 * <p>
 * The check runs inside the hourly search of the collect inventory ({@link ToolNeeds#search}) and only after a search that
 * found nothing, so it adds no polling. The same check looks at an open request: when its tree has a dead end
 * ({@link Requests#deadEnd}), the request is withdrawn and one tier lower is asked, whether or not that tier has a pattern.
 * A settler is never offered a tool that is not better than the one it carries. Tiers that need an enchanted tool stay manual until the
 * Enchanter exists. A request ends when the tool is on the belt, the Job goes away or the settler is gone
 * ({@link #cancel}).
 */
public final class ToolOrders {

    /** The tier of a settler without a tool of the kind. */
    private static final int NO_TOOL = -1;

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
     * @param tool    the kind of the Job's tool
     * @param site    the JobBlock of the Job
     * @return whether a held tool is free stock in the collect inventory now: the settler looks again at once
     */
    static boolean supply(ServerLevel level, Settler settler, SettlerJob job, ToolKind tool, JobBlockSite site) {
        ToolStaircase.Rule rule = settler.getWork().toolRule(job, tool);
        if (rule.enchanted()) {
            return false;
        }
        Settlement settlement = site.settlement();
        Optional<BlockPos> home = supplyClerk(site);
        if (home.isEmpty()) {
            return false;
        }
        ReservedFor me = ReservedFor.settler(settler.getUUID());
        Predicate<Item> kind = kindOf(tool);
        // What the settler may still gain: a tool must rank above the best allowed one it carries, so it is never offered a
        // tool that is not better (and never moves down).
        int floor = settler.getWork().tool(job, tool).map(ToolTiers::tier).orElse(NO_TOOL);
        Need need = new Need(level, settlement, site, job, me, kind, rule, home.get(), floor);
        HeldSupply.Held held = HeldSupply.handOver(need.want());
        if (held != HeldSupply.Held.NOTHING) {
            return held == HeldSupply.Held.IN_COLLECT;
        }
        if (HeldSupply.onItsWay(need.want())) {
            return false;
        }
        Optional<Request> asked = HeldSupply.asked(need.want());
        if (asked.isPresent()) {
            stepDown(need, asked.get());
            return false;
        }
        if (reserveFromStore(need)) {
            return HeldSupply.handOver(need.want()) == HeldSupply.Held.IN_COLLECT;
        }
        chooseTool(need).ifPresent(item -> Requests.postReserved(SettlementData.get(level), settlement, ItemKey.of(item), 1, home.get(), me));
        return false;
    }

    /**
     * The tool supply of an innate recipe of a Producing Job (vault {@code Systems/Producing Jobs.md}), called when the
     * recipe lacks its tool and an open request or "Always produce" wants it. The tool staircase does not apply: any tool
     * of the recipe's kind in the Home Clerk's store is held for the settler, the highest tier first. When none is in
     * stock, the request goes to the Home Clerk, reserved for the settler, for the lowest tier a producer of the
     * settlement can make, else for the lowest tier; golden tools are never ordered. A request nobody can make waits
     * with "Missing: ..." and never steps down. Same Home Clerk, same "Auto supply" switch and same hand-over as
     * {@link #supply}.
     *
     * @param level   the level
     * @param settler the settler without the tool
     * @param job     its Producing Job
     * @param site    the JobBlock of the Job
     * @param tool    the recipe's tool
     * @return whether a tool is free stock in the collect inventory now: the settler can work the recipe at once
     */
    static boolean supplyRecipeTool(ServerLevel level, Settler settler, SettlerJob job, JobBlockSite site, Ingredient tool) {
        Optional<BlockPos> home = supplyClerk(site);
        if (home.isEmpty()) {
            return false;
        }
        Settlement settlement = site.settlement();
        ReservedFor me = ReservedFor.settler(settler.getUUID());
        Predicate<Item> kind = kindOf(tool);
        HeldSupply.Want want = new HeldSupply.Want(level, settlement, site, job, me, kind, stack -> true);
        HeldSupply.Held held = HeldSupply.handOver(want);
        if (held != HeldSupply.Held.NOTHING) {
            return held == HeldSupply.Held.IN_COLLECT;
        }
        if (HeldSupply.onItsWay(want) || HeldSupply.asked(want).isPresent()) {
            return false;
        }
        Optional<ItemKey> inStore = ClerkStorage.stock(level, home.get(), true).counts().keySet().stream()
                .filter(key -> kind.test(key.item()))
                .min(Comparator.comparingInt((ItemKey key) -> -ToolTiers.tier(key.toStack())).thenComparing(vanillaFirst()));
        if (inStore.isPresent()) {
            Holds.hold(settlement, home.get(), me, inStore.get(), 1);
            return HeldSupply.handOver(want) == HeldSupply.Held.IN_COLLECT;
        }
        List<Item> cheapestFirst = ToolStaircase.orderable(kind, ToolStaircase.Rule.NONE)
                .sorted(Comparator.comparingInt((Item item) -> ToolTiers.tier(new ItemStack(item)))
                        .thenComparing(ItemKey::of, vanillaFirst()))
                .toList();
        cheapestFirst.stream().filter(item -> Producers.canMake(level, settlement, ItemKey.of(item))).findFirst()
                .or(() -> cheapestFirst.stream().findFirst())
                .ifPresent(item -> Requests.postReserved(SettlementData.get(level), settlement, ItemKey.of(item), 1, home.get(), me));
        return false;
    }

    /**
     * The settler has an innate recipe's tool on its belt or in its collect inventory: its request for that kind of tool
     * ends ({@link #cancel}).
     *
     * @param level   the level
     * @param settler the settler
     * @param tool    the recipe's tool
     */
    static void recipeToolAtHand(ServerLevel level, UUID settler, Ingredient tool) {
        cancel(level, settler, kindOf(tool));
    }

    /**
     * @param site the JobBlock of a settler
     * @return its Home Clerk, empty when it has none or the Clerk's switch "Auto supply" is off
     */
    static Optional<BlockPos> supplyClerk(JobBlockSite site) {
        return site.block().homeClerk().filter(home -> site.settlement().block(home).filter(SettlementBlock::autoSupply).isPresent());
    }

    /**
     * What the tool supply works with: the settler's need and where it stands.
     *
     * @param floor the tier of the best allowed tool the settler carries, {@link #NO_TOOL} if it has none; only a better one is asked for
     */
    private record Need(ServerLevel level, Settlement settlement, JobBlockSite site, SettlerJob job, ReservedFor me, Predicate<Item> kind,
            ToolStaircase.Rule rule, BlockPos home, int floor) {

        /** What of the settler's tools waits in the Clerk and where it goes. */
        HeldSupply.Want want() {
            return new HeldSupply.Want(level, settlement, site, job, me, kind, rule::allows);
        }
    }

    /**
     * A tool of the kind that lies in the Home Clerk's store and meets the rule is held for the settler, the best one if
     * there are several. Nothing is ordered then.
     *
     * @return whether a tool is held for the settler now
     */
    private static boolean reserveFromStore(Need need) {
        Stock store = ClerkStorage.stock(need.level(), need.home(), true);
        Optional<ItemKey> best = store.counts().keySet().stream()
                .filter(key -> need.kind().test(key.item()) && need.rule().meets(key.toStack()) && ToolTiers.tier(key.toStack()) > need.floor())
                .min(bestFirst(need.rule()));
        best.ifPresent(key -> Holds.hold(need.settlement(), need.home(), need.me(), key, 1));
        return best.isPresent();
    }

    /**
     * The hourly check of an open request: when its tree has a dead end, nobody can make the tool, so the request is
     * withdrawn and one tier lower is asked, whether or not that tier has a pattern. At the weakest tier the request stays
     * open with "Missing: ...".
     */
    private static void stepDown(Need need, Request asked) {
        if (!Requests.deadEnd(need.settlement(), asked.id())) {
            return;
        }
        int tier = ToolTiers.tier(asked.item().toStack());
        lowerTool(need, tier).ifPresent(tool -> {
            SettlementData data = SettlementData.get(need.level());
            Requests.withdrawReserved(data, need.settlement(), asked.id());
            Requests.postReserved(data, need.settlement(), ItemKey.of(tool), 1, need.home(), need.me());
        });
    }

    /**
     * The first tool to ask for: the highest tier the skill allows for which a producer of the settlement has a checked
     * pattern. Several tools of one tier: the one with a pattern first, then the order of {@link #bestFirst}. When no
     * producer can make any, the highest allowed tier is asked for anyway: the request stays open with "Missing: ..."
     * until somebody can (no timeout).
     */
    private static Optional<Item> chooseTool(Need need) {
        List<Item> ladder = ladder(need, Integer.MAX_VALUE);
        return ladder.stream().filter(item -> Producers.canMake(need.level(), need.settlement(), ItemKey.of(item))).findFirst()
                .or(() -> ladder.stream().findFirst());
    }

    /**
     * The tool to ask for after a dead end: the best tool of the next lower tier, one with a pattern first. That tier needs
     * no pattern: a lower tool is easier to make, and a producer may get a pattern later. Nothing at the weakest tier.
     */
    private static Optional<Item> lowerTool(Need need, int below) {
        List<Item> ladder = ladder(need, below);
        if (ladder.isEmpty()) {
            return Optional.empty();
        }
        int tier = ToolTiers.tier(new ItemStack(ladder.getFirst()));
        List<Item> nextTier = ladder.stream().filter(item -> ToolTiers.tier(new ItemStack(item)) == tier).toList();
        return nextTier.stream().filter(item -> Producers.canMake(need.level(), need.settlement(), ItemKey.of(item))).findFirst()
                .or(() -> nextTier.stream().findFirst());
    }

    /**
     * The items the settler may be ordered, best first: of the Job's tool kind, not golden, allowed by its
     * skill, better than the tool it carries and below the given tier. Highest tier first, then {@link #bestFirst}.
     */
    private static List<Item> ladder(Need need, int below) {
        record Rated(Item item, int tier) {
        }
        Comparator<ItemKey> order = bestFirst(need.rule());
        return ToolStaircase.orderable(need.kind(), need.rule())
                .map(item -> new Rated(item, ToolTiers.tier(new ItemStack(item))))
                .filter(rated -> rated.tier() > need.floor() && rated.tier() < below)
                .sorted(Comparator.comparingInt((Rated rated) -> -rated.tier())
                        .thenComparing((first, second) -> order.compare(ItemKey.of(first.item()), ItemKey.of(second.item()))))
                .map(Rated::item)
                .toList();
    }

    /**
     * The one order of "best tool", used for the tools in the store and for the items of the ladder: a tool that meets the
     * requirement first, then the higher tier, then an enchanted one; after that vanilla before other namespaces, then the
     * later id (so the stone tool comes before the copper one).
     *
     * @param rule the settler's rule
     * @return the comparator, the best tool first
     */
    private static Comparator<ItemKey> bestFirst(ToolStaircase.Rule rule) {
        return Comparator.comparingInt((ItemKey key) -> -rule.rank(key.toStack())).thenComparing(vanillaFirst());
    }

    /** Between tools of one rank: vanilla before other namespaces, then the later id (so the stone tool comes before the copper one). */
    private static Comparator<ItemKey> vanillaFirst() {
        return Comparator.comparing((ItemKey key) -> !BuiltInRegistries.ITEM.getKey(key.item()).getNamespace().equals(Identifier.DEFAULT_NAMESPACE))
                .thenComparing(key -> BuiltInRegistries.ITEM.getKey(key.item()).toString(), Comparator.reverseOrder());
    }

    /**
     * The settler got a tool of its requirement on the belt: its request for that kind of tool ends ({@link #cancel}).
     *
     * @param level   the level
     * @param settler the settler
     * @param job     its tool Job
     * @param tool    the kind of the Job's tool
     */
    static void settled(ServerLevel level, Settler settler, SettlerJob job, ToolKind tool) {
        Optional<ItemStack> belt = settler.getWork().tool(job, tool);
        if (belt.isPresent() && settler.getWork().toolRule(job, tool).meets(belt.get())) {
            cancel(level, settler.getUUID(), kindOf(tool));
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
        ToolKind.of(job).ifPresent(tool -> cancel(level, settler.getUUID(), kindOf(tool)));
        // A farmer asks for nothing but what its Job needs (food, shears), and which items count as food is known only for
        // the animal types seen since the last start: so everything reserved for it goes.
        job.jobType().profile(ConsumablesProfile.class).ifPresent(profile -> cancel(level, settler.getUUID(), item -> true));
        List<Ingredient> recipeTools = InnateRecipes.of(level, job.jobType()).stream().flatMap(holder -> holder.value().tool().stream()).toList();
        if (!recipeTools.isEmpty()) {
            cancel(level, settler.getUUID(), item -> recipeTools.stream().anyMatch(tool -> kindOf(tool).test(item)));
        }
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
    static void cancel(ServerLevel level, UUID settler, Predicate<Item> kind) {
        SettlementData data = SettlementData.get(level);
        ReservedFor me = ReservedFor.settler(settler);
        for (Settlement settlement : data.settlements()) {
            for (Request request : settlement.requests().stream().filter(found -> found.reservedFor().filter(me::equals).isPresent() && kind.test(found.item().item())).toList()) {
                Requests.withdrawReserved(data, settlement, request.id());
            }
            Holds.release(settlement, me, kind);
        }
    }

    static Predicate<Item> kindOf(ToolKind tool) {
        return item -> tool.isTool().test(new ItemStack(item));
    }

    private static Predicate<Item> kindOf(Ingredient tool) {
        return item -> tool.test(new ItemStack(item));
    }
}
