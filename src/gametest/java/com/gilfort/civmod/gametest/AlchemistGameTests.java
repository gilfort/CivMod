package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.PatternLayout;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Alchemist (#268, vault {@code Jobs/Alchemist.md}): a Specialization of the Craftsperson unlocked by a
 * brewing stand that works every {@code minecraft:brewing} recipe, vanilla or from a datapack.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AlchemistGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few brewing rounds. */
    private static final long WORK_TICKS = 4000L;
    /** Ticks of one vanilla brewing round. */
    private static final double BREWING_TICKS = 400.0;
    /** Ticks per operation of the shared speed formula (as the Smelter's). */
    private static final double TICKS_PER_OPERATION = 80.0;

    private AlchemistGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "alchemist_unlock", AlchemistGameTests::unlock);
            TestFunctions.register(helper, "alchemist_recipes", AlchemistGameTests::recipes);
            TestFunctions.register(helper, "alchemist_brews_awkward", AlchemistGameTests::brewsAwkward);
            TestFunctions.register(helper, "alchemist_healing_chain", AlchemistGameTests::healingChain);
            TestFunctions.register(helper, "alchemist_missing_water_bottle", AlchemistGameTests::missingWaterBottle);
            TestFunctions.register(helper, "alchemist_datapack_recipe", AlchemistGameTests::datapackRecipe);
        });
    }

    /** The Alchemist. */
    static Job alchemist() {
        return StationTypeGameTests.job(CivMod.id("alchemist"));
    }

    private static ItemKey water() {
        return ItemKey.of(ItemKeyGameTests.potion(Potions.WATER));
    }

    private static ItemKey awkward() {
        return ItemKey.of(ItemKeyGameTests.potion(Potions.AWKWARD));
    }

    private static ItemKey healing() {
        return ItemKey.of(ItemKeyGameTests.potion(Potions.HEALING));
    }

    /** A level-2 Craftsperson JobBlock with the Alchemist unlocked by a brewing stand in its second Station Types slot. */
    static BlockPos laboratory(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.BREWING_STAND), "A brewing stand unlocks the Alchemist");
        return pos;
    }

    private static int count(ServerLevel level, BlockPos clerk, ItemKey key) {
        return ClerkStorage.stock(level, clerk, true).count(key::matches);
    }

    /**
     * The Alchemist specializes the Craftsperson (job factor 0.5, base EXP 2, hunger 0.5), is unlocked by a brewing stand,
     * works {@code brewing} recipes at brewing stands without fuel and extras.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job alchemist = alchemist();
        helper.assertTrue(alchemist != null && alchemist.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Alchemist specializes the Craftsperson");
        helper.assertTrue(alchemist.unlockItem().equals(Optional.of(Items.BREWING_STAND)), "Unlocked by a brewing stand: " + alchemist.unlockItem());
        helper.assertTrue(alchemist.jobFactor() == 0.5 && alchemist.baseExp() == 2 && alchemist.hungerFactor() == 0.5,
                "Job factor 0.5, base EXP 2, hunger 0.5: " + alchemist.jobFactor() + " / " + alchemist.baseExp() + " / " + alchemist.hungerFactor());
        helper.assertTrue(alchemist.icon().is(Items.BREWING_STAND) && alchemist.bubbleIcon().is(Items.POTION)
                && alchemist.displayItem().equals(Optional.of(Items.POTION)), "Icon brewing stand, bubble and display item potion");
        helper.assertTrue(alchemist.description().getString().equals("Brews potions at a brewing stand"),
                "Description: " + alchemist.description().getString());
        ProducingProfile profile = alchemist.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.BREWING)), "Brewing is the only recipe type");
        helper.assertTrue(profile.station(RecipeType.BREWING).location().equals(CivMod.id("stations/alchemist")),
                "Its own station tag: " + profile.station(RecipeType.BREWING));
        helper.assertTrue(profile.fuel().isEmpty() && profile.parallelStations(5) == 1, "No fuel, one stand at a time");
        BlockPos pos = laboratory(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(alchemist),
                "The job picker offers the Alchemist");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.BREWING_STAND);
        helper.assertTrue(alchemist.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a brewing stand");
        helper.succeed();
    }

    /**
     * Vanilla mixes are recipes of the Alchemist with two inputs (ingredient, potion): the pattern layout has two cells and
     * the brewing stand, the recipe takes the vanilla 400 ticks, and the exact items of the cells find it again.
     */
    private static void recipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ProducingProfile profile = alchemist().profile(ProducingProfile.class).orElseThrow();
        PatternLayout layout = PatternLayout.of(level, profile, RecipeType.BREWING);
        helper.assertTrue(layout.equals(new PatternLayout(RecipeType.BREWING, PatternLayout.Kind.BREWING, Items.BREWING_STAND)) && layout.kind().cells() == 2,
                "Two inputs at the brewing stand: " + layout);
        List<PatternRecipes.Shape> shapes = PatternRecipes.producing(level, profile.recipeTypes(), awkward());
        helper.assertTrue(shapes.size() == 1, "One mix makes an awkward potion: " + shapes);
        PatternRecipes.Shape shape = shapes.getFirst();
        helper.assertTrue(shape.grid().get(0).equals(ItemKey.of(Items.NETHER_WART)) && shape.grid().get(1).equals(water())
                && shape.grid().stream().skip(2).allMatch(ItemKey::isEmpty), "Ingredient first, then the water bottle: " + shape.grid());
        helper.assertTrue(shape.output().equals(awkward()) && shape.count() == 1, "Makes one awkward potion");
        RecipeHolder<?> holder = PatternRecipes.holder(level, shape.id()).orElseThrow();
        helper.assertTrue(profile.workTime(holder) == BREWING_TICKS / TICKS_PER_OPERATION, "Vanilla brewing time: " + profile.workTime(holder));
        helper.assertTrue(PatternRecipes.matching(level, layout, List.of(ItemKey.of(Items.NETHER_WART), water())).map(PatternRecipes.Shape::id)
                .equals(Optional.of(shape.id())), "The items of the cells find the recipe");
        helper.assertTrue(PatternRecipes.matching(level, layout, List.of(ItemKey.of(Items.NETHER_WART), healing())).isEmpty(),
                "Nether wart on a Potion of Healing is no mix");
        Optional<PatternRecipes.Shape> splash = PatternRecipes.matching(level, layout, List.of(ItemKey.of(Items.GUNPOWDER), awkward()));
        helper.assertTrue(splash.isPresent() && splash.get().output().item() == Items.SPLASH_POTION,
                "Gunpowder on an awkward potion makes a splash potion, the same potion in another item: " + splash);
        helper.succeed();
    }

    /** A Clerk asks for an awkward potion; an Alchemist with a water bottle and nether wart brews it and the brewing stand stays. */
    private static void brewsAwkward(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(ItemKeyGameTests.potion(Potions.WATER), new ItemStack(Items.NETHER_WART)),
                List.of(ItemKeyGameTests.potion(Potions.AWKWARD)));
        BlockPos craft = laboratory(helper, new BlockPos(1, 1, 5));
        BlockPos stand = new BlockPos(3, 1, 5);
        helper.setBlock(stand, Blocks.BREWING_STAND);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, alchemist());
        helper.assertTrue(Patterns.setOutput(level, craft, alchemist(), 0, awkward()).orElseThrow().checked(), "Awkward potion checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), awkward()) == 1, "An awkward potion arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(count(level, setup.clerk(), water()) == 0 && count(level, setup.clerk(), ItemKey.of(Items.NETHER_WART)) == 0,
                "The inputs are used up");
        helper.assertTrue(helper.getBlockState(stand).is(Blocks.BREWING_STAND), "The brewing stand stays");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A Clerk asks for a Potion of Healing; only a water bottle, nether wart and a glistering melon slice are in stock. The
     * Alchemist brews the awkward potion from its own pattern first, then the Potion of Healing.
     */
    private static void healingChain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper,
                List.of(ItemKeyGameTests.potion(Potions.WATER), new ItemStack(Items.NETHER_WART), new ItemStack(Items.GLISTERING_MELON_SLICE)),
                List.of(ItemKeyGameTests.potion(Potions.HEALING)));
        BlockPos craft = laboratory(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.BREWING_STAND);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, alchemist());
        helper.assertTrue(Patterns.setOutput(level, craft, alchemist(), 0, healing()).orElseThrow().checked(), "Healing checked");
        helper.assertTrue(Patterns.setOutput(level, craft, alchemist(), 1, awkward()).orElseThrow().checked(), "Awkward checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), healing()) == 1, "A Potion of Healing arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(count(level, setup.clerk(), awkward()) == 0 && count(level, setup.clerk(), ItemKey.of(Items.GLISTERING_MELON_SLICE)) == 0,
                "The awkward potion and the melon slice are used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** Nether wart but no water bottle in stock: nothing is brewed and the request says what is missing. */
    private static void missingWaterBottle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.NETHER_WART)), List.of(ItemKeyGameTests.potion(Potions.AWKWARD)));
        Request request = Requests.claimable(setup.settlement()).getFirst();
        BlockPos craft = laboratory(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.BREWING_STAND);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, alchemist());
        helper.assertTrue(Patterns.setOutput(level, craft, alchemist(), 0, awkward()).orElseThrow().checked(), "Awkward potion checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), awkward()) == 0, "Nothing brewed");
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.MISSING_INPUTS, "Missing inputs: " + settler.getWork().status(craft));
        List<Request.Missing> missing = Requests.get(setup.settlement(), request.id()).orElseThrow().missing();
        helper.assertTrue(missing.equals(List.of(new Request.Missing(water(), 1))), "Missing the water bottle: " + missing);
        helper.assertTrue(missing.getFirst().text().getString().endsWith("Water Bottle"), "Named: " + missing.getFirst().text().getString());
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * The GameTest datapack adds a brewing recipe (amethyst shard on a water bottle gives a Potion of Luck): it is a recipe of
     * the Alchemist like a vanilla mix.
     */
    private static void datapackRecipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ProducingProfile profile = alchemist().profile(ProducingProfile.class).orElseThrow();
        ItemKey luck = ItemKey.of(ItemKeyGameTests.potion(Potions.LUCK));
        List<PatternRecipes.Shape> shapes = PatternRecipes.producing(level, profile.recipeTypes(), luck);
        helper.assertTrue(shapes.size() == 1 && shapes.getFirst().id().identifier().equals(CivMod.id("gametest_brewing_luck")),
                "The datapack mix is a recipe: " + shapes);
        helper.assertTrue(shapes.getFirst().grid().subList(0, 2).equals(List.of(ItemKey.of(Items.AMETHYST_SHARD), water())),
                "Shard on water: " + shapes.getFirst().grid());
        helper.succeed();
    }
}
