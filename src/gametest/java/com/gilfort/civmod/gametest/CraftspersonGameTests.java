package com.gilfort.civmod.gametest;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.PatternTab;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.JobCategory;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.logistics.CraftPlan;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Craftsperson: patterns, checkmarks and the Producing loop (#57).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CraftspersonGameTests {

    private CraftspersonGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "craftsperson_registry", CraftspersonGameTests::registry);
            register(helper, "craftsperson_pattern_matching", CraftspersonGameTests::patternMatching);
            register(helper, "craftsperson_checkmark_limit", CraftspersonGameTests::checkmarkLimit);
            register(helper, "craftsperson_chain_resolution", CraftspersonGameTests::chainResolution);
            register(helper, "craftsperson_catch_up_crafting", CraftspersonGameTests::catchUpCrafting);
            register(helper, "craftsperson_fallback", CraftspersonGameTests::fallback);
            register(helper, "craftsperson_claim_split", CraftspersonGameTests::claimSplit);
            register(helper, "craftsperson_delivery_fallback", CraftspersonGameTests::deliveryFallback);
            register(helper, "craftsperson_live_round", CraftspersonGameTests::liveRound);
            register(helper, "craftsperson_menu", CraftspersonGameTests::menu);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Craftsperson is a Producing Job with job factor 0.3 and base EXP 2; its JobBlock records it. */
    private static void registry(GameTestHelper helper) {
        helper.assertTrue(ModJobs.CRAFTSPERSON.get().category() == JobCategory.PRODUCING, "Producing category");
        helper.assertTrue(ModJobs.CRAFTSPERSON.get().jobFactor() == 0.3, "Job factor 0.3: " + ModJobs.CRAFTSPERSON.get().jobFactor());
        helper.assertTrue(ModJobs.CRAFTSPERSON.get().baseExp() == 2, "Base EXP 2");
        BlockPos pos = craftsperson(helper, new BlockPos(1, 1, 1));
        SettlementBlock block = SettlementData.get(helper.getLevel()).settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow();
        helper.assertTrue(block.jobType() == ModJobs.CRAFTSPERSON.get(), "JobBlock offers the Craftsperson");
        helper.assertTrue(block.patterns().isEmpty(), "Empty library");
        helper.assertTrue(ModJobs.CRAFTSPERSON.get().isValidWorkPlace(helper.getLevel(), placeTable(helper, new BlockPos(3, 1, 1))),
                "A crafting table is the station");
        helper.succeed();
    }

    /**
     * Output only: the first matching crafting recipe fills the grid, tag ingredients stay flexible, arrows cycle the
     * alternatives. Inputs set: exactly those items, the output follows the recipe they match. Patterns are saved.
     */
    private static void patternMatching(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = craftsperson(helper, new BlockPos(1, 1, 1));
        Pattern pickaxe = Patterns.setOutput(level, pos, 0, Items.STONE_PICKAXE).orElseThrow();
        helper.assertTrue(!pickaxe.exact() && pickaxe.recipe().isPresent() && pickaxe.output() == Items.STONE_PICKAXE && pickaxe.count() == 1,
                "Output only: a recipe pattern " + pickaxe);
        helper.assertTrue(pickaxe.grid().get(4) == Items.STICK && pickaxe.grid().get(7) == Items.STICK
                && Collections.frequency(pickaxe.grid(), Items.AIR) == 4, "Grid: 3 stone heads, 2 sticks " + pickaxe.grid());
        List<Ingredient> inputs = Patterns.inputs(level, pickaxe);
        helper.assertTrue(inputs.size() == 5, "Five inputs");
        helper.assertTrue(inputs.stream().anyMatch(input -> input.test(new ItemStack(Items.COBBLESTONE)) && input.test(new ItemStack(Items.BLACKSTONE))),
                "Tag ingredients stay flexible");

        Pattern stick = Patterns.setOutput(level, pos, 1, Items.STICK).orElseThrow();
        Pattern other = Patterns.cycle(level, pos, 1, 1).orElseThrow();
        helper.assertFalse(other.recipe().equals(stick.recipe()), "Arrows cycle the alternatives: " + stick.recipe() + " / " + other.recipe());
        helper.assertTrue(other.output() == Items.STICK, "Still sticks");
        helper.assertTrue(Patterns.cycle(level, pos, 1, -1).orElseThrow().recipe().equals(stick.recipe()), "And back");

        Pattern exact = Patterns.setInput(level, pos, 2, 1, Items.STICK).orElseThrow();
        helper.assertTrue(exact.exact() && exact.output() == Items.AIR, "One stick matches no recipe");
        helper.assertTrue(Patterns.setInput(level, pos, 2, 1, Items.BIRCH_PLANKS).isPresent(), "Plank set");
        exact = Patterns.setInput(level, pos, 2, 4, Items.BIRCH_PLANKS).orElseThrow();
        helper.assertTrue(exact.exact() && exact.output() == Items.STICK && exact.count() == 4, "Two birch planks: 4 sticks " + exact);
        List<Ingredient> exactInputs = Patterns.inputs(level, exact);
        helper.assertTrue(exactInputs.size() == 2 && exactInputs.stream().noneMatch(input -> input.test(new ItemStack(Items.OAK_PLANKS))),
                "Exactly those items");

        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        Settlement loaded = Settlement.CODEC.parse(JsonOps.INSTANCE, Settlement.CODEC.encodeStart(JsonOps.INSTANCE, settlement).getOrThrow())
                .getOrThrow();
        helper.assertTrue(loaded.block(pos).orElseThrow().patterns().equals(settlement.block(pos).orElseThrow().patterns()),
                "Patterns are saved");
        Patterns.clear(data, pos, 2);
        helper.assertTrue(Patterns.pattern(settlement.block(pos).orElseThrow(), 2).isEmpty(), "Cleared");
        helper.succeed();
    }

    /**
     * Checkmarks: 6 + 1 per 5 skill levels; only checked patterns count for "can make"; when the limit drops the last
     * checked patterns in list order lose their checkmark, with a notice.
     */
    private static void checkmarkLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = craftsperson(helper, new BlockPos(1, 1, 1));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        helper.assertTrue(Patterns.checkLimit(1) == 6 && Patterns.checkLimit(20) == 10 && Patterns.checkLimit(100) == 26, "6 + level / 5");

        Item[] outputs = {Items.STICK, Items.OAK_PLANKS, Items.TORCH, Items.CHEST, Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL,
                Items.LADDER, Items.BOWL};
        for (int slot = 0; slot < outputs.length; slot++) {
            helper.assertTrue(Patterns.setOutput(level, pos, slot * 2, outputs[slot]).isPresent(), "Pattern " + outputs[slot]);
        }
        helper.assertFalse(Producers.canMake(level, settlement, Items.STICK), "Unchecked: nobody makes sticks");
        for (int slot = 0; slot < outputs.length; slot++) {
            boolean checked = Patterns.toggleCheck(data, pos, slot * 2);
            helper.assertTrue(checked == slot < 6, "Checkmark " + slot + " within the limit of 6: " + checked);
        }
        helper.assertFalse(Producers.canMake(level, settlement, Items.STICK), "No settler: nobody makes sticks");
        com.gilfort.civmod.settlement.JobAssignments.assign(data, pos, java.util.UUID.randomUUID(), "Cora", settlement.id(), List.of());
        helper.assertTrue(Producers.canMake(level, settlement, Items.STICK), "Checked and staffed: sticks can be made");
        helper.assertFalse(Producers.canMake(level, settlement, Items.LADDER), "Not checked: no ladders");

        helper.assertTrue(Patterns.enforceLimit(data, pos, 4) == 2, "Limit 4: two checkmarks removed");
        SettlementBlock block = settlement.block(pos).orElseThrow();
        helper.assertTrue(Patterns.checked(block).stream().map(Pattern::output).toList()
                .equals(List.of(Items.STICK, Items.OAK_PLANKS, Items.TORCH, Items.CHEST)), "The last ones in list order lose theirs");
        helper.assertTrue(block.trim().equals(Optional.of(new SettlementBlock.Trim(2, 4))), "Notice: 2 removed, 4 left");
        helper.assertTrue(Patterns.enforceLimit(data, pos, 4) == 0, "Within the limit: nothing removed");
        helper.succeed();
    }

    /**
     * Chain resolution: a stone pickaxe from logs and cobblestone crafts planks and sticks first with its own patterns;
     * a missing base input without a pattern is reported; held intermediates are used before the stock.
     */
    private static void chainResolution(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = craftsperson(helper, new BlockPos(1, 1, 1));
        define(helper, pos, 0, Items.STONE_PICKAXE);
        define(helper, pos, 1, Items.OAK_PLANKS);
        SettlementData data = SettlementData.get(level);
        List<Pattern> checked = Patterns.checked(SettlementData.get(level).settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow());
        Map<Item, Integer> stock = Map.of(Items.OAK_LOG, 1, Items.COBBLESTONE, 3);

        CraftPlan noSticks = CraftPlan.plan(level, checked, Items.STONE_PICKAXE, 1, Map.of(), stock);
        helper.assertTrue(noSticks.missing().equals(Optional.of(new Request.Missing(Items.STICK, 2))),
                "No stick pattern: Missing 2× Stick " + noSticks.missing());

        define(helper, pos, 2, Items.STICK);
        checked = Patterns.checked(data.settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow());
        CraftPlan one = CraftPlan.plan(level, checked, Items.STONE_PICKAXE, 1, Map.of(), stock);
        helper.assertTrue(one.missing().isEmpty(), "Everything there: " + one.missing());
        helper.assertTrue(one.takes().equals(Map.of(Items.OAK_LOG, 1, Items.COBBLESTONE, 3)), "Takes a log and 3 cobblestone: " + one.takes());
        helper.assertTrue(one.steps().stream().map(step -> step.pattern().output()).toList()
                .equals(List.of(Items.OAK_PLANKS, Items.STICK, Items.STONE_PICKAXE)), "Intermediates first: " + one.steps());
        helper.assertTrue(one.operations() == 3, "Three operations: " + one.operations());

        CraftPlan two = CraftPlan.plan(level, checked, Items.STONE_PICKAXE, 2, Map.of(), stock);
        helper.assertTrue(two.missing().equals(Optional.of(new Request.Missing(Items.COBBLESTONE, 3))), "2 pickaxes: 3 cobblestone missing "
                + two.missing());
        helper.assertTrue(CraftPlan.plan(level, checked, Items.STONE_PICKAXE, 2, Map.of(), Map.of(Items.OAK_LOG, 1, Items.COBBLESTONE, 6))
                .operations() == 4, "One log makes the sticks of two pickaxes: planks, sticks, 2 pickaxes");

        CraftPlan held = CraftPlan.plan(level, checked, Items.STONE_PICKAXE, 1, Map.of(Items.STICK, 2), Map.of(Items.COBBLESTONE, 3));
        helper.assertTrue(held.missing().isEmpty() && held.operations() == 1 && held.takes().equals(Map.of(Items.COBBLESTONE, 3)),
                "Held sticks are used first: " + held);
        helper.assertTrue(CraftPlan.most(level, checked, Items.STONE_PICKAXE, 5, Map.of(), Map.of(Items.OAK_LOG, 2, Items.COBBLESTONE, 9), 256) == 3,
                "As many as the materials allow: 3");
        helper.succeed();
    }

    /**
     * Catch-up: the Craftsperson takes a stone pickaxe request, fetches a log and cobblestone from the Clerk storage,
     * crafts planks, sticks and the pickaxe (3 operations, EXP each) and delivers the pickaxe to the Clerk; the request
     * is done, leftover intermediates stay in its buffer.
     */
    private static void catchUpCrafting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 1));
        chest.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_PICKAXE, 1, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.claimable(settlement).size() == 1, "One request");

        BlockPos craft = craftsperson(helper, new BlockPos(1, 1, 5));
        placeTable(helper, new BlockPos(3, 1, 5));
        define(helper, craft, 0, Items.STONE_PICKAXE);
        define(helper, craft, 1, Items.STICK);
        define(helper, craft, 2, Items.OAK_PLANKS);
        Settler settler = craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        catchUp(level, settler, 4000L);

        helper.assertTrue(ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.STONE_PICKAXE)) == 1, "Pickaxe delivered");
        helper.assertTrue(ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.OAK_LOG)) == 0,
                "Inputs used up");
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Request done: " + Requests.byPriority(settlement));
        JobBuffer buffer = settler.getWork().buffer(craft);
        helper.assertTrue(buffer.count() == 4, "Leftover 2 planks and 2 sticks stay in the buffer: " + buffer.stacks());
        helper.assertTrue(settler.getSkills().get(ModJobs.CRAFTSPERSON.get()).exp() + settler.getSkills().get(ModJobs.CRAFTSPERSON.get()).level() > 1,
                "EXP gained");
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /**
     * Picking work: the highest-priority request with a checked pattern, even if materials are missing; up to 3 per
     * decision, a failed one is skipped for 30 s; the next decision reaches the doable request.
     */
    private static void fallback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        chest.setItem(1, new ItemStack(Items.STICK, 2));
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_PICKAXE, 1, 30));
        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.STONE_AXE, 1, 20));
        StockRules.setRule(data, clerk, StockRule.minimum(2, Items.TORCH, 4, 10));
        StockRules.setRule(data, clerk, StockRule.minimum(3, Items.STONE_HOE, 1, 5));
        StockRules.setRule(data, clerk, StockRule.minimum(4, Items.LADDER, 3, 50));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);

        BlockPos craft = craftsperson(helper, new BlockPos(1, 1, 5));
        Settler settler = craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        define(helper, craft, 0, Items.STONE_PICKAXE);
        define(helper, craft, 1, Items.STONE_AXE);
        define(helper, craft, 2, Items.TORCH);
        define(helper, craft, 3, Items.STONE_HOE);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.NO_WORK_PLACE, "No crafting table: no work place "
                + settler.getWork().status(craft));
        // Leaving and taking the job again ends the wait of the blocked job.
        settler.removeJob(craft);
        settler.addJob(new SettlerJob(ModJobs.CRAFTSPERSON.get(), craft));

        placeTable(helper, new BlockPos(3, 1, 5));
        settler.getWork().decideNow(level);
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).orElseThrow().missing().equals(Optional.of(new Request.Missing(Items.COBBLESTONE, 1))),
                "Pickaxe: Missing 1× Cobblestone " + Requests.forRule(settlement, clerk, 0));
        helper.assertTrue(Requests.forRule(settlement, clerk, 1).orElseThrow().missing().isPresent()
                && Requests.forRule(settlement, clerk, 2).orElseThrow().missing().isPresent(), "Axe and torches tried");
        helper.assertTrue(Requests.forRule(settlement, clerk, 4).orElseThrow().missing().isEmpty(), "No ladder pattern: not tried");
        Request hoe = Requests.forRule(settlement, clerk, 3).orElseThrow();
        helper.assertTrue(hoe.claims().isEmpty() && hoe.missing().isEmpty(), "Only 3 tries: the hoe waits " + hoe);
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.MISSING_INPUTS, "Status: missing inputs");

        settler.getWork().decideNow(level);
        helper.assertTrue(Requests.forRule(settlement, clerk, 3).orElseThrow().claimOf(settler.getUUID()).map(Request.Claim::amount)
                .equals(Optional.of(1)), "Failed ones skipped: the hoe is claimed");
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.FETCHING_INPUTS, "Fetching inputs");

        settler.removeJob(craft);
        helper.assertTrue(Requests.forRule(settlement, clerk, 3).orElseThrow().claims().isEmpty(), "Leaving the JobBlock releases the claim");
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /** Several Craftspersons split a request: each claims what it can do in one round (buffer limit). */
    private static void claimSplit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int slot = 0; slot < 7; slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        for (int slot = 7; slot < 11; slot++) {
            chest.setItem(slot, new ItemStack(Items.STICK, 64));
        }
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_PICKAXE, 100, 0));
        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.COBBLESTONE, 500, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        UUID request = Requests.forRule(settlement, clerk, 0).orElseThrow().id();
        helper.assertTrue(Requests.forRule(settlement, clerk, 1).orElseThrow().amount() == 500 - 7 * 64,
                "Rules count items, not slots: " + Requests.forRule(settlement, clerk, 1));
        StockRules.removeRule(data, clerk, 1);

        BlockPos first = craftsperson(helper, new BlockPos(1, 1, 5));
        placeTable(helper, new BlockPos(3, 1, 5));
        define(helper, first, 0, Items.STONE_PICKAXE);
        BlockPos second = craftsperson(helper, new BlockPos(6, 1, 5));
        placeTable(helper, new BlockPos(8, 1, 5));
        define(helper, second, 0, Items.STONE_PICKAXE);
        Settler cora = craftsperson(helper, first, new BlockPos(2, 1, 7), "Cora");
        Settler cid = craftsperson(helper, second, new BlockPos(7, 1, 7), "Cid");
        cora.getWork().decideNow(level);
        cid.getWork().decideNow(level);
        Request split = Requests.get(settlement, request).orElseThrow();
        helper.assertTrue(split.claimOf(cora.getUUID()).map(Request.Claim::amount).equals(Optional.of(51)),
                "Cora claims what fits its buffer (5 items each): " + split);
        helper.assertTrue(split.claimOf(cid.getUUID()).map(Request.Claim::amount).equals(Optional.of(49)), "Cid claims the rest: " + split);
        helper.assertTrue(split.amount() == 0, "Nothing open");
        JobAssignments.unassign(data, clerk);
        cora.discard();
        cid.discard();
        helper.assertTrue(Requests.get(settlement, request).orElseThrow().claims().isEmpty(), "Removed settlers release their claims");
        helper.succeed();
    }

    /**
     * Delivery: to the posting Clerk; its storage full → the Craftsperson's own drop-off; that full too → "Drop-off
     * full" (catch-up).
     */
    private static void deliveryFallback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container clerkSlots = (Container) level.getBlockEntity(clerk);
        for (int slot = 0; slot < clerkSlots.getContainerSize(); slot++) {
            clerkSlots.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        BlockPos craft = craftsperson(helper, new BlockPos(1, 1, 5));
        placeTable(helper, new BlockPos(3, 1, 5));
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.CHEST);
        BlockPos dropOff = helper.absolutePos(new BlockPos(5, 1, 5));
        JobAssignments.cyclePoint(data, craft, dropOff, false, true);
        helper.assertTrue(JobAssignments.cyclePoint(data, craft, dropOff, false, true) == JobAssignments.PointResult.DROP_OFF, "Own drop-off");
        define(helper, craft, 0, Items.STICK);
        Container own = (Container) level.getBlockEntity(craft);
        own.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        Request request = Requests.post(data, settlement, Items.STICK, 4, Optional.of(clerk), Optional.empty());
        Settler settler = craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        catchUp(level, settler, 3000L);
        Container chest = (Container) level.getBlockEntity(dropOff);
        helper.assertTrue(chest.countItem(Items.STICK) == 4, "Clerk full (no Clerk settler: block slots only): sticks in the own drop-off");
        helper.assertTrue(Requests.get(settlement, request.id()).isEmpty(), "Request done");

        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        own.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        Requests.post(data, settlement, Items.STICK, 4, Optional.of(clerk), Optional.empty());
        catchUp(level, settler, 3000L);
        helper.assertTrue(settler.getWork().buffer(craft).stacks().stream().anyMatch(stack -> stack.is(Items.STICK) && stack.getCount() == 4),
                "Everything full: the sticks stay in the buffer " + settler.getWork().buffer(craft).stacks());
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.DROP_OFF_FULL, "Drop-off full: " + settler.getWork().status(craft));
        settler.discard();
        helper.succeed();
    }

    /**
     * Live play: the Craftsperson walks to its inputs, crafts at the crafting table (swing, sound, result in the hand)
     * and walks the result to the posting Clerk.
     */
    private static void liveRound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Own floor: test places are spread over the world and the terrain below them varies.
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        // Entities only tick in forced chunks on the GameTest server.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos craft = craftsperson(helper, new BlockPos(1, 1, 5));
        placeTable(helper, new BlockPos(4, 1, 6));
        define(helper, craft, 0, Items.STICK);
        ((Container) level.getBlockEntity(craft)).setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        Request request = Requests.post(data, settlement, Items.STICK, 4, Optional.of(clerk), Optional.empty());
        Settler settler = craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        // Skilled up to the cap of a level 1 JobBlock (a bigger JobBlock would reach into the neighbouring tests).
        settler.getSkills().set(ModJobs.CRAFTSPERSON.get(), new com.gilfort.civmod.job.SettlerSkills.Skill(100, 0));
        settler.setNoAi(false);
        Container clerkSlots = (Container) level.getBlockEntity(clerk);
        helper.succeedWhen(() -> {
            helper.assertTrue(clerkSlots.countItem(Items.STICK) == 4, "Sticks delivered to the Clerk; status " + settler.getWork().status(craft)
                    + ", buffer " + settler.getWork().buffer(craft).stacks() + ", request " + Requests.get(settlement, request.id())
                    + ", at " + helper.relativePos(settler.blockPosition()));
            helper.assertTrue(Requests.get(settlement, request.id()).isEmpty(), "Request done");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    private static Settler craftsperson(GameTestHelper helper, BlockPos craft, BlockPos relative, String name) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(craft).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, relative, data, settlement);
        settler.setCustomName(net.minecraft.network.chat.Component.literal(name));
        // Live decisions go straight to work (no morning trip for provisions).
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        helper.assertTrue(JobAssignments.assign(data, craft, settler.getUUID(), name, settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Craftsperson assigned");
        settler.addJob(new SettlerJob(ModJobs.CRAFTSPERSON.get(), craft));
        return settler;
    }

    /** Catches up some hours of the previous workday from 06:00. */
    private static void catchUp(ServerLevel level, Settler settler, long ticks) {
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;
        settler.getWork().setCatchUp(dayStart - 24000L, dayStart - 24000L + ticks);
        CatchUp.runNow(level, List.of(settler));
    }

    /**
     * The Craftsperson's menu: a Patterns tab with the player inventory; edits through the menu change the library;
     * the tab shows the checkmark limit and removed checkmarks.
     */
    private static void menu(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = craftsperson(helper, new BlockPos(1, 1, 1));
        SettlementData data = SettlementData.get(level);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(pos);
        JobBlockMenu menu = new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(level, pos, ModJobs.CRAFTSPERSON.get(), 1));
        helper.assertTrue(menu.getTabCount() == 3, "Storage, Info, Patterns");
        menu.setActiveTab(JobBlockMenu.TAB_PATTERNS);
        helper.assertTrue(menu.slots.get(JobBlockEntity.SLOTS).isActive() && !menu.slots.get(0).isActive(),
                "Patterns tab: player inventory, no storage slots");

        menu.editPattern(PatternTab.Edit.setOutput(1, 3, Items.TORCH));
        menu.editPattern(PatternTab.Edit.toggleCheck(1, 3));
        menu.editPattern(PatternTab.Edit.transfer(1, 5, Optional.empty(), List.of(Items.AIR, Items.OAK_PLANKS, Items.AIR, Items.AIR, Items.OAK_PLANKS,
                Items.AIR, Items.AIR, Items.AIR, Items.AIR)));
        PatternTab tab = PatternTab.of(level, pos);
        helper.assertTrue(tab.pattern(3).filter(Pattern::checked).map(Pattern::output).equals(Optional.of(Items.TORCH)), "Torch pattern checked: " + tab);
        helper.assertTrue(tab.pattern(5).map(Pattern::output).equals(Optional.of(Items.STICK)) && tab.pattern(5).orElseThrow().exact(),
                "Transfer without a known recipe: exactly the shown items");
        helper.assertTrue(tab.limit() == Patterns.BASE_CHECKMARKS && tab.trim().isEmpty(), "Limit 6, nothing removed");
        menu.editPattern(PatternTab.Edit.clear(1, 3));
        helper.assertTrue(PatternTab.of(level, pos).pattern(3).isEmpty(), "Cleared through the menu");
        helper.assertTrue(data.settlementAt(pos).isPresent(), "Still registered");
        helper.succeed();
    }

    /** Puts a checked pattern for an item into a library slot. */
    static void define(GameTestHelper helper, BlockPos pos, int slot, Item output) {
        helper.assertTrue(Patterns.setOutput(helper.getLevel(), pos, slot, output).isPresent(), "Pattern for " + output);
        helper.assertTrue(Patterns.toggleCheck(SettlementData.get(helper.getLevel()), pos, slot), "Checked " + output);
    }

    static BlockPos craftsperson(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, ModBlocks.CRAFTSPERSON_BLOCK.get());
        return helper.absolutePos(relative);
    }

    static BlockPos placeTable(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        return helper.absolutePos(relative);
    }
}
