package com.gilfort.civmod.gametest;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.JobCategory;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
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
