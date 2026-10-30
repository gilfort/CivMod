package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StockTab;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Clerk's stock rules and the requests they post (#56).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StockRuleGameTests {

    private static final UUID CLARA = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID CARL = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    private StockRuleGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "stock_rules_request_amount", StockRuleGameTests::requestAmount);
            register(helper, "stock_rules_one_request_per_rule", StockRuleGameTests::oneRequestPerRule);
            register(helper, "stock_rules_inactive_off_shift", StockRuleGameTests::inactiveOffShift);
            register(helper, "stock_rules_priority_order", StockRuleGameTests::priorityOrder);
            register(helper, "stock_rules_rule_deletion", StockRuleGameTests::ruleDeletion);
            register(helper, "stock_rules_menu", StockRuleGameTests::menu);
            register(helper, "stock_rules_clerk_removed", StockRuleGameTests::clerkRemoved);
            register(helper, "stock_rules_old_request_save", StockRuleGameTests::oldRequestSave);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * A rule below its amount posts one request for X − stock − amount being crafted; checking again keeps one
     * request; rules and requests are saved.
     */
    private static void requestAmount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.STONE_PICKAXE, 1));
        ((Container) level.getBlockEntity(clerk)).setItem(0, new ItemStack(Items.STONE_PICKAXE, 1));

        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_PICKAXE, 5, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        Request request = only(helper, settlement);
        helper.assertTrue(request.item() == Items.STONE_PICKAXE && request.amount() == 3,
                "Request for 5 − 2 in stock = 3: " + request.amount());
        helper.assertTrue(request.target().equals(Optional.of(clerk)) && request.rule().equals(Optional.of(0)), "Posted by the rule of that Clerk");

        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(only(helper, settlement).id().equals(request.id()), "Checking again keeps the one request");

        helper.assertTrue(Requests.claim(data, settlement, request.id(), CARL, "Carl", 2) == 2, "Craftsperson claims 2");
        helper.assertTrue(only(helper, settlement).amount() == 1, "1 left to claim");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(only(helper, settlement).amount() == 1, "Amount being crafted counts: 5 − 2 − 2 = 1");
        helper.assertTrue(Requests.claim(data, settlement, request.id(), CARL, "Carl", 10) == 1, "Only what is open can be claimed");

        ClerkStorage.stock(level, clerk, true).insert(new ItemStack(Items.STONE_PICKAXE, 1));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        Request crafting = only(helper, settlement);
        helper.assertTrue(crafting.amount() == 0 && crafting.crafting() == 3, "Stock rose: nothing open, claims stay: " + crafting);

        Settlement loaded = Settlement.CODEC.parse(JsonOps.INSTANCE, Settlement.CODEC.encodeStart(JsonOps.INSTANCE, settlement).getOrThrow())
                .getOrThrow();
        helper.assertTrue(Requests.byPriority(loaded).equals(Requests.byPriority(settlement)), "Requests are saved");
        helper.assertTrue(loaded.block(clerk).orElseThrow().stockRules().equals(settlement.block(clerk).orElseThrow().stockRules()),
                "Rules are saved");

        Requests.finish(data, settlement, request.id(), CARL);
        Stock delivered = ClerkStorage.stock(level, clerk, true);
        delivered.insert(new ItemStack(Items.STONE_PICKAXE));
        delivered.insert(new ItemStack(Items.STONE_PICKAXE));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Delivered and stocked: no request");
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /** Each rule keeps at most one request open, however often it is checked. */
    private static void oneRequestPerRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.TORCH, 32, 0));
        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.LANTERN, 16, 0));
        StockRules.setRule(data, clerk, StockRule.minimum(2, Items.STICK, 8, 0));
        for (int i = 0; i < 3; i++) {
            StockRules.check(level, clerk, ClerkGameTests.MORNING);
        }
        helper.assertTrue(Requests.byPriority(settlement).size() == 3, "One request per rule: " + Requests.byPriority(settlement));
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).orElseThrow().amount() == 32
                && Requests.forRule(settlement, clerk, 1).orElseThrow().amount() == 16, "Each rule requests its own amount");
        Request torches = Requests.forRule(settlement, clerk, 0).orElseThrow();
        Requests.claim(data, settlement, torches.id(), CARL, "Carl", 32);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).size() == 3 && Requests.claimable(settlement).size() == 2,
                "A fully claimed request stays the rule's one request");
        TestAssignments.unassign(data, clerk);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).size() == 1, "Claimed request stays until delivered");
        Requests.finish(data, settlement, torches.id(), CARL);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "All gone");
        helper.succeed();
    }

    /**
     * Rules are active only while a Clerk settler is on shift and only in the 9 slots per JobBlock level; checks
     * outside withdraw the requests. Catch-up checks the rules once per simulated hour.
     */
    private static void inactiveOffShift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.BREAD, 10, 0));
        StockRules.setRule(data, clerk, StockRule.minimum(9, Items.APPLE, 10, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "No Clerk settler: no request");

        TestAssignments.assign(data, clerk, CLARA, "Clara", settlement.id(), List.of());
        StockRules.check(level, clerk, ClerkGameTests.EVENING);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "After the shift: no request");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).size() == 1 && Requests.forRule(settlement, clerk, 0).isPresent(),
                "On shift at level 1: only the first 9 rules are active");
        SettlementManager.setLevel(data, clerk, 2, SettlementManager.Listener.NONE);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).size() == 2, "Level 2: 18 rules active");
        SettlementManager.setLevel(data, clerk, 1, SettlementManager.Listener.NONE);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.forRule(settlement, clerk, 9).isEmpty(), "Downgrade: the extra rule is kept but inactive");
        helper.assertTrue(settlement.block(clerk).orElseThrow().stockRule(9).isPresent(), "Extra rule kept");
        StockRules.check(level, clerk, ClerkGameTests.EVENING);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Shift over: requests withdrawn");
        TestAssignments.unassign(data, clerk);

        // Catch-up: the Clerk settler checks the rules per simulated hour of its shift.
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        TestAssignments.assign(data, clerk, settler.getUUID(), "Clerk", settlement.id(), List.of());
        settler.addJob(new SettlerJob(ModJobs.CLERK.get(), clerk));
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;
        settler.getWork().setCatchUp(dayStart - 24000L, dayStart - 24000L + 3000L);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).isPresent(), "Catch-up checks the rules");
        TestAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /**
     * Requests by priority (highest first) for producers; other sources post with priority 0, sub-requests inherit
     * the priority of their parent; a changed rule priority moves the open request (and its sub-requests) at once.
     */
    private static void priorityOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.BREAD, 4, -5));
        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.STONE_AXE, 1, 50));
        StockRules.setRule(data, clerk, StockRule.minimum(2, Items.TORCH, 4, 10));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        Request axe = Requests.forRule(settlement, clerk, 1).orElseThrow();
        Request other = Requests.post(data, settlement, Items.CHEST, 1, Optional.empty(), Optional.empty());
        Request sticks = Requests.post(data, settlement, Items.STICK, 2, Optional.of(clerk), Optional.of(axe.id()));
        helper.assertTrue(other.priority() == 0, "Other sources: priority 0");
        helper.assertTrue(sticks.priority() == 50, "Sub-request inherits its parent's priority");
        List<Item> order = Requests.claimable(settlement).stream().map(Request::item).toList();
        helper.assertTrue(order.equals(List.of(Items.STONE_AXE, Items.STICK, Items.TORCH, Items.CHEST, Items.BREAD)),
                "Highest priority first: " + order);

        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.STONE_AXE, 1, -50));
        helper.assertTrue(Requests.get(settlement, axe.id()).orElseThrow().priority() == -50
                && Requests.get(settlement, sticks.id()).orElseThrow().priority() == -50, "Priority change moves the request and its sub-requests");
        helper.assertTrue(Requests.claimable(settlement).get(0).item() == Items.TORCH, "New order");
        Requests.withdraw(data, settlement, other.id());
        TestAssignments.unassign(data, clerk);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Off: all withdrawn " + Requests.byPriority(settlement));
        helper.succeed();
    }

    /**
     * Deleting a rule withdraws its request at once; a running claim is finished first. Changing the item withdraws
     * the old request.
     */
    private static void ruleDeletion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.TORCH, 16, 0));
        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.LADDER, 16, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(StockRules.removeRule(data, clerk, 0), "Rule deleted");
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).isEmpty(), "Its request is gone at once");
        helper.assertTrue(settlement.block(clerk).orElseThrow().stockRule(0).isEmpty(), "Rule gone");

        Request ladders = Requests.forRule(settlement, clerk, 1).orElseThrow();
        Requests.claim(data, settlement, ladders.id(), CARL, "Carl", 4);
        StockRules.removeRule(data, clerk, 1);
        Request running = Requests.get(settlement, ladders.id()).orElseThrow();
        helper.assertTrue(running.amount() == 0 && running.crafting() == 4, "A running craft is finished: " + running);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.get(settlement, ladders.id()).isPresent(), "Kept until delivered");
        Requests.finish(data, settlement, ladders.id(), CARL);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Delivered: gone");

        StockRules.setRule(data, clerk, StockRule.minimum(2, Items.TORCH, 16, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        StockRules.setRule(data, clerk, StockRule.minimum(2, Items.LANTERN, 16, 0));
        helper.assertTrue(Requests.forRule(settlement, clerk, 2).isEmpty(), "New item: the old request is withdrawn");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.forRule(settlement, clerk, 2).orElseThrow().item() == Items.LANTERN, "Next check: the new item");
        TestAssignments.unassign(data, clerk);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.succeed();
    }

    /**
     * The Clerk's menu: Stock and Requests tabs, the player inventory on the Stock tab, rules only in active slots,
     * deleting through the menu.
     */
    private static void menu(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.snapTo(clerk.getX() + 0.5, clerk.getY() + 1, clerk.getZ() + 0.5);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(clerk);
        JobBlockMenu menu = new JobBlockMenu(1, player.getInventory(), entity,
                JobBlockInfo.of(level, clerk, ModJobs.CLERK.get(), entity.getJobLevel()));
        helper.assertTrue(menu.getTabCount() == 4, "Storage, Workers, Stock, Tasks");
        menu.setActiveTab(menu.tab(StockTab.class).orElseThrow().index());
        helper.assertTrue(menu.slots.get(JobBlockEntity.SLOTS).isActive() && !menu.slots.get(0).isActive(),
                "Stock tab: player inventory, no storage slots");
        menu.slots.get(JobBlockEntity.SLOTS).set(new ItemStack(Items.TORCH, 3));
        helper.assertTrue(menu.quickMoveStack(player, JobBlockEntity.SLOTS).isEmpty() && entity.isEmpty()
                && menu.slots.get(JobBlockEntity.SLOTS).getItem().getCount() == 3, "Shift-click on the Stock tab moves nothing");
        menu.slots.get(JobBlockEntity.SLOTS).set(ItemStack.EMPTY);

        menu.tab(StockTab.class).orElseThrow().editRule(9, Optional.of(StockRule.minimum(9, Items.TORCH, 8, 0)));
        helper.assertTrue(settlement.block(clerk).orElseThrow().stockRule(9).isEmpty(), "Level 1: slot 9 is inactive");
        menu.tab(StockTab.class).orElseThrow().editRule(0, Optional.of(StockRule.minimum(0, Items.TORCH, 8, 0)));
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).map(Request::amount).orElse(0) == 8, "Set and checked at once");
        menu.tab(StockTab.class).orElseThrow().editRule(0, Optional.empty());
        helper.assertTrue(settlement.block(clerk).orElseThrow().stockRules().isEmpty() && Requests.byPriority(settlement).isEmpty(),
                "Deleted through the menu");
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    private static Request only(GameTestHelper helper, Settlement settlement) {
        List<Request> open = Requests.byPriority(settlement);
        helper.assertTrue(open.size() == 1, "One request: " + open);
        return open.get(0);
    }

    /** {@link ClerkGameTests#clerkWithStorage} with a Clerk settler assigned (on shift at the frozen 08:00). */
    /**
     * Removing a Clerk JobBlock withdraws the requests of its rules; other requests delivering to it lose their
     * target and go to the producer's own drop-off.
     */
    private static void clerkRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        // A second JobBlock keeps the settlement alive.
        CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_PICKAXE, 1, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        Request other = Requests.post(data, settlement, Items.STICK, 4, Optional.of(clerk), Optional.empty());
        SettlementManager.remove(data, clerk, SettlementManager.Listener.NONE);
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).isEmpty(), "Rule request withdrawn");
        helper.assertTrue(Requests.get(settlement, other.id()).map(Request::target).equals(Optional.of(Optional.empty())),
                "Other request kept without a target (own drop-off): " + Requests.get(settlement, other.id()));
        helper.succeed();
    }

    /** Requests saved before the review fixes of #54 ("rule": -1, one "missing" input) still load. */
    private static void oldRequestSave(GameTestHelper helper) {
        Request current = new Request(UUID.randomUUID(), Items.STICK, 4, 0, Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of());
        com.google.gson.JsonObject json = Request.CODEC.encodeStart(JsonOps.INSTANCE, current).getOrThrow().getAsJsonObject();
        json.addProperty("rule", -1);
        json.add("missing", com.google.gson.JsonParser.parseString("{\"item\": \"minecraft:oak_planks\", \"count\": 2}"));
        Request request = Request.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        helper.assertTrue(request.rule().isEmpty(), "-1: no rule " + request.rule());
        helper.assertTrue(request.missing().equals(List.of(new Request.Missing(Items.OAK_PLANKS, 2))), "One missing input " + request.missing());
        json.addProperty("rule", 3);
        helper.assertTrue(Request.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().rule().equals(Optional.of(3)), "Rule 3");
        helper.succeed();
    }

    static BlockPos workingClerk(GameTestHelper helper) {
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        helper.assertTrue(TestAssignments.assign(data, clerk, CLARA, "Clara", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Clerk assigned");
        return clerk;
    }
}
