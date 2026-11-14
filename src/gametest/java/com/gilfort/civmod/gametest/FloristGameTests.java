package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.DropOutcome;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.datagen.FloristRecipes;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.DropPools;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
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

    /** The roll that lands on the first outcome with these results: the weight in front of it. */
    private static int rollFor(List<DropOutcome> outcomes, List<ItemStackTemplate> results) {
        int before = 0;
        for (DropOutcome outcome : outcomes) {
            if (outcome.results().equals(results)) {
                return before;
            }
            before += outcome.weight();
        }
        throw new AssertionError("No outcome gives " + results + " in " + outcomes);
    }

    private static List<ItemStackTemplate> results(Outcome outcome, List<Item> others) {
        return switch (outcome) {
            case DOUBLE -> List.of(new ItemStackTemplate(FLOWER, 2));
            case DIFFERENT -> List.of(new ItemStackTemplate(FLOWER), new ItemStackTemplate(others.getFirst()));
            case COMES_BACK -> List.of(new ItemStackTemplate(FLOWER));
            case LOST -> List.of();
        };
    }

    /** Every item of the two flower tags as the running game sees them. */
    private static List<Item> flowers(ServerLevel level) {
        return Stream.of("flowers/small", "flowers/tall").map(path -> TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", path)))
                .flatMap(tag -> level.registryAccess().lookupOrThrow(Registries.ITEM).get(tag).stream()).flatMap(HolderSet.Named::stream)
                .map(Holder::value).distinct().toList();
    }

    private static int weightOf(List<DropOutcome> outcomes, List<ItemStackTemplate> results) {
        return outcomes.stream().filter(outcome -> outcome.results().equals(results)).mapToInt(DropOutcome::weight).sum();
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

    /** Every flower of the two flower tags has a recipe of its own: its outcomes weigh 75, 10, 10 and 5 in a hundred. */
    private static void recipesPerFlower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = florist(helper, new BlockPos(1, 1, 1));
        List<Item> flowers = flowers(level);
        helper.assertTrue(flowers.contains(Items.POPPY) && flowers.contains(Items.SUNFLOWER), "Small and tall flowers are listed: " + flowers);
        Patterns.syncInnate(level, pos, florist());
        helper.assertTrue(InnateRecipes.of(level, florist()).size() == flowers.size(),
                "One recipe per flower: " + InnateRecipes.of(level, florist()).size() + " for " + flowers.size());
        for (Item flower : flowers) {
            List<DropOutcome> outcomes = DropPools.outcomes(recipeOf(level, pos, flower).drops().orElseThrow());
            int others = flowers.size() - 1;
            helper.assertTrue(outcomes.stream().mapToInt(DropOutcome::weight).sum() == 100 * others, "Weights add up for " + flower);
            helper.assertTrue(weightOf(outcomes, List.of(new ItemStackTemplate(flower, 2))) == 75 * others, "75 % double for " + flower);
            helper.assertTrue(weightOf(outcomes, List.of(new ItemStackTemplate(flower))) == 10 * others, "10 % comes back for " + flower);
            helper.assertTrue(weightOf(outcomes, List.of()) == 5 * others, "5 % lost for " + flower);
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
        DropPools.script(DropsGameTests.scripted(rollFor(outcomes, results(outcome, others))));
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
        DropPools.script(DropsGameTests.scripted(rollFor(outcomes, List.of(new ItemStackTemplate(FLOWER, 2)))));
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
     * A flower of another mod in the flower tags joins the "different type" pool of every other flower's recipe: the
     * drops are built from the flowers the tags hold, whoever added them.
     */
    private static void foreignFlower(GameTestHelper helper) {
        Item foreign = Items.STICK;
        List<Item> flowers = List.of(Items.POPPY, Items.DANDELION, foreign);
        List<DropOutcome> outcomes = FloristRecipes.outcomes(Items.POPPY, flowers).outcomes();
        helper.assertTrue(weightOf(outcomes, List.of(new ItemStackTemplate(Items.POPPY), new ItemStackTemplate(foreign))) == 10, "The foreign flower can grow");
        helper.assertTrue(weightOf(outcomes, List.of(new ItemStackTemplate(Items.POPPY), new ItemStackTemplate(Items.DANDELION))) == 10, "The dandelion can grow");
        helper.assertTrue(outcomes.stream().mapToInt(DropOutcome::weight).sum() == 200, "Two other flowers, 200 in total: " + outcomes);
        List<DropOutcome> ofForeign = FloristRecipes.outcomes(foreign, flowers).outcomes();
        helper.assertTrue(weightOf(ofForeign, List.of(new ItemStackTemplate(foreign, 2))) == 150, "The foreign flower doubles like the others");
        helper.succeed();
    }
}
