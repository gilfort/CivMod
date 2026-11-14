package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.DropOutcome;
import com.gilfort.civmod.api.Drops;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.DropPools;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Florist (#261, vault {@code Jobs/Florist.md}): a Specialization of the Craftsperson unlocked by a
 * flower pot; its innate recipes (one per flower) turn a flower and a bone meal into two flowers, a flower and another
 * kind, the flower alone, or nothing. The draws are scripted ({@link DropPools#script}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FloristGameTests {

    /** Work time the tests catch up: plenty for a few crafts at job factor 0.5. */
    private static final long WORK_TICKS = 12000L;
    /** Most rounds of catch-up the chain needs: ask, compost, craft. */
    private static final int MAX_PASSES = 4;
    /** Wheat in the Clerk, more than the 7 points of one bone meal need. */
    private static final int WHEAT_STOCK = 30;
    /** The flower the round tests grow. */
    private static final Item FLOWER = Items.POPPY;

    private FloristGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "florist_unlock", FloristGameTests::unlock);
            register(helper, "florist_recipes_per_flower", FloristGameTests::recipesPerFlower);
            register(helper, "florist_outcome_double", test -> grow(test, Outcome.DOUBLE, 2, 0));
            register(helper, "florist_outcome_different", test -> grow(test, Outcome.DIFFERENT, 1, 1));
            register(helper, "florist_outcome_comes_back", test -> grow(test, Outcome.COMES_BACK, 1, 0));
            register(helper, "florist_outcome_lost", test -> grow(test, Outcome.LOST, 0, 0));
            register(helper, "florist_always_produce", FloristGameTests::alwaysProduce);
            register(helper, "florist_foreign_flower", FloristGameTests::foreignFlower);
            register(helper, "florist_bone_meal_chain", FloristGameTests::boneMealChain);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The four things a flower and a bone meal can turn into. */
    private enum Outcome {
        DOUBLE, DIFFERENT, COMES_BACK, LOST
    }

    /** The Florist. */
    static Job florist() {
        return StationTypeGameTests.job(CivMod.id("florist"));
    }

    /** A Craftsperson JobBlock with the Florist unlocked by a flower pot in its second Station Types slot. */
    private static BlockPos florist(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.FLOWER_POT), "A flower pot unlocks the Florist");
        return pos;
    }

    /** The innate recipe of a flower, found by its input. */
    private static JobCraftingRecipe recipeOf(ServerLevel level, BlockPos craft, Item flower) {
        Patterns.syncInnate(level, craft, florist());
        for (int slot = 0; ; slot++) {
            Optional<Pattern> pattern = PatternsTabData.of(level, craft, florist()).pattern(slot);
            if (pattern.isEmpty()) {
                throw new AssertionError("No recipe for " + flower);
            }
            JobCraftingRecipe recipe = InnateRecipes.recipe(level, pattern.get()).orElseThrow();
            if (recipe.ingredients().getFirst().test(new ItemStack(flower))) {
                return recipe;
            }
        }
    }

    private static int slotOf(ServerLevel level, BlockPos craft, JobCraftingRecipe wanted) {
        for (int slot = 0; ; slot++) {
            Pattern pattern = PatternsTabData.of(level, craft, florist()).pattern(slot).orElseThrow();
            if (InnateRecipes.recipe(level, pattern).orElseThrow() == wanted) {
                return slot;
            }
        }
    }

    /** The roll that lands on the first outcome with these results and this tag result: the weight in front of it. */
    private static int rollFor(List<DropOutcome> outcomes, List<ItemStackTemplate> results, boolean withTag) {
        int before = 0;
        for (DropOutcome outcome : outcomes) {
            if (outcome.results().equals(results) && outcome.tagResult().isPresent() == withTag) {
                return before;
            }
            before += outcome.weight();
        }
        throw new AssertionError("No outcome gives " + results + " in " + outcomes);
    }

    private static List<ItemStackTemplate> results(Outcome outcome) {
        return switch (outcome) {
            case DOUBLE -> List.of(new ItemStackTemplate(FLOWER, 2));
            case DIFFERENT, COMES_BACK -> List.of(new ItemStackTemplate(FLOWER));
            case LOST -> List.of();
        };
    }

    /** The scripted draw of an outcome: the roll that lands on it and, for the different flower, the index in the tag. */
    private static RandomSource scriptFor(List<DropOutcome> outcomes, Outcome outcome, int tagIndex) {
        return DropsGameTests.scripted(rollFor(outcomes, results(outcome), outcome == Outcome.DIFFERENT), tagIndex);
    }

    /** Every item of the two flower tags as the running game sees them. */
    private static List<Item> flowers(ServerLevel level) {
        return Stream.of("flowers/small", "flowers/tall").map(path -> TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", path)))
                .flatMap(tag -> level.registryAccess().lookupOrThrow(Registries.ITEM).get(tag).stream()).flatMap(HolderSet.Named::stream)
                .map(Holder::value).distinct().toList();
    }

    /** The flowers that have a recipe of their own: the vanilla ones (other mods' items in the tag have none). */
    private static List<Item> vanillaFlowers(ServerLevel level) {
        return flowers(level).stream().filter(flower -> BuiltInRegistries.ITEM.getKey(flower).getNamespace().equals(Identifier.DEFAULT_NAMESPACE)).toList();
    }

    private static int weightOf(List<DropOutcome> outcomes, List<ItemStackTemplate> results, boolean withTag) {
        return outcomes.stream().filter(outcome -> outcome.results().equals(results) && outcome.tagResult().isPresent() == withTag)
                .mapToInt(DropOutcome::weight).sum();
    }

    /**
     * The Florist specializes the Craftsperson (job factor 0.5, base EXP 2, hunger 0.5), is unlocked by a flower pot,
     * works at a flower pot and every innate recipe it knows draws.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job florist = florist();
        helper.assertTrue(florist != null && florist.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Florist specializes the Craftsperson");
        helper.assertTrue(florist.unlockItem().equals(Optional.of(Items.FLOWER_POT)), "Unlocked by a flower pot: " + florist.unlockItem());
        helper.assertTrue(florist.jobFactor() == 0.5 && florist.baseExp() == 2 && florist.hungerFactor() == 0.5,
                "Job factor 0.5, base EXP 2, hunger 0.5: " + florist.jobFactor() + " / " + florist.baseExp() + " / " + florist.hungerFactor());
        ProducingProfile profile = florist.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "A crafting profile that no pattern passes");
        BlockPos pos = florist(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(florist),
                "The job picker offers the Florist");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.FLOWER_POT);
        helper.assertTrue(florist.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a flower pot");
        JobCraftingRecipe poppy = recipeOf(level, pos, FLOWER);
        helper.assertTrue(poppy.drops().isPresent() && poppy.result().isEmpty(), "A flower recipe only draws: " + poppy);
        helper.assertTrue(poppy.ingredients().size() == 2 && poppy.ingredients().get(1).test(new ItemStack(Items.BONE_MEAL)), "A flower and a bone meal");
        helper.succeed();
    }

    /**
     * Every vanilla flower of the two flower tags has a recipe of its own with exactly four outcomes that weigh 75, 10, 10
     * and 5; the "different flower" outcome takes its second flower from the civmod flower tag.
     */
    private static void recipesPerFlower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = florist(helper, new BlockPos(1, 1, 1));
        List<Item> flowers = vanillaFlowers(level);
        helper.assertTrue(flowers.contains(Items.POPPY) && flowers.contains(Items.SUNFLOWER), "Small and tall flowers are listed: " + flowers);
        Patterns.syncInnate(level, pos, florist());
        helper.assertTrue(InnateRecipes.of(level, florist()).size() == flowers.size(),
                "One recipe per flower: " + InnateRecipes.of(level, florist()).size() + " for " + flowers.size());
        for (Item flower : flowers) {
            List<DropOutcome> outcomes = DropPools.outcomes(recipeOf(level, pos, flower).drops().orElseThrow());
            helper.assertTrue(outcomes.size() == 4, "Four outcomes for " + flower + ": " + outcomes);
            helper.assertTrue(weightOf(outcomes, List.of(new ItemStackTemplate(flower, 2)), false) == 75, "75 % double for " + flower);
            helper.assertTrue(weightOf(outcomes, List.of(new ItemStackTemplate(flower)), true) == 10, "10 % a different flower for " + flower);
            helper.assertTrue(weightOf(outcomes, List.of(new ItemStackTemplate(flower)), false) == 10, "10 % comes back for " + flower);
            helper.assertTrue(weightOf(outcomes, List.of(), false) == 5, "5 % lost for " + flower);
            DropOutcome different = outcomes.stream().filter(outcome -> outcome.tagResult().isPresent()).findFirst().orElseThrow();
            helper.assertTrue(different.tagResult().orElseThrow().tag().location().equals(CivMod.id("florist_flowers")), "The tag of the different flower");
        }
        helper.succeed();
    }

    /** One execution with a scripted draw uses up one flower and one bone meal and delivers exactly what the outcome gives. */
    private static void grow(GameTestHelper helper, Outcome outcome, int flowersBack, int othersBack) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(FLOWER), new ItemStack(Items.BONE_MEAL)), List.of());
        BlockPos craft = florist(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLOWER_POT);
        JobCraftingRecipe recipe = recipeOf(level, craft, FLOWER);
        alwaysMake(helper, craft, recipe, 1);
        List<Item> others = flowers(level).stream().filter(flower -> !flower.equals(FLOWER)).toList();
        List<DropOutcome> outcomes = DropPools.outcomes(recipe.drops().orElseThrow());
        Settler settler = FletcherGameTests.worker(helper, setup, craft, florist());
        DropPools.script(scriptFor(outcomes, outcome, 0));
        try {
            CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
            int flowers = FletcherGameTests.count(level, setup.clerk(), FLOWER);
            helper.assertTrue(flowers == flowersBack, outcome + ": flowers " + flowers);
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 0, outcome + ": the bone meal is used up");
            int foreign = others.stream().mapToInt(other -> FletcherGameTests.count(level, setup.clerk(), other)).sum();
            helper.assertTrue(foreign == othersBack, outcome + ": other flowers " + foreign);
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, settler);
        }
        helper.succeed();
    }

    /** Turns a recipe into an "Always produce" pattern with the given number of crafts per round. */
    private static void alwaysMake(GameTestHelper helper, BlockPos craft, JobCraftingRecipe recipe, int crafts) {
        ServerLevel level = helper.getLevel();
        int slot = slotOf(level, craft, recipe);
        helper.assertTrue(Patterns.toggleAlways(level, craft, florist(), slot), "Always produce on the recipe");
        Patterns.setAtOnce(SettlementData.get(level), craft, florist(), slot, crafts);
    }

    /** An "Always produce" pattern with 4 crafts per round runs four times and delivers to the Home Clerk. */
    private static void alwaysProduce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(FLOWER, 4), new ItemStack(Items.BONE_MEAL, 4)), List.of());
        BlockPos craft = florist(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLOWER_POT);
        JobCraftingRecipe recipe = recipeOf(level, craft, FLOWER);
        alwaysMake(helper, craft, recipe, 4);
        List<DropOutcome> outcomes = DropPools.outcomes(recipe.drops().orElseThrow());
        Settler settler = FletcherGameTests.worker(helper, setup, craft, florist());
        DropPools.script(scriptFor(outcomes, Outcome.DOUBLE, 0));
        try {
            CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), FLOWER) == 8, "4 crafts, 8 flowers at the Home Clerk: " + FletcherGameTests.count(level, setup.clerk(), FLOWER) + " flowers, " + FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) + " bone meal");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 0, "All 4 bone meal used up");
            helper.assertTrue(settler.getWork().buffer(craft).count() == 0, "Nothing is left in the settler's buffer");
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, settler);
        }
        helper.succeed();
    }

    /**
     * An item of another mod comes up at runtime: the draw reads the tag when it draws, so a member that is no vanilla
     * flower can be the different flower. The poppy recipe's outcomes are drawn with the test-only tag
     * {@code civmod:test_foreign_flowers} (poppy, dandelion and a beer) in place of the flower tag, so no dev client
     * grows beer from flowers. A scripted draw of the "different flower" outcome lands on the beer; the poppy itself is
     * never drawn as the different one.
     */
    private static void foreignFlower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = florist(helper, new BlockPos(1, 1, 1));
        Item foreign = ModItems.BEER.get();
        TagKey<Item> testTag = TagKey.create(Registries.ITEM, CivMod.id("test_foreign_flowers"));
        List<DropOutcome> outcomes = DropPools.outcomes(recipeOf(level, pos, FLOWER).drops().orElseThrow()).stream()
                .map(outcome -> new DropOutcome(outcome.weight(), outcome.minLevel(), outcome.results(),
                        outcome.tagResult().map(tagResult -> new DropOutcome.TagResult(testTag, tagResult.count()))))
                .toList();
        List<Item> candidates = DropPools.candidates(testTag, List.of(FLOWER));
        helper.assertTrue(candidates.contains(foreign) && !candidates.contains(FLOWER), "The beer can be the different flower, the poppy cannot: " + candidates);
        List<ItemStack> drawn = DropPools.draw(Drops.of(outcomes), 1, scriptFor(outcomes, Outcome.DIFFERENT, candidates.indexOf(foreign)),
                List.of(new ItemStack(FLOWER), new ItemStack(Items.BONE_MEAL)));
        helper.assertTrue(drawn.size() == 2 && drawn.get(0).is(FLOWER) && drawn.get(1).is(foreign), "The poppy and the beer: " + drawn);
        helper.assertTrue(!flowers(level).contains(foreign), "The flower tags themselves stay free of the beer");
        helper.succeed();
    }

    /**
     * The chain of the spec: the Home Clerk keeps one bone meal in stock (a Min rule), so the Composter turns wheat into it
     * through a request, and the Florist, who has the poppy but no bone meal, grows it as soon as the bone meal is there. An
     * "Always produce" round of the Florist posts no sub-request of its own (its recipes cannot serve requests), so the
     * bone meal reaches it through the Clerk's stock.
     */
    private static void boneMealChain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(FLOWER), new ItemStack(Items.WHEAT, WHEAT_STOCK)),
                List.of(new ItemStack(Items.BONE_MEAL)));
        BlockPos floristBlock = florist(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLOWER_POT);
        JobCraftingRecipe recipe = recipeOf(level, floristBlock, FLOWER);
        alwaysMake(helper, floristBlock, recipe, 1);
        BlockPos compostBlock = ComposterGameTests.composting(helper, new BlockPos(1, 1, 9));
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.COMPOSTER);
        Settler florist = FletcherGameTests.worker(helper, setup, floristBlock, florist());
        Settler composter = FletcherGameTests.worker(helper, setup, compostBlock, ComposterGameTests.composter());
        composter.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.NETHERITE_HOE));
        List<DropOutcome> outcomes = DropPools.outcomes(recipe.drops().orElseThrow());
        DropPools.script(scriptFor(outcomes, Outcome.DOUBLE, 0));
        try {
            for (int pass = 0; pass < MAX_PASSES && FletcherGameTests.count(level, setup.clerk(), FLOWER) < 2; pass++) {
                CraftspersonGameTests.catchUp(level, florist, WORK_TICKS);
                CraftspersonGameTests.catchUp(level, composter, WORK_TICKS);
            }
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), FLOWER) == 2,
                    "Two poppies at the Clerk (florist " + florist.getWork().status(floristBlock) + ", composter " + composter.getWork().status(compostBlock) + ")");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WHEAT) < WHEAT_STOCK, "The Composter used wheat");
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, florist);
            composter.discard();
        }
        helper.succeed();
    }
}
