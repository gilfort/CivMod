package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.mastery.ReversibleRecipes;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Mastery roll (spec #304, story #302, vault {@code Systems/Producing Jobs.md}): a Specialization with a
 * scripted roll crafts a pattern in catch-up (the same output hook as live play) and the items in the Clerk and the buffer
 * show what the hits did. The global {@link QuietMastery} keeps every other test free of the roll.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MasteryGameTests {

    /** Work time of the previous workday the tests catch up: plenty for a craft at job factor 0.3. */
    private static final long WORK_TICKS = 6000L;
    /** Where the Specialization's JobBlock stands. */
    private static final BlockPos CRAFT = new BlockPos(1, 1, 5);
    /** Where its crafting or smithing table stands. */
    private static final BlockPos TABLE = new BlockPos(3, 1, 5);
    /** Ingredient items of a cake: 3 milk buckets, 2 sugar, 1 egg, 3 wheat. */
    private static final int CAKE_ITEMS = 9;

    private MasteryGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mastery_saving_one_hit", test -> cake(test, 1, 1));
            register(helper, "mastery_saving_three_hits", test -> cake(test, 3, 3));
            register(helper, "mastery_saving_minimum", MasteryGameTests::savingMinimum);
            register(helper, "mastery_extra_output_one_hit", test -> founder(test, 1));
            register(helper, "mastery_extra_output_two_hits", test -> founder(test, 2));
            register(helper, "mastery_extra_output_three_hits", test -> founder(test, 3));
            register(helper, "mastery_reversible_gets_nothing", MasteryGameTests::reversibleGetsNothing);
            register(helper, "mastery_gear_gets_nothing_yet", MasteryGameTests::gearGetsNothing);
            register(helper, "mastery_craftsperson_never_rolls", MasteryGameTests::craftspersonNeverRolls);
            register(helper, "mastery_smelter_never_rolls", MasteryGameTests::smelterNeverRolls);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A Specialization that crafts one wanted item for the Clerk. */
    private record Run(Setup setup, BlockPos craft, Settler settler) {
    }

    /**
     * Puts a settler of the Job at a JobBlock with a checked pattern of the wanted item and a Clerk that wants one of it,
     * rolling the given numbers.
     */
    private static Run run(GameTestHelper helper, Job job, BlockPos craft, Block table, List<ItemStack> stock, Item wanted, RandomSource rolls) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, stock, List.of(new ItemStack(wanted)));
        helper.setBlock(TABLE, table);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, job);
        helper.assertTrue(Patterns.setOutput(level, craft, job, 0, ItemKey.of(wanted)).orElseThrow().checked(), wanted + " checked");
        settler.getWork().scriptMasteryRandom(rolls);
        return new Run(setup, craft, settler);
    }

    private static Run baker(GameTestHelper helper, List<ItemStack> stock, Item wanted, RandomSource rolls) {
        return run(helper, BakerGameTests.baker(), BakerGameTests.bakery(helper, CRAFT), Blocks.CRAFTING_TABLE, stock, wanted, rolls);
    }

    private static Run smith(GameTestHelper helper, List<ItemStack> stock, Item wanted, RandomSource rolls) {
        return run(helper, SmithGameTests.smith(), SmithGameTests.smithy(helper, CRAFT), Blocks.SMITHING_TABLE, stock, wanted, rolls);
    }

    private static void work(GameTestHelper helper, Run run) {
        CraftspersonGameTests.catchUp(helper.getLevel(), run.settler(), WORK_TICKS);
    }

    /** How many of the item the Clerk holds plus the settler's buffer: where a saved item waits is not the point. */
    private static int total(GameTestHelper helper, Run run, Item item) {
        return FletcherGameTests.count(helper.getLevel(), run.setup().clerk(), item)
                + run.settler().getWork().buffer(run.craft()).counts().getOrDefault(ItemKey.of(item), 0);
    }

    private static void finish(GameTestHelper helper, Run run) {
        FletcherGameTests.cleanUp(run.setup(), run.settler());
        helper.succeed();
    }

    /**
     * A cake has 9 ingredient items: the hits save 13 % of them each, rounded down, at least 1. The saved items are the
     * first ingredients (the scripted pick), the milk buckets, and they leave no empty bucket behind: the saving comes
     * before the remainders.
     */
    private static void cake(GameTestHelper helper, int hits, int saved) {
        // Milk buckets do not stack: one stack each.
        Run run = baker(helper, List.of(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                new ItemStack(Items.SUGAR, 2), new ItemStack(Items.EGG), new ItemStack(Items.WHEAT, 3)), Items.CAKE, MasteryRolls.hits(hits));
        work(helper, run);
        helper.assertTrue(total(helper, run, Items.CAKE) == 1, "The cake is made (status " + run.settler().getWork().status(run.craft()) + ")");
        helper.assertTrue(total(helper, run, Items.MILK_BUCKET) == saved, hits + " hits save " + saved + " of " + CAKE_ITEMS + " items, the milk buckets: "
                + total(helper, run, Items.MILK_BUCKET));
        helper.assertTrue(total(helper, run, Items.BUCKET) == 3 - saved, "Only used milk buckets leave an empty bucket: " + total(helper, run, Items.BUCKET));
        helper.assertTrue(total(helper, run, Items.SUGAR) == 0 && total(helper, run, Items.EGG) == 0 && total(helper, run, Items.WHEAT) == 0,
                "The other ingredients are used up");
        finish(helper, run);
    }

    /** Bread has 3 ingredient items: 13 % of 3 rounds down to 0, but a hit saves at least 1 item (a wheat). */
    private static void savingMinimum(GameTestHelper helper) {
        Run run = baker(helper, List.of(new ItemStack(Items.WHEAT, 3)), Items.BREAD, MasteryRolls.hits(1));
        work(helper, run);
        helper.assertTrue(total(helper, run, Items.BREAD) == 1, "The bread is made (status " + run.settler().getWork().status(run.craft()) + ")");
        helper.assertTrue(total(helper, run, Items.WHEAT) == 1, "One wheat is saved: " + total(helper, run, Items.WHEAT));
        finish(helper, run);
    }

    /**
     * A Founder smelts raw iron (1 ingredient item, below 3): every hit gives one more ingot, and nothing is saved, the raw
     * iron is used up.
     */
    private static void founder(GameTestHelper helper, int hits) {
        ServerLevel level = helper.getLevel();
        Job founder = SmelterSpecializationGameTests.founder();
        SmelterSpecializationGameTests.Setup setup = SmelterSpecializationGameTests.setup(helper, founder, Items.BLAST_FURNACE, Blocks.BLAST_FURNACE,
                "iron_ingot_from_blasting_raw_iron", Items.RAW_IRON, List.of(new ItemStack(Items.BREAD, 32), new ItemStack(Items.RAW_IRON), new ItemStack(Items.COAL, 3)));
        helper.assertTrue(StockRules.setRule(setup.data(), setup.clerk(), StockRule.minimum(0, ItemKey.of(Items.IRON_INGOT), 1, 0)), "Rule set");
        StockRules.check(level, setup.clerk(), ClerkGameTests.MORNING);
        Settler settler = SmelterSpecializationGameTests.worker(helper, setup, founder);
        settler.getWork().scriptMasteryRandom(MasteryRolls.hits(hits));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);

        int ingots = SmelterGameTests.count(level, setup.clerk(), Items.IRON_INGOT)
                + settler.getWork().buffer(setup.block()).counts().getOrDefault(ItemKey.of(Items.IRON_INGOT), 0);
        helper.assertTrue(ingots == 1 + hits, hits + " hits make " + (1 + hits) + " ingots: " + ingots + ", status " + settler.getWork().status(setup.block()));
        helper.assertTrue(SmelterGameTests.count(level, setup.clerk(), Items.RAW_IRON) == 0, "Nothing is saved below 3 items, the raw iron is used up");
        SmelterSpecializationGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Nine iron ingots make an iron block and an iron block makes nine ingots: a reversible recipe. A Smith with three hits
     * saves nothing there, though 9 items would save 3 anywhere else.
     */
    private static void reversibleGetsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(ReversibleRecipes.contains(level, recipe("iron_block")), "Ingots to block are reversible");
        helper.assertTrue(ReversibleRecipes.contains(level, recipe("iron_ingot_from_iron_block")), "Block to ingots is reversible");
        helper.assertFalse(ReversibleRecipes.contains(level, recipe("cake")), "A cake is not");
        helper.assertFalse(ReversibleRecipes.contains(level, recipe("iron_pickaxe")), "A pickaxe is not");
        Run run = smith(helper, List.of(new ItemStack(Items.IRON_INGOT, 9)), Items.IRON_BLOCK, MasteryRolls.always());
        work(helper, run);
        helper.assertTrue(total(helper, run, Items.IRON_BLOCK) == 1, "The block is made (status " + run.settler().getWork().status(run.craft()) + ")");
        helper.assertTrue(total(helper, run, Items.IRON_INGOT) == 0, "No ingot is saved: " + total(helper, run, Items.IRON_INGOT));
        finish(helper, run);
    }

    /** Gear (a result with durability) is the masterwork branch of #303: until it exists, three hits change nothing. */
    private static void gearGetsNothing(GameTestHelper helper) {
        Run run = smith(helper, List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.STICK, 2)), Items.IRON_PICKAXE, MasteryRolls.always());
        work(helper, run);
        helper.assertTrue(total(helper, run, Items.IRON_PICKAXE) == 1, "One pickaxe (status " + run.settler().getWork().status(run.craft()) + ")");
        helper.assertTrue(total(helper, run, Items.IRON_INGOT) == 0 && total(helper, run, Items.STICK) == 0, "Nothing is saved from a pickaxe");
        finish(helper, run);
    }

    /** A Craftsperson has no parent Job: even a roll that hits every time saves no wheat of the bread. */
    private static void craftspersonNeverRolls(GameTestHelper helper) {
        Run run = run(helper, CraftspersonGameTests.craftJob(), CraftspersonGameTests.craftsperson(helper, CRAFT), Blocks.CRAFTING_TABLE,
                List.of(new ItemStack(Items.WHEAT, 3)), Items.BREAD, MasteryRolls.always());
        work(helper, run);
        helper.assertTrue(total(helper, run, Items.BREAD) == 1, "The bread is made (status " + run.settler().getWork().status(run.craft()) + ")");
        helper.assertTrue(total(helper, run, Items.WHEAT) == 0, "No wheat is saved: " + total(helper, run, Items.WHEAT));
        finish(helper, run);
    }

    /** A Smelter has no parent Job: a roll that hits every time gives no extra ingot. */
    private static void smelterNeverRolls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job smelter = SmelterGameTests.smelter();
        SmelterSpecializationGameTests.Setup setup = SmelterSpecializationGameTests.setup(helper, smelter, Items.BLAST_FURNACE, Blocks.FURNACE,
                "iron_ingot_from_smelting_raw_iron", Items.RAW_IRON, List.of(new ItemStack(Items.BREAD, 32), new ItemStack(Items.RAW_IRON), new ItemStack(Items.COAL, 3)));
        helper.assertTrue(StockRules.setRule(setup.data(), setup.clerk(), StockRule.minimum(0, ItemKey.of(Items.IRON_INGOT), 1, 0)), "Rule set");
        StockRules.check(level, setup.clerk(), ClerkGameTests.MORNING);
        Settler settler = SmelterSpecializationGameTests.worker(helper, setup, smelter);
        settler.getWork().scriptMasteryRandom(MasteryRolls.always());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);

        int ingots = SmelterGameTests.count(level, setup.clerk(), Items.IRON_INGOT)
                + settler.getWork().buffer(setup.block()).counts().getOrDefault(ItemKey.of(Items.IRON_INGOT), 0);
        helper.assertTrue(ingots == 1, "One ingot, no extra: " + ingots + ", status " + settler.getWork().status(setup.block()));
        SmelterSpecializationGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    private static ResourceKey<Recipe<?>> recipe(String path) {
        return ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(path));
    }
}
