package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTab;
import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.Warehouses;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
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
 * GameTests for the stock rule Min / Max / Order, the Max stop of collecting jobs and surplus (#73).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MinMaxOrderGameTests {

    private static final long DAY = 24000L;
    private static final Identifier OAK_LOG = Identifier.withDefaultNamespace("oak_log");
    private static final Identifier STONE = Identifier.withDefaultNamespace("stone");
    private static final Identifier GRANITE = Identifier.withDefaultNamespace("granite");

    private MinMaxOrderGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "min_max_order_request_amounts", MinMaxOrderGameTests::requestAmounts);
            register(helper, "min_max_order_old_minimum_rule", MinMaxOrderGameTests::oldMinimumRule);
            register(helper, "min_max_order_collecting_stops_at_max", MinMaxOrderGameTests::collectingStopsAtMax);
            register(helper, "min_max_order_dropped_item_at_max", MinMaxOrderGameTests::droppedItemAtMax);
            register(helper, "min_max_order_surplus", MinMaxOrderGameTests::surplus);
            register(helper, "min_max_order_surplus_write_through", MinMaxOrderGameTests::surplusWriteThrough);
            register(helper, "min_max_order_duplicate_rules_on_load", MinMaxOrderGameTests::duplicateRulesOnLoad);
        });
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                total += container.getItem(i).getCount();
            }
        }
        return total;
    }

    /** A Clerk JobBlock at a relative position, level 1 (no settler). */
    private static BlockPos clerk(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, ModBlocks.CLERK_BLOCK.get());
        return helper.absolutePos(relative);
    }

    /**
     * A Lumberjack whose Home Clerk holds its Max of oak logs leaves oak out: with only oak around the job pauses with
     * "Maximum reached" and the Found tab marks oak "Maximum reached at &lt;Clerk&gt;"; below Max it cuts oak again
     * (after the 30 s re-check).
     */
    private static void collectingStopsAtMax(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        BlockPos clerk = clerk(helper, new BlockPos(1, 1, 7));
        SettlementData data = SettlementData.get(level);
        helper.assertTrue(JobBlockSite.blockAt(level, jobBlock).orElseThrow().homeClerk().equals(Optional.of(clerk)), "Home Clerk");
        helper.assertTrue(StockRules.setRule(data, clerk, new StockRule(0, Items.OAK_LOG, 0, 4, 0, 0)), "Max rule set");
        Container store = (Container) level.getBlockEntity(clerk);
        store.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        int[] before = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.MAX_REACHED,
                        "Not paused at Max: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> {
                    FoundTab tab = FoundTab.of(level, jobBlock);
                    helper.assertTrue(tab.entry(OAK_LOG).flatMap(FoundTab.Entry::maxAt).isPresent()
                            && !tab.entry(OAK_LOG).orElseThrow().excluded(), "Oak marked as at Max, not excluded: " + tab);
                    before[0] = settler.getWork().buffer(jobBlock).count() + count((Container) level.getBlockEntity(jobBlock), Items.OAK_LOG);
                    store.setItem(0, new ItemStack(Items.OAK_LOG, 3));
                })
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().buffer(jobBlock).count()
                        + count((Container) level.getBlockEntity(jobBlock), Items.OAK_LOG) > before[0],
                        "No oak below Max (status " + settler.getWork().status(jobBlock) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(FoundTab.of(level, jobBlock).entry(OAK_LOG).flatMap(FoundTab.Entry::maxAt).isEmpty(), "Mark gone below Max");
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * The Max check uses the dropped item: a Miner with a plain pickaxe and a Max of cobblestone at its Home Clerk
     * still mines granite but no stone (stone drops cobblestone); a caught-up day yields granite only.
     */
    private static void droppedItemAtMax(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos jobBlockRelative = new BlockPos(1, 1, 1);
        helper.setBlock(jobBlockRelative, ModBlocks.MINER_BLOCK.get());
        for (int x = 4; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), Blocks.GRANITE);
        }
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        BlockPos clerk = clerk(helper, new BlockPos(1, 1, 7));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        StockRules.setRule(data, clerk, new StockRule(0, Items.COBBLESTONE, 0, 1, 0, 0));
        ((Container) level.getBlockEntity(clerk)).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.IRON_PICKAXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(5, 1, 5)), true, false);
        helper.assertTrue(JobAssignments.assign(data, jobBlock, settler.getUUID(), "Digger", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), jobBlock));
        settler.setNoAi(false);
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || !FoundTab.of(level, jobBlock).scanned()) {
                return;
            }
            done[0] = true;
            FoundTab tab = FoundTab.of(level, jobBlock);
            helper.assertTrue(tab.entry(STONE).flatMap(FoundTab.Entry::maxAt).isPresent(), "Stone drops cobblestone, at Max: " + tab);
            helper.assertTrue(tab.entry(GRANITE).flatMap(FoundTab.Entry::maxAt).isEmpty(), "Granite is not at Max: " + tab);
            Container container = (Container) level.getBlockEntity(jobBlock);
            int cobbleBefore = count(container, Items.COBBLESTONE);
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            int cobble = count(container, Items.COBBLESTONE) - cobbleBefore + settler.getWork().buffer(jobBlock).stacks().stream()
                    .filter(stack -> stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
            int granite = count(container, Items.GRANITE) + settler.getWork().buffer(jobBlock).stacks().stream()
                    .filter(stack -> stack.is(Items.GRANITE)).mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(cobble == 0, "Cobblestone at Max produced: " + cobble);
            helper.assertTrue(granite > 0, "Granite still mined");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /**
     * Stock above Max at a Served Clerk becomes a surplus task: the Warehouse's Deliveryman carries it into the
     * Warehouse's store. A Warehouse at its own Max takes nothing more ("Surplus: nowhere to go" at the Served Clerk),
     * and so does a Warehouse for its own surplus.
     */
    private static void surplus(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos warehouse = clerk(helper, new BlockPos(1, 1, 1));
        BlockPos served = clerk(helper, new BlockPos(6, 1, 6));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();
        Settler dora = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);
        dora.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 16));
        helper.assertTrue(JobAssignments.assign(data, warehouse, dora.getUUID(), "Dora", settlement.id(), List.of(), ModJobs.DELIVERYMAN.get())
                == JobAssignments.AssignResult.OK, "Deliveryman assigned");
        dora.addJob(new SettlerJob(ModJobs.DELIVERYMAN.get(), warehouse));
        helper.assertTrue(Warehouses.link(data, warehouse, served) == Warehouses.LinkResult.LINKED, "Served Clerk linked");

        StockRules.setRule(data, served, new StockRule(0, Items.TORCH, 0, 10, 0, 0));
        Container servedStore = (Container) level.getBlockEntity(served);
        Container warehouseStore = (Container) level.getBlockEntity(warehouse);
        servedStore.setItem(0, new ItemStack(Items.TORCH, 30));
        StockRules.check(level, served, ClerkGameTests.MORNING);
        helper.assertTrue(Deliveries.freeSurplus(settlement, served, Items.TORCH, DayClock.day(level))
                .map(task -> task.input().orElseThrow().count()).orElse(0) == 20, "A surplus task for 30 − 10 = 20");
        helper.assertTrue(StockRules.surplusNowhere(level, served).isEmpty(), "The surplus has somewhere to go");
        dora.setNoAi(false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(count(warehouseStore, Items.TORCH) == 20,
                        "Surplus not in the Warehouse yet: " + count(warehouseStore, Items.TORCH) + " (" + dora.getWork().statusText(warehouse).getString()
                                + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(count(servedStore, Items.TORCH) == 10, "Max stays at the Served Clerk");
                    helper.assertTrue(settlement.deliveries().stream().noneMatch(task -> task.surplus()), "Surplus task done");
                    // The Warehouse is at its own Max: more surplus has nowhere to go.
                    StockRules.setRule(data, warehouse, new StockRule(0, Items.TORCH, 0, 20, 0, 0));
                    servedStore.setItem(1, new ItemStack(Items.TORCH, 5));
                    StockRules.check(level, served, ClerkGameTests.MORNING);
                    helper.assertTrue(settlement.deliveries().stream().noneMatch(task -> task.surplus()), "No task into a Warehouse at Max");
                    helper.assertTrue(StockRules.surplusNowhere(level, served).equals(List.of(0)), "Surplus: nowhere to go");
                    helper.assertTrue(ClerkTab.of(level, served).nowhere().equals(List.of(0)), "Shown in the Clerk's tab");
                    // The Warehouse keeps its own surplus.
                    warehouseStore.setItem(5, new ItemStack(Items.TORCH, 3));
                    StockRules.check(level, warehouse, ClerkGameTests.MORNING);
                    helper.assertTrue(settlement.deliveries().stream().noneMatch(task -> task.surplus()), "A Warehouse keeps its surplus");
                    helper.assertTrue(StockRules.surplusNowhere(level, warehouse).equals(List.of(0)), "Warehouse: nowhere to go");
                    JobAssignments.unassign(data, warehouse);
                    dora.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * CivMod's own stock change of an item with a Max rule creates the surplus task at once (write-through), without
     * waiting for the 30 s rule check; an item without a Max rule creates none.
     */
    private static void surplusWriteThrough(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos warehouse = clerk(helper, new BlockPos(1, 1, 1));
        BlockPos served = clerk(helper, new BlockPos(6, 1, 6));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();
        helper.assertTrue(Warehouses.link(data, warehouse, served) == Warehouses.LinkResult.LINKED, "Served Clerk linked");
        StockRules.setRule(data, served, new StockRule(0, Items.TORCH, 0, 10, 0, 0));
        ClerkStorage.stock(level, served, true).insert(new ItemStack(Items.TORCH, 30));
        ClerkStorage.stock(level, served, true).insert(new ItemStack(Items.STICK, 30));
        // The write-through runs at the end of the tick, long before the 30 s check comes round for most positions.
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Deliveries.freeSurplus(settlement, served, Items.TORCH, DayClock.day(level))
                            .map(task -> task.input().orElseThrow().count()).orElse(0) == 20, "A surplus task for 30 − 10 = 20 right away");
                    helper.assertTrue(Deliveries.freeSurplus(settlement, served, Items.STICK, DayClock.day(level)).isEmpty(),
                            "No Max rule for sticks: no surplus");
                })
                .thenSucceed();
    }

    /** An old save with two rules for one item keeps only the first (one rule per item and Clerk). */
    private static void duplicateRulesOnLoad(GameTestHelper helper) {
        BlockPos clerk = clerk(helper, new BlockPos(1, 1, 1));
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        StockRules.setRule(data, clerk, new StockRule(0, Items.TORCH, 0, 10, 0, 0));
        StockRules.setRule(data, clerk, new StockRule(1, Items.STICK, 0, 20, 0, 0));
        JsonObject saved = SettlementBlock.CODEC.encodeStart(JsonOps.INSTANCE, settlement.block(clerk).orElseThrow()).getOrThrow().getAsJsonObject();
        saved.getAsJsonArray("stock_rules").add(StockRule.CODEC.encodeStart(JsonOps.INSTANCE, new StockRule(2, Items.TORCH, 5, 50, 0, 0)).getOrThrow());
        SettlementBlock loaded = SettlementBlock.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        helper.assertTrue(loaded.stockRules().equals(List.of(new StockRule(0, Items.TORCH, 0, 10, 0, 0), new StockRule(1, Items.STICK, 0, 20, 0, 0))),
                "The first torch rule stays, the duplicate is dropped: " + loaded.stockRules());
        helper.succeed();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Puts {@code count} torches into the Clerk's storage. */
    private static void stock(ServerLevel level, BlockPos clerk, int count) {
        ClerkStorage.stock(level, clerk, true).insert(new ItemStack(Items.TORCH, count));
    }

    private static int open(Settlement settlement, BlockPos clerk) {
        return Requests.forRule(settlement, clerk, 0).map(Request::amount).orElse(-1);
    }

    /**
     * The request of a rule opens only below Min and asks for the target stock min(Min + Order, Max) (no Order: Max,
     * no Max: Min) minus the stock; it shrinks as stock comes in and is withdrawn at the target; between Min and the
     * target no new request opens; a rule without Min never requests.
     */
    private static void requestAmounts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        stock(level, clerk, 10);

        // Min only: up to Min.
        helper.assertTrue(StockRules.setRule(data, clerk, new StockRule(0, Items.TORCH, 64, 0, 0, 0)), "Min rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(open(settlement, clerk) == 54, "Min 64, stock 10: 54 " + open(settlement, clerk));

        // Min + Max: up to Max.
        StockRules.setRule(data, clerk, new StockRule(0, Items.TORCH, 64, 256, 0, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(open(settlement, clerk) == 246, "Min 64, Max 256, stock 10: 246 " + open(settlement, clerk));

        // Min + Order: up to Min + Order.
        StockRules.setRule(data, clerk, new StockRule(0, Items.TORCH, 64, 0, 100, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(open(settlement, clerk) == 154, "Min 64, Order 100, stock 10: 154 " + open(settlement, clerk));

        // Min + Max + Order: up to min(Min + Order, Max).
        StockRules.setRule(data, clerk, new StockRule(0, Items.TORCH, 64, 128, 192, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(open(settlement, clerk) == 118, "Min 64, Max 128, Order 192, stock 10: 118 " + open(settlement, clerk));
        StockRules.setRule(data, clerk, new StockRule(0, Items.TORCH, 64, 256, 192, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(open(settlement, clerk) == 246, "Min 64, Max 256, Order 192, stock 10: 246 " + open(settlement, clerk));

        // Recomputed on every check; above Min it stays open until the target.
        stock(level, clerk, 90);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(open(settlement, clerk) == 156, "Stock 100, above Min: still open for 156 " + open(settlement, clerk));
        stock(level, clerk, 156);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).isEmpty(), "Target 256 reached: withdrawn");

        // Between Min and the target: no new request.
        ClerkStorage.stock(level, clerk, true).take(Items.TORCH, 156);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).isEmpty(), "Stock 100 is not below Min 64: no request");
        ClerkStorage.stock(level, clerk, true).take(Items.TORCH, 40);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(open(settlement, clerk) == 196, "Stock 60 below Min: one order up to the target, 196 " + open(settlement, clerk));

        // No Min: never a request.
        StockRules.setRule(data, clerk, new StockRule(0, Items.TORCH, 0, 256, 0, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).isEmpty(), "Max only: no request");

        // Invalid rules are refused.
        helper.assertFalse(StockRules.setRule(data, clerk, new StockRule(1, Items.STICK, 0, 0, 0, 0)), "Neither Min nor Max: refused");
        helper.assertFalse(StockRules.setRule(data, clerk, StockRule.minimum(1, Items.TORCH, 8, 0)), "One rule per item and Clerk");
        StockRule clamped = new StockRule(1, Items.STICK, 0, 64, 32, 0);
        helper.assertTrue(clamped.order() == 0, "Order only with Min: " + clamped);
        StockRule band = new StockRule(1, Items.STICK, 100, 50, 0, 0);
        helper.assertTrue(band.min() <= band.max(), "Min ≤ Max: " + band);
        StockRule edited = new StockRule(1, Items.STICK, 64, 256, 0, 0);
        helper.assertTrue(edited.withMin(300).min() == 256 && edited.withMax(10).max() == 64 && edited.withMax(0).max() == 0
                && edited.withMin(0).withOrder(5).order() == 0, "Editor: Min and Max clamp against each other, Order needs Min");
        JobAssignments.unassign(data, clerk);
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.succeed();
    }

    /** A Minimum rule of an old save loads as Min X without Max and Order; the new fields are saved. */
    private static void oldMinimumRule(GameTestHelper helper) {
        com.google.gson.JsonObject old = com.google.gson.JsonParser.parseString(
                "{\"slot\": 2, \"item\": \"minecraft:torch\", \"amount\": 48, \"priority\": 7, \"mode\": \"minimum\"}").getAsJsonObject();
        StockRule rule = StockRule.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        helper.assertTrue(rule.equals(new StockRule(2, Items.TORCH, 48, 0, 0, 7)), "Minimum 48 → Min 48: " + rule);
        StockRule band = new StockRule(3, Items.STICK, 64, 256, 192, -3);
        StockRule loaded = StockRule.CODEC.parse(JsonOps.INSTANCE, StockRule.CODEC.encodeStart(JsonOps.INSTANCE, band).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.equals(band), "Min / Max / Order saved: " + loaded);
        helper.succeed();
    }
}
