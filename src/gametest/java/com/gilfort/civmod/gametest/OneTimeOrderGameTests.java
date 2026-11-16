package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.ClerkTabData.TaskRow;
import com.gilfort.civmod.menu.ClerkTabData.TaskStatus;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StockTab;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.OneTimeOrders;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Clerk's one-time order (#287, vault {@code Jobs/Clerk.md}): one request without a stock rule, placed
 * through the Stock tab, listed in the Tasks tab, withdrawn by the player.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class OneTimeOrderGameTests {

    private static final UUID CARL = UUID.fromString("00000000-0000-0000-0000-0000000002c3");

    private OneTimeOrderGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "one_time_order_posts_request", OneTimeOrderGameTests::postsRequest);
            register(helper, "one_time_order_without_free_rule_slot", OneTimeOrderGameTests::withoutFreeRuleSlot);
            register(helper, "one_time_order_stays_open_and_withdraws", OneTimeOrderGameTests::staysOpenAndWithdraws);
            register(helper, "one_time_order_refuses_bad_input", OneTimeOrderGameTests::refusesBadInput);
            register(helper, "one_time_order_delivered_and_clerk_removed", OneTimeOrderGameTests::deliveredAndClerkRemoved);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static StockTab stockTab(GameTestHelper helper, BlockPos clerk) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.snapTo(clerk.getX() + 0.5, clerk.getY() + 1, clerk.getZ() + 0.5);
        JobBlockEntity entity = (JobBlockEntity) helper.getLevel().getBlockEntity(clerk);
        JobBlockMenu menu = new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(ModJobs.CLERK.get(), entity.getJobLevel()));
        return menu.tab(StockTab.class).orElseThrow();
    }

    /** "Order" posts one request for the amount to this Clerk, without a rule; the Tasks tab lists it. */
    private static void postsRequest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Settlement settlement = SettlementData.get(level).settlementAt(clerk).orElseThrow();

        stockTab(helper, clerk).order(ItemKey.of(Items.BEDROCK), 3);

        List<Request> open = Requests.byPriority(settlement);
        helper.assertTrue(open.size() == 1, "One request: " + open);
        Request order = open.get(0);
        helper.assertTrue(order.item().is(Items.BEDROCK) && order.amount() == 3 && order.target().equals(Optional.of(clerk))
                && order.rule().isEmpty() && order.parent().isEmpty(), "Three bedrock for this Clerk, no rule: " + order);
        helper.assertTrue(Requests.oneTime(settlement, clerk).equals(List.of(order)), "Listed as the Clerk's one-time order");

        List<TaskRow> tasks = ClerkTabData.of(level, clerk).tasks();
        helper.assertTrue(tasks.size() == 1 && tasks.get(0).order().equals(Optional.of(order.id())), "The Tasks tab lists it: " + tasks);
        helper.assertTrue(tasks.get(0).icon().is(Items.BEDROCK) && tasks.get(0).icon().getCount() == 3, "Icon carries the amount");
        TestAssignments.unassign(SettlementData.get(level), clerk);
        helper.succeed();
    }

    /** All stock rule slots in use: a one-time order still goes through and no rule check removes it. */
    private static void withoutFreeRuleSlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Item[] items = { Items.TORCH, Items.LANTERN, Items.STICK, Items.LADDER, Items.CHEST, Items.BARREL, Items.BOWL, Items.SHEARS, Items.BUCKET };
        int slots = ClerkTabData.of(level, clerk).slots();
        for (int slot = 0; slot < slots; slot++) {
            helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(slot, ItemKey.of(items[slot]), 1, 0)), "Rule " + slot);
        }
        helper.assertTrue(ClerkTabData.of(level, clerk).nextEmptyGhostSlot() == -1, "Every rule slot is used");

        stockTab(helper, clerk).order(ItemKey.of(Items.BEDROCK), 1);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);

        helper.assertTrue(Requests.oneTime(settlement, clerk).size() == 1, "The order is placed and survives the rule check");
        helper.assertTrue(settlement.block(clerk).orElseThrow().stockRules().size() == slots, "No rule slot taken");
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /** Nobody makes the item: the order stays open and shows what is missing; the player withdraws it. */
    private static void staysOpenAndWithdraws(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        StockTab tab = stockTab(helper, clerk);
        tab.order(ItemKey.of(Items.BEDROCK), 2);
        Request order = Requests.oneTime(settlement, clerk).get(0);
        Requests.setMissing(data, settlement, order.id(), List.of(new Request.Missing(ItemKey.of(Items.COBBLESTONE), 4)));

        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        TaskRow row = ClerkTabData.of(level, clerk).tasks().get(0);
        helper.assertTrue(row.status() == TaskStatus.BLOCKED, "Blocked: " + row.status());
        helper.assertTrue(row.details().stream().anyMatch(line -> line.getString().contains("Cobblestone")), "Missing input shown: " + row.details());

        // A request that a rule posted is no order and cannot be withdrawn this way.
        StockRules.setRule(data, clerk, StockRule.minimum(0, ItemKey.of(Items.TORCH), 5, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        Request ruleRequest = Requests.forRule(settlement, clerk, 0).orElseThrow();
        tab.withdrawOrder(ruleRequest.id());
        helper.assertTrue(Requests.get(settlement, ruleRequest.id()).filter(found -> found.amount() > 0).isPresent(), "A rule request stays");

        tab.withdrawOrder(order.id());
        helper.assertTrue(Requests.oneTime(settlement, clerk).isEmpty(), "The order is withdrawn");
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /** Empty item, amount below 1 or above the limit: no request. */
    private static void refusesBadInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Settlement settlement = SettlementData.get(level).settlementAt(clerk).orElseThrow();
        StockTab tab = stockTab(helper, clerk);
        tab.order(ItemKey.EMPTY, 1);
        tab.order(ItemKey.of(Items.BEDROCK), 0);
        tab.order(ItemKey.of(Items.BEDROCK), -4);
        tab.order(ItemKey.of(Items.BEDROCK), OneTimeOrders.MAX_AMOUNT + 1);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Nothing posted: " + Requests.byPriority(settlement));
        tab.order(ItemKey.of(Items.BEDROCK), OneTimeOrders.MAX_AMOUNT);
        helper.assertTrue(Requests.oneTime(settlement, clerk).size() == 1, "The limit itself is fine");
        TestAssignments.unassign(SettlementData.get(level), clerk);
        helper.succeed();
    }

    /** A delivered order is done and gone; removing the Clerk takes its open orders with it. */
    private static void deliveredAndClerkRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        StockTab tab = stockTab(helper, clerk);
        tab.order(ItemKey.of(Items.STICK), 4);
        tab.order(ItemKey.of(Items.BEDROCK), 1);
        Request sticks = Requests.oneTime(settlement, clerk).get(0);
        helper.assertTrue(Requests.claim(data, settlement, sticks.id(), CARL, "Carl", 4) == 4, "A producer claims it");
        SubRequestGameTests.finishInFull(data, settlement, sticks.id(), CARL);
        helper.assertTrue(Requests.get(settlement, sticks.id()).isEmpty(), "Delivered: the order is done");

        SettlementManager.remove(data, clerk, 0L, SettlementManager.Listener.NONE);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "The removed Clerk's open order is gone: " + Requests.byPriority(settlement));
        helper.succeed();
    }
}
