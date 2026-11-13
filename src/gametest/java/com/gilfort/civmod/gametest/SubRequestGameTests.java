package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.SubRequests;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for sub-requests (#241, vault {@code Systems/Sub-Requests.md}): a producer that lacks an input it cannot make
 * itself asks the settlement; the child's result waits in the Clerk for the parent request.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SubRequestGameTests {

    /** Work time of the previous workday each pass catches up. */
    private static final long WORK_TICKS = 4000L;
    /** Most passes the example needs: post, make, then the parent. */
    private static final int MAX_PASSES = 4;

    private SubRequestGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "sub_request_smith_sticks", SubRequestGameTests::smithSticks);
            register(helper, "sub_request_every_profile", SubRequestGameTests::everyProfile);
            register(helper, "sub_request_withdraw", SubRequestGameTests::withdraw);
            register(helper, "sub_request_limits", SubRequestGameTests::limits);
            register(helper, "sub_request_tasks_tab", SubRequestGameTests::tasksTab);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A working Clerk whose storage holds the items. */
    private static BlockPos clerkWith(GameTestHelper helper, List<ItemStack> stock) {
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < stock.size(); i++) {
            chest.setItem(i, stock.get(i).copy());
        }
        return clerk;
    }

    private static int count(ServerLevel level, BlockPos clerk, Item item) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(item));
    }

    /** A settler working a Job at a JobBlock. */
    private static Settler worker(GameTestHelper helper, SettlementData data, Settlement settlement, BlockPos jobBlock, BlockPos at, String name, Job job) {
        Settler settler = ClerkGameTests.settler(helper, at, data, settlement);
        settler.setCustomName(Component.literal(name));
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), name, settlement.id(), List.of(), job)
                == JobAssignments.AssignResult.OK, name + " assigned");
        settler.addJob(new SettlerJob(job, jobBlock));
        return settler;
    }

    /** Catches up some hours of the previous workday for the settlers one after the other. */
    private static void catchUp(ServerLevel level, Settler... settlers) {
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;
        for (Settler settler : settlers) {
            settler.getWork().setCatchUp(dayStart - 24000L, dayStart - 24000L + WORK_TICKS);
        }
        CatchUp.runNow(level, List.of(settlers));
    }

    /**
     * Example of the issue: a Clerk's Min rule asks for an iron pickaxe, the Smith lacks sticks (outside its domain), a
     * Craftsperson makes them from a log (planks on the way). The Smith posts a child request for the sticks at the Clerk, the Craftsperson
     * claims it, the sticks wait in the Clerk for the pickaxe request (nobody else sees them) and the pickaxe arrives.
     */
    private static void smithSticks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.OAK_LOG, 1)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.IRON_PICKAXE, 1, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        UUID pickaxe = Requests.claimable(settlement).getFirst().id();

        BlockPos smithy = SmithGameTests.smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        BlockPos workshop = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 9));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 9));
        Settler smith = worker(helper, data, settlement, smithy, new BlockPos(2, 1, 7), "Sigrun", SmithGameTests.smith());
        Settler crafter = worker(helper, data, settlement, workshop, new BlockPos(2, 1, 11), "Carl", CraftspersonGameTests.craftJob());
        helper.assertTrue(Patterns.setOutput(level, smithy, SmithGameTests.smith(), 0, Items.IRON_PICKAXE).orElseThrow().checked(), "Pickaxe checked");
        Patterns.setOutput(level, smithy, SmithGameTests.smith(), 1, Items.STICK);
        CraftspersonGameTests.define(helper, workshop, 0, Items.STICK);
        CraftspersonGameTests.define(helper, workshop, 1, Items.OAK_PLANKS);

        // The Smith alone: it lacks sticks, asks for them and waits.
        catchUp(level, smith);
        List<Request> children = Requests.children(settlement, pickaxe);
        helper.assertTrue(children.size() == 1 && children.getFirst().item() == Items.STICK, "A child request for sticks: " + children);
        Request child = children.getFirst();
        helper.assertTrue(child.amount() == 2 && child.target().equals(Optional.of(clerk)) && child.parent().equals(Optional.of(pickaxe))
                && child.rule().isEmpty(), "Two sticks for the Clerk, parent set: " + child);
        helper.assertTrue(count(level, clerk, Items.IRON_PICKAXE) == 0, "No pickaxe yet");

        // The Craftsperson makes them; they stay at the Clerk for the pickaxe request.
        catchUp(level, crafter);
        helper.assertTrue(Requests.children(settlement, pickaxe).isEmpty(), "The child is done: " + Requests.children(settlement, pickaxe));
        helper.assertTrue(Holds.heldFor(settlement, ReservedFor.request(pickaxe)).getOrDefault(clerk, Map.of()).getOrDefault(Items.STICK, 0) == 2,
                "Two sticks held for the pickaxe request: " + Holds.heldFor(settlement, ReservedFor.request(pickaxe)));
        // One craft of planks makes 4 sticks; the 2 that were asked for are held, the 2 left over are free stock.
        helper.assertTrue(count(level, clerk, Items.STICK) == 2, "Nobody else sees the 2 held sticks: " + count(level, clerk, Items.STICK));

        // The Smith takes them.
        for (int pass = 0; pass < MAX_PASSES && count(level, clerk, Items.IRON_PICKAXE) == 0; pass++) {
            catchUp(level, smith);
        }
        helper.assertTrue(count(level, clerk, Items.IRON_PICKAXE) == 1, "The pickaxe arrived (status " + smith.getWork().status(smithy) + ")");
        helper.assertTrue(Holds.held(settlement, clerk).isEmpty(), "Nothing is held any more: " + Holds.held(settlement, clerk));
        TestAssignments.unassign(data, smithy);
        TestAssignments.unassign(data, workshop);
        smith.discard();
        crafter.discard();
        helper.succeed();
    }

    /**
     * Every registered Producing Job (addons included) takes part in the chain: with a checked pattern it is asked for the
     * item as a child request (parent, priority, Clerk), its result waits in the Clerk for the parent, nobody else sees
     * it, the parent uses it up; a withdrawn parent frees it. Delivery mode changes only the transport, so this runs on
     * the shared request and stock path.
     */
    private static void everyProfile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        int index = 0;
        int checkedJobs = 0;
        for (Job job : ModJobs.REGISTRY) {
            if (job.category() != JobCategory.PRODUCING || job.parent().isPresent() || job.profile(ProducingProfile.class).isEmpty()) {
                continue;
            }
            BlockPos pos = helper.absolutePos(new BlockPos(1 + 3 * (index++ % 8), 1, 6 + 3 * (index / 8)));
            TestSettlements.place(helper, pos, job, 1);
            Optional<Item> output = makeableItem(level, pos, job);
            if (output.isEmpty()) {
                continue;
            }
            checkedJobs++;
            helper.assertTrue(TestAssignments.assign(data, pos, UUID.randomUUID(), "Worker", settlement.id(), List.of(), job)
                    == JobAssignments.AssignResult.OK, job + " assigned");
            chain(helper, data, settlement, clerk, job, output.get());
        }
        helper.assertTrue(checkedJobs > 1, "More than one Producing Job took part: " + checkedJobs);
        helper.succeed();
    }

    /** The first item the Job's library can make with a checked pattern (slot 0), empty if it has no recipe. */
    private static Optional<Item> makeableItem(ServerLevel level, BlockPos pos, Job job) {
        List<net.minecraft.world.item.crafting.RecipeType<?>> types = Patterns.profile(job).recipeTypes();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!PatternRecipes.producing(level, types, item).isEmpty()
                    && Patterns.setOutput(level, pos, job, 0, item).filter(pattern -> pattern.checked() || Patterns.toggleCheck(level, pos, job, 0)).isPresent()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /** Parent, child, store, parent done - and the same with a parent that is withdrawn instead. */
    private static void chain(GameTestHelper helper, SettlementData data, Settlement settlement, BlockPos clerk, Job job, Item item) {
        ServerLevel level = helper.getLevel();
        UUID producer = UUID.randomUUID();
        int before = count(level, clerk, item);
        for (boolean withdrawn : new boolean[] {false, true}) {
            Request parent = Requests.post(data, settlement, Items.BEDROCK, 1, Optional.of(clerk), Optional.empty());
            helper.assertTrue(SubRequests.post(level, data, settlement, parent, List.of(new Request.Missing(item, 2))) == 1,
                    job + ": a child request for " + item);
            Request child = Requests.children(settlement, parent.id()).getFirst();
            helper.assertTrue(child.item() == item && child.amount() == 2 && child.priority() == parent.priority() && child.parent().equals(Optional.of(parent.id()))
                    && child.target().equals(Optional.of(clerk)), job + ": child of the parent: " + child);
            helper.assertTrue(Requests.claim(data, settlement, child.id(), producer, "Worker", 2) == 2, job + ": claimed");
            helper.assertTrue(ClerkStorage.dropOff(level, clerk).insert(new ItemStack(item, 2)).isEmpty(), job + ": result stored");
            Requests.finish(data, settlement, child.id(), producer);
            helper.assertTrue(Requests.children(settlement, parent.id()).isEmpty(), job + ": child done");
            helper.assertTrue(count(level, clerk, item) == before, job + ": held for the parent, nobody else sees it");
            helper.assertTrue(Holds.heldFor(settlement, ReservedFor.request(parent.id())).getOrDefault(clerk, Map.of()).getOrDefault(item, 0) == 2,
                    job + ": held for the parent");
            if (withdrawn) {
                Requests.withdraw(data, settlement, parent.id());
            } else {
                Holds.consume(settlement, ReservedFor.request(parent.id()), Map.of(item, 2));
                Requests.withdraw(data, settlement, parent.id());
            }
            helper.assertTrue(Holds.held(settlement, clerk).isEmpty(), job + ": nothing held after the parent");
            helper.assertTrue(count(level, clerk, item) == before + 2, job + ": the items are free stock again");
            ClerkStorage.stock(level, clerk, true).take(item, 2);
        }
    }

    /**
     * Withdrawing a parent withdraws its children; a child nobody claimed disappears with it.
     */
    private static void withdraw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos workshop = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 9));
        CraftspersonGameTests.define(helper, workshop, 0, Items.STICK);
        helper.assertTrue(TestAssignments.assign(data, workshop, UUID.randomUUID(), "Carl", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Craftsperson assigned");
        Request parent = Requests.post(data, settlement, Items.IRON_PICKAXE, 1, Optional.of(clerk), Optional.empty());
        helper.assertTrue(SubRequests.post(level, data, settlement, parent, List.of(new Request.Missing(Items.STICK, 2))) == 1, "A child");
        helper.assertTrue(SubRequests.post(level, data, settlement, parent, List.of(new Request.Missing(Items.STICK, 2))) == 0,
                "Asking again posts no second child");
        Requests.withdraw(data, settlement, parent.id());
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Parent and child are gone: " + Requests.byPriority(settlement));
        helper.succeed();
    }

    /**
     * Limits: nobody who can make the item means no child ("Missing: …" stays), an item along the parent chain is no
     * child (cycle), and a chain stops at depth {@code CraftPlan.MAX_DEPTH}.
     */
    private static void limits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Request root = Requests.post(data, settlement, Items.IRON_PICKAXE, 1, Optional.of(clerk), Optional.empty());
        helper.assertTrue(SubRequests.post(level, data, settlement, root, List.of(new Request.Missing(Items.STICK, 2))) == 0,
                "Nobody makes sticks: no child");
        BlockPos workshop = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 9));
        CraftspersonGameTests.define(helper, workshop, 0, Items.STICK);
        helper.assertTrue(TestAssignments.assign(data, workshop, UUID.randomUUID(), "Carl", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Craftsperson assigned");
        Request sticks = Requests.post(data, settlement, Items.STICK, 2, Optional.of(clerk), Optional.of(root.id()));
        helper.assertTrue(SubRequests.post(level, data, settlement, sticks, List.of(new Request.Missing(Items.STICK, 2))) == 0,
                "An item along the parent chain: no child (cycle)");
        Request deep = sticks;
        for (int depth = 2; depth <= 8; depth++) {
            deep = Requests.post(data, settlement, Items.DIRT, 1, Optional.of(clerk), Optional.of(deep.id()));
        }
        helper.assertTrue(SubRequests.post(level, data, settlement, deep, List.of(new Request.Missing(Items.STICK, 1))) == 0,
                "Eight parents above: no deeper child");
        helper.assertTrue(SubRequests.post(level, data, settlement, root, List.of(new Request.Missing(Items.STICK, 2))) == 0,
                "The root already has its child");
        helper.succeed();
    }

    /**
     * The Tasks tab lists open sub-requests indented under their main request; fulfilled ones are not listed.
     */
    private static void tasksTab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos workshop = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 9));
        CraftspersonGameTests.define(helper, workshop, 0, Items.STICK);
        helper.assertTrue(TestAssignments.assign(data, workshop, UUID.randomUUID(), "Carl", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Craftsperson assigned");
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.IRON_PICKAXE, 1, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        Request parent = Requests.claimable(settlement).getFirst();
        SubRequests.post(level, data, settlement, parent, List.of(new Request.Missing(Items.STICK, 2)));
        List<ClerkTabData.TaskRow> rows = ClerkTabData.of(level, clerk).tasks();
        helper.assertTrue(rows.size() == 2 && rows.get(0).depth() == 0 && rows.get(1).depth() == 1 && rows.get(1).icon().is(Items.STICK),
                "A main request with its sub-request below: " + rows);
        Request child = Requests.children(settlement, parent.id()).getFirst();
        UUID producer = UUID.randomUUID();
        Requests.claim(data, settlement, child.id(), producer, "Carl", 2);
        Requests.finish(data, settlement, child.id(), producer);
        helper.assertTrue(ClerkTabData.of(level, clerk).tasks().size() == 1, "A fulfilled sub-request disappears");
        helper.succeed();
    }
}
