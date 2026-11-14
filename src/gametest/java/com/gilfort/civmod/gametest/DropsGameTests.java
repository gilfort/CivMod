package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.DropOutcome;
import com.gilfort.civmod.api.Drops;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.job.DropDraw;
import com.gilfort.civmod.job.DropPools;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for {@code drops} on innate recipes (#252) that need no settler: the weighted draw as a pure function with a
 * scripted random source, the merge of pool files and the recipe format. The Sifter itself is in {@code SifterGameTests}.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DropsGameTests {

    private static final String POOL_FILE_ONE = """
            {"outcomes": [
              {"weight": 200, "results": [{"id": "minecraft:bone_meal"}]},
              {"weight": 100, "min_level": 21, "results": [{"id": "minecraft:redstone", "count": 2}]}
            ]}""";
    private static final String POOL_FILE_TWO = """
            {"outcomes": [
              {"weight": 6, "min_level": 81, "results": [{"id": "minecraft:diamond"}]}
            ]}""";

    private DropsGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "drops_pool_pure", DropsGameTests::poolPure);
            register(helper, "drops_pool_merge", DropsGameTests::poolMerge);
            register(helper, "drops_chances", DropsGameTests::chances);
            register(helper, "drops_recipe_format", DropsGameTests::recipeFormat);
            register(helper, "drops_recipe_rejected", DropsGameTests::recipeRejected);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A random source whose {@code nextInt(bound)} returns the scripted values in order (the last one repeats). */
    static RandomSource scripted(int... values) {
        return new LegacyRandomSource(0L) {
            private int next;

            @Override
            public int nextInt(int bound) {
                return values[Math.min(next++, values.length - 1)];
            }
        };
    }

    private static DropOutcome outcome(int weight, int minLevel, Item item) {
        return new DropOutcome(weight, minLevel, List.of(new ItemStackTemplate(item)));
    }

    /** Five steps like the Sifter's: weights 200 (Lv 1), 100 (Lv 21), 40 (Lv 41), 15 (Lv 61), 6 (Lv 81). */
    private static List<DropOutcome> steps() {
        return List.of(outcome(200, 1, Items.BONE_MEAL), outcome(100, 21, Items.REDSTONE), outcome(40, 41, Items.RAW_GOLD),
                outcome(15, 61, Items.EMERALD), outcome(6, 81, Items.DIAMOND));
    }

    private static Item first(List<ItemStackTemplate> drawn) {
        return drawn.getFirst().item().value();
    }

    private static void poolPure(GameTestHelper helper) {
        List<DropOutcome> steps = steps();
        helper.assertValueEqual(DropDraw.eligible(steps, 1).size(), 1, "Level 1 only has the first step");
        helper.assertValueEqual(DropDraw.eligible(steps, 20).size(), 1, "Level 20 still only the first step");
        helper.assertValueEqual(DropDraw.eligible(steps, 21).size(), 2, "Level 21 opens the second step");
        helper.assertValueEqual(DropDraw.eligible(steps, 100).size(), 5, "Level 100 has every step: the junk stays");

        // Level 1: whatever the draw, only bone meal (the only weight is 200).
        helper.assertValueEqual(first(DropDraw.draw(steps, 1, scripted(0))), Items.BONE_MEAL, "Level 1, lowest roll");
        helper.assertValueEqual(first(DropDraw.draw(steps, 1, scripted(199))), Items.BONE_MEAL, "Level 1, highest roll");
        // Level 100: total 361; rolls walk the steps in order.
        helper.assertValueEqual(first(DropDraw.draw(steps, 100, scripted(0))), Items.BONE_MEAL, "Level 100, junk on roll 0");
        helper.assertValueEqual(first(DropDraw.draw(steps, 100, scripted(199))), Items.BONE_MEAL, "Level 100, last junk roll");
        helper.assertValueEqual(first(DropDraw.draw(steps, 100, scripted(200))), Items.REDSTONE, "Level 100, first redstone roll");
        helper.assertValueEqual(first(DropDraw.draw(steps, 100, scripted(354))), Items.EMERALD, "Level 100, last emerald roll");
        helper.assertValueEqual(first(DropDraw.draw(steps, 100, scripted(355))), Items.DIAMOND, "Level 100, first diamond roll");
        helper.assertValueEqual(first(DropDraw.draw(steps, 100, scripted(360))), Items.DIAMOND, "Level 100, last roll is the diamond");
        // The draw asks for a number below the total weight of the eligible outcomes only.
        helper.assertValueEqual(DropDraw.totalWeight(DropDraw.eligible(steps, 21)), 300, "Total weight at level 21");
        helper.assertTrue(DropDraw.draw(List.of(), 50, scripted(0)).isEmpty(), "No outcomes draw nothing");
        helper.succeed();
    }

    private static void poolMerge(GameTestHelper helper) {
        DataResult<List<DropOutcome>> one = DropPools.parseFile(JsonParser.parseString(POOL_FILE_ONE));
        DataResult<List<DropOutcome>> two = DropPools.parseFile(JsonParser.parseString(POOL_FILE_TWO));
        helper.assertTrue(one.isSuccess() && two.isSuccess(), "Both pool files parse");
        helper.assertValueEqual(one.getOrThrow().get(0).minLevel(), 1, "min_level defaults to 1");
        helper.assertValueEqual(one.getOrThrow().get(1).results().getFirst().count(), 2, "A result keeps its count");
        List<DropOutcome> merged = DropPools.merge(List.of(one.getOrThrow(), two.getOrThrow()));
        helper.assertValueEqual(merged.size(), 3, "Entries of several packs are added");
        helper.assertValueEqual(first(DropDraw.draw(merged, 100, scripted(305))), Items.DIAMOND, "The added entry can be drawn");
        helper.succeed();
    }

    private static void chances(GameTestHelper helper) {
        List<DropOutcome> steps = steps();
        // A chance is the share of the weight at the level where the entry opens.
        helper.assertValueEqual(DropDraw.percentAtUnlock(steps, steps.get(0)), 100.0, "Bone meal alone at Lv 1");
        helper.assertValueEqual(DropDraw.percentAtUnlock(steps, steps.get(1)), 100.0 * 100 / 300, "Redstone at Lv 21");
        helper.assertValueEqual(DropDraw.percentAtUnlock(steps, steps.get(4)), 100.0 * 6 / 361, "Diamond at Lv 81");
        helper.succeed();
    }

    private static final String RECIPE_BASE = """
            {"type": "civmod:job_crafting", "jobs": ["civmod:sifter"], "ingredients": ["minecraft:sand"],""";

    private static DataResult<JobCraftingRecipe> parse(GameTestHelper helper, String json) {
        return JobCraftingRecipe.MAP_CODEC.codec().parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString(json));
    }

    private static void recipeFormat(GameTestHelper helper) {
        DataResult<JobCraftingRecipe> byPool = parse(helper, RECIPE_BASE + "\"drops\": \"civmod:sifter\"}");
        helper.assertTrue(byPool.isSuccess(), "A recipe with drops and no result loads: " + byPool.error().map(DataResult.Error::message).orElse(""));
        Drops pool = byPool.getOrThrow().drops().orElseThrow();
        helper.assertValueEqual(pool.pool().orElseThrow(), CivMod.id("sifter"), "The pool id");
        helper.assertTrue(byPool.getOrThrow().result().isEmpty(), "No result");

        DataResult<JobCraftingRecipe> inline = parse(helper, RECIPE_BASE + """
                "result": {"id": "minecraft:flint"},
                "drops": [{"weight": 3, "min_level": 5, "results": []}, {"weight": 1, "results": [{"id": "minecraft:flint", "count": 2}]}]}""");
        helper.assertTrue(inline.isSuccess(), "Inline outcomes load: " + inline.error().map(DataResult.Error::message).orElse(""));
        Drops outcomes = inline.getOrThrow().drops().orElseThrow();
        helper.assertValueEqual(outcomes.outcomes().size(), 2, "Two inline outcomes");
        helper.assertTrue(outcomes.outcomes().getFirst().results().isEmpty(), "An outcome may find nothing");
        helper.assertValueEqual(outcomes.outcomes().getFirst().minLevel(), 5, "min_level read");

        DataResult<JobCraftingRecipe> plain = parse(helper, RECIPE_BASE + "\"result\": {\"id\": \"minecraft:flint\"}}");
        helper.assertTrue(plain.isSuccess() && plain.getOrThrow().drops().isEmpty(), "A recipe without drops still loads");
        helper.succeed();
    }

    private static void recipeRejected(GameTestHelper helper) {
        helper.assertTrue(parse(helper, RECIPE_BASE + "\"station\": \"#civmod:stations/sifter\"}").isError(), "Neither result nor drops is rejected");
        helper.assertTrue(parse(helper, RECIPE_BASE + "\"drops\": []}").isError(), "Empty drops are rejected");
        helper.assertTrue(parse(helper, RECIPE_BASE + "\"drops\": [{\"weight\": 0, \"results\": []}]}").isError(), "A weight of 0 is rejected");
        helper.assertTrue(parse(helper, RECIPE_BASE + "\"drops\": [{\"weight\": 1, \"min_level\": 0, \"results\": []}]}").isError(),
                "A min_level below 1 is rejected");
        helper.assertTrue(parse(helper, RECIPE_BASE + "\"drops\": [{\"weight\": 1, \"results\": [{\"id\": \"minecraft:nothing_here\"}]}]}").isError(),
                "An unknown item is rejected");
        helper.assertTrue(parse(helper, RECIPE_BASE + "\"drops\": \"not an id\"}").isError(), "A pool name that is no id is rejected");
        helper.assertTrue(parse(helper, RECIPE_BASE + "\"drops\": \"civmod:sifter\", \"requires\": {\"entity\": \"minecraft:cow\"}}").isError(),
                "Drops do not go with requires");
        helper.succeed();
    }
}
