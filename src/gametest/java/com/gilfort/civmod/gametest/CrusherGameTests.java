package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Crusher (#251, vault {@code Jobs/Crusher.md}): a Specialization of the Craftsperson unlocked by a
 * grindstone that works only innate recipes at a grindstone and breaks stone down: cobblestone to gravel, gravel to
 * sand, a clay block to 4 clay balls.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CrusherGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few executions at job factor 1.0. */
    private static final long WORK_TICKS = 4000L;
    /** The six recipes of the ticket's list come first by id and get the checkmarks of a new Crusher (limit 6). */
    private static final int REQUIRED_RECIPES = 6;
    /** The three extras the ticket lists after the required six. */
    private static final int EXTRA_RECIPES = 3;

    private CrusherGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "crusher_unlock", CrusherGameTests::unlock);
            register(helper, "crusher_recipes", CrusherGameTests::recipes);
            register(helper, "crusher_gravel_request", CrusherGameTests::gravelRequest);
            register(helper, "crusher_clay_block_four_balls", CrusherGameTests::clayBlockFourBalls);
            register(helper, "crusher_without_grindstone", CrusherGameTests::withoutGrindstone);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Crusher. */
    static Job crusher() {
        return StationTypeGameTests.job(CivMod.id("crusher"));
    }

    /** A level-2 Craftsperson JobBlock with the Crusher unlocked by a grindstone in its second Station Types slot. */
    static BlockPos crushery(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.GRINDSTONE), "A grindstone unlocks the Crusher");
        return pos;
    }

    /** The vanilla item with the registry path, for the colour sets that have no constant. */
    static Item vanilla(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
    }

    /**
     * The result of the innate recipe of a Job that takes the item as its input.
     *
     * @return the result stack, empty if no recipe of the Job takes the item
     */
    static Optional<ItemStack> resultFor(ServerLevel level, Job job, Item input) {
        return InnateRecipes.of(level, job).stream().map(RecipeHolder::value)
                .filter(recipe -> recipe.ingredients().size() == 1 && recipe.ingredients().getFirst().test(new ItemStack(input)))
                .map(recipe -> recipe.result().orElseThrow().create()).findFirst();
    }

    /**
     * The Crusher specializes the Craftsperson (job factor 1.0, base EXP 2, hunger 0.5), is unlocked by a grindstone, works
     * innate recipes only at a grindstone, needs no tool and looks like gravel. Its six required recipes fill the
     * first slots, locked and checked; the three extras follow unchecked.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job crusher = crusher();
        helper.assertTrue(crusher != null && crusher.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Crusher specializes the Craftsperson");
        helper.assertTrue(crusher.unlockItem().equals(Optional.of(Items.GRINDSTONE)), "Unlocked by a grindstone: " + crusher.unlockItem());
        helper.assertTrue(crusher.jobFactor() == 1.0 && crusher.baseExp() == 2 && crusher.hungerFactor() == 0.5,
                "Job factor 1.0, base EXP 2, hunger 0.5: " + crusher.jobFactor() + " / " + crusher.baseExp() + " / " + crusher.hungerFactor());
        helper.assertTrue(crusher.icon().is(Items.GRINDSTONE) && crusher.bubbleIcon().is(Items.GRAVEL) && crusher.displayItem().equals(Optional.of(Items.GRAVEL)),
                "Icon grindstone, bubble and display item gravel");
        helper.assertTrue(crusher.description().getString().equals("Breaks stone down into gravel, sand and more at a grindstone"),
                "Description: " + crusher.description().getString());
        ProducingProfile profile = crusher.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting is the only recipe type");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).location().equals(CivMod.id("stations/crusher")),
                "The station tag is civmod:stations/crusher: " + profile.station(RecipeType.CRAFTING));
        BlockPos pos = crushery(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(crusher),
                "The job picker offers the Crusher");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.GRINDSTONE);
        helper.assertTrue(crusher.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a grindstone");
        Patterns.syncInnate(level, pos, crusher);
        int recipes = REQUIRED_RECIPES + EXTRA_RECIPES;
        for (int slot = 0; slot < recipes; slot++) {
            Optional<Pattern> pattern = Patterns.pattern(StationTypeGameTests.block(level, pos), crusher, slot);
            boolean required = slot < REQUIRED_RECIPES;
            helper.assertTrue(pattern.filter(Pattern::innate).filter(found -> found.checked() == required).isPresent(),
                    "Slot " + slot + " holds an innate recipe, checked for the required six only: " + pattern);
        }
        helper.assertTrue(Patterns.pattern(StationTypeGameTests.block(level, pos), crusher, recipes).isEmpty(), "No tenth recipe");
        Pattern planks = Patterns.setOutput(level, pos, crusher, recipes, Items.OAK_PLANKS).orElseThrow();
        helper.assertTrue(Patterns.unusable(level, StationTypeGameTests.block(level, pos), crusher, planks),
                "A crafting pattern of a player is outside the Crusher's domain: " + planks);
        helper.succeed();
    }

    /**
     * The nine recipes of the ticket, each one input to the stated output: stone, cobblestone, gravel, every terracotta
     * colour, clay block, glowstone, and the three extras.
     */
    private static void recipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job crusher = crusher();
        helper.assertTrue(InnateRecipes.of(level, crusher).size() == REQUIRED_RECIPES + EXTRA_RECIPES,
                "Nine recipes: " + InnateRecipes.of(level, crusher).size());
        expect(helper, crusher, Items.STONE, Items.COBBLESTONE, 1);
        expect(helper, crusher, Items.COBBLESTONE, Items.GRAVEL, 1);
        expect(helper, crusher, Items.GRAVEL, Items.SAND, 1);
        expect(helper, crusher, Items.TERRACOTTA, Items.RED_SAND, 1);
        expect(helper, crusher, vanilla("red_terracotta"), Items.RED_SAND, 1);
        expect(helper, crusher, Items.CLAY, Items.CLAY_BALL, 4);
        expect(helper, crusher, Items.GLOWSTONE, Items.GLOWSTONE_DUST, 4);
        expect(helper, crusher, Items.SANDSTONE, Items.SAND, 4);
        expect(helper, crusher, Items.RED_SANDSTONE, Items.RED_SAND, 4);
        expect(helper, crusher, Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, 1);
        helper.assertTrue(resultFor(level, crusher, Items.GLAZED_TERRACOTTA.white()).isEmpty(), "Glazed terracotta is not crushed");
        helper.assertTrue(InnateRecipes.of(level, crusher).stream().map(RecipeHolder::value).map(JobCraftingRecipe::tool).allMatch(Optional::isEmpty),
                "No recipe needs a tool");
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, Job job, Item input, Item output, int count) {
        Optional<ItemStack> result = resultFor(helper.getLevel(), job, input);
        helper.assertTrue(result.filter(stack -> stack.is(output) && stack.getCount() == count).isPresent(),
                input + " gives " + count + " x " + output + ": " + result);
    }

    /** A Clerk Min rule on 1 gravel, cobblestone in stock: the Crusher crushes it at the grindstone and delivers the gravel. */
    private static void gravelRequest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.COBBLESTONE)), List.of(new ItemStack(Items.GRAVEL)));
        BlockPos craft = crushery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.GRINDSTONE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, crusher());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.GRAVEL) == 1,
                "The gravel arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.COBBLESTONE) == 0, "The cobblestone is used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** One clay block gives 4 clay balls in one execution: a Min rule on 4 clay balls is met by a single clay block. */
    private static void clayBlockFourBalls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.CLAY)), List.of(new ItemStack(Items.CLAY_BALL, 4)));
        BlockPos craft = crushery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.GRINDSTONE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, crusher());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.CLAY_BALL) == 4,
                "4 clay balls arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.CLAY) == 0, "The clay block is used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** Without a grindstone nothing is crushed and the Crusher reports that it has no work place. */
    private static void withoutGrindstone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.COBBLESTONE)), List.of(new ItemStack(Items.GRAVEL)));
        BlockPos craft = crushery(helper, new BlockPos(1, 1, 5));
        Settler settler = FletcherGameTests.worker(helper, setup, craft, crusher());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.GRAVEL) == 0, "No gravel without a grindstone");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.COBBLESTONE) == 1, "The cobblestone stays");
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.NO_WORK_PLACE, "Status: " + settler.getWork().status(craft));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }
}
