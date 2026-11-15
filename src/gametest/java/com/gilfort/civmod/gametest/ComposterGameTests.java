package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.CompostChances;
import com.gilfort.civmod.job.CompostPoints;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.LibraryRestPoints;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementBlock;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Composter (#262, vault {@code Jobs/Composter.md}): a Specialization of the Craftsperson unlocked by a
 * composter that works one innate recipe, anything compostable to bone meal. Every item adds its compost chance as
 * points, 7 points make 1 bone meal, the rest points are saved with the JobBlock and the hoe loses 1 durability per bone
 * meal made.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ComposterGameTests {

    /** Work time of the previous workday the tests catch up: plenty for the many one-item rounds. */
    private static final long WORK_TICKS = 24000L;
    /** Wheat seeds that make exactly one bone meal: 24 times 0.3 points are 7.2 points. */
    private static final int SEEDS_FOR_ONE = 24;
    /** The points the 24 seeds leave over, in hundredths of a point. */
    private static final int SEED_REST = 20;
    /** Wheat (0.65 points) in stock for the request tests: 22 give 14.3 points, two bone meal and 0.3 points over. */
    private static final int WHEAT_STOCK = 30;
    /** The wheat the request for two bone meal uses up. */
    private static final int WHEAT_FOR_TWO = 22;
    /** The points that 22 wheat leave over after two bone meal, in hundredths of a point. */
    private static final int WHEAT_REST = 30;

    private ComposterGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "composter_unlock", ComposterGameTests::unlock);
            TestFunctions.register(helper, "composter_recipe", ComposterGameTests::recipe);
            TestFunctions.register(helper, "composter_points", ComposterGameTests::points);
            TestFunctions.register(helper, "composter_chances", ComposterGameTests::chances);
            TestFunctions.register(helper, "composter_seeds_to_bone_meal", ComposterGameTests::seedsToBoneMeal);
            TestFunctions.register(helper, "composter_hoe_wear", ComposterGameTests::hoeWear);
            TestFunctions.register(helper, "composter_without_hoe", ComposterGameTests::withoutHoe);
            TestFunctions.register(helper, "composter_request_served", ComposterGameTests::requestServed);
            TestFunctions.register(helper, "composter_rest_points_saved", ComposterGameTests::restPointsSaved);
        });
    }

    /** The Composter. */
    static Job composter() {
        return StationTypeGameTests.job(CivMod.id("composter"));
    }

    /** A level-2 Craftsperson JobBlock with the Composter unlocked by a composter in its second Station Types slot. */
    static BlockPos composting(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.COMPOSTER), "A composter unlocks the Composter");
        return pos;
    }

    /** The settler of a Composter with a hoe on its belt, at a composter block three blocks from the JobBlock. */
    private static Settler composterWith(GameTestHelper helper, Setup setup, BlockPos craft, ItemStack hoe) {
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.COMPOSTER);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, composter());
        if (!hoe.isEmpty()) {
            settler.getInventory().setItem(Settler.BELT_START, hoe);
        }
        return settler;
    }

    /**
     * The Composter specializes the Craftsperson (job factor 1.0, base EXP 2, hunger 0.5), is unlocked by a composter and
     * works at a composter block.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job composter = composter();
        helper.assertTrue(composter != null && composter.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Composter specializes the Craftsperson");
        helper.assertTrue(composter.unlockItem().equals(Optional.of(Items.COMPOSTER)), "Unlocked by a composter: " + composter.unlockItem());
        helper.assertTrue(composter.jobFactor() == 1.0 && composter.baseExp() == 2 && composter.hungerFactor() == 0.5,
                "Job factor 1.0, base EXP 2, hunger 0.5: " + composter.jobFactor() + " / " + composter.baseExp() + " / " + composter.hungerFactor());
        helper.assertTrue(composter.icon().is(Items.COMPOSTER) && composter.bubbleIcon().is(Items.BONE_MEAL)
                && composter.displayItem().equals(Optional.of(Items.BONE_MEAL)), "Icon composter, bubble and display item bone meal");
        helper.assertTrue(!composter.description().getString().startsWith("job.civmod"), "A description is set: " + composter.description().getString());
        ProducingProfile profile = composter.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting is the only recipe type");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).location().equals(CivMod.id("stations/composter")),
                "The main station tag is the composter's: " + profile.station(RecipeType.CRAFTING));
        BlockPos pos = composting(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(composter),
                "The job picker offers the Composter");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.COMPOSTER);
        helper.assertTrue(composter.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a composter");
        helper.succeed();
    }

    /**
     * The one innate recipe takes any compostable item, modded ones included, and no other: seeds, crops and leaves in,
     * stone and tools out. It makes one bone meal per item, with a hoe, and it is a checked pattern of the library.
     */
    private static void recipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<RecipeHolder<JobCraftingRecipe>> recipes = InnateRecipes.of(level, composter());
        helper.assertTrue(recipes.size() == 1, "One recipe: " + recipes.size());
        JobCraftingRecipe compost = recipes.getFirst().value();
        helper.assertTrue(compost.ingredients().size() == 1, "One input item per execution: " + compost.ingredients().size());
        List<ItemStack> accepted = List.of(new ItemStack(Items.WHEAT_SEEDS), new ItemStack(Items.WHEAT), new ItemStack(Items.OAK_LEAVES),
                new ItemStack(Items.PUMPKIN), new ItemStack(Items.CAKE));
        for (ItemStack stack : accepted) {
            helper.assertTrue(compost.ingredients().getFirst().test(stack), "Takes " + stack);
        }
        List<ItemStack> refused = List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIAMOND_HOE), new ItemStack(Items.BONE_MEAL));
        for (ItemStack stack : refused) {
            helper.assertFalse(compost.ingredients().getFirst().test(stack), "Refuses " + stack);
        }
        helper.assertTrue(compost.result().filter(made -> made.create().is(Items.BONE_MEAL) && made.count() == 1).isPresent(),
                "Makes bone meal: " + compost.result());
        helper.assertTrue(compost.tool().filter(tool -> tool.test(new ItemStack(Items.IRON_HOE)) && !tool.test(new ItemStack(Items.IRON_SHOVEL))).isPresent(),
                "Needs a hoe: " + compost.tool());
        BlockPos pos = composting(helper, new BlockPos(1, 1, 1));
        Patterns.syncInnate(level, pos, composter());
        Pattern pattern = PatternsTabData.of(level, pos, composter()).pattern(0).orElseThrow();
        helper.assertTrue(pattern.innate() && pattern.checked() && pattern.output().is(Items.BONE_MEAL), "Slot 0 is the checked innate recipe: " + pattern);
        helper.succeed();
    }

    /**
     * The point counter is a pure function. 24 seeds of 0.3 points are 7.2 points: one bone meal and 0.2 points over; the
     * bone meal comes with the 24th seed, not before. Over a long run the yield is the points divided by 7, whatever the
     * chances are, and an item of more than one point can make several.
     */
    private static void points(GameTestHelper helper) {
        int rest = 0;
        int boneMeal = 0;
        for (int seed = 1; seed <= SEEDS_FOR_ONE; seed++) {
            CompostPoints.Step step = CompostPoints.add(rest, 30);
            boneMeal += step.boneMeal();
            rest = step.rest();
            helper.assertTrue(boneMeal == (seed == SEEDS_FOR_ONE ? 1 : 0), "Seed " + seed + ": " + boneMeal + " bone meal");
        }
        helper.assertTrue(rest == SEED_REST, "0.2 points are left: " + rest);
        rest = 0;
        boneMeal = 0;
        for (int item = 0; item < 1000; item++) {
            CompostPoints.Step step = CompostPoints.add(rest, 65);
            boneMeal += step.boneMeal();
            rest = step.rest();
        }
        helper.assertTrue(boneMeal == 92 && rest == 600, "1000 wheat are 650 points: 92 bone meal and 6 points left: " + boneMeal + " / " + rest);
        CompostPoints.Step many = CompostPoints.add(600, 1500);
        helper.assertTrue(many.boneMeal() == 3 && many.rest() == 0, "15 points on 6 make 21: three bone meal, nothing left: " + many);
        helper.assertTrue(CompostPoints.add(0, 0).equals(new CompostPoints.Step(0, 0)), "An item without a chance adds nothing");
        helper.succeed();
    }

    /**
     * A compost chance in hundredths of a point is what the item's weighted compost value gives on average: 0.3 for seeds,
     * 0.65 for wheat, 0.85 for a hay bale, 1 for cake. An item that cannot be composted has none.
     */
    private static void chances(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(CompostChances.percent(level, new ItemStack(Items.WHEAT_SEEDS)) == 30, "Seeds: " + CompostChances.percent(level, new ItemStack(Items.WHEAT_SEEDS)));
        helper.assertTrue(CompostChances.percent(level, new ItemStack(Items.WHEAT)) == 65, "Wheat: " + CompostChances.percent(level, new ItemStack(Items.WHEAT)));
        helper.assertTrue(CompostChances.percent(level, new ItemStack(Items.HAY_BLOCK)) == 85, "Hay bale: " + CompostChances.percent(level, new ItemStack(Items.HAY_BLOCK)));
        helper.assertTrue(CompostChances.percent(level, new ItemStack(Items.CAKE)) == 100, "Cake: " + CompostChances.percent(level, new ItemStack(Items.CAKE)));
        helper.assertTrue(CompostChances.percent(level, new ItemStack(Items.STONE)) == 0, "Stone: " + CompostChances.percent(level, new ItemStack(Items.STONE)));
        helper.succeed();
    }

    /**
     * 24 wheat seeds make 1 bone meal for a Clerk that asks for one. The seeds are used up, 0.2 points are left, and the
     * hoe lost 1 durability. The rest points are part of the saved JobBlock: after a save and load they are still there.
     */
    private static void seedsToBoneMeal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WHEAT_SEEDS, SEEDS_FOR_ONE)), List.of(new ItemStack(Items.BONE_MEAL, 1)));
        BlockPos craft = composting(helper, new BlockPos(1, 1, 5));
        Settler settler = composterWith(helper, setup, craft, new ItemStack(Items.IRON_HOE));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 1,
                "One bone meal arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WHEAT_SEEDS) == 0, "The seeds are used up");
        SettlementBlock block = StationTypeGameTests.block(level, craft);
        helper.assertTrue(block.restPoints(composter()) == SEED_REST, "0.2 points are left: " + block.restPoints(composter()));
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, SettlementData.get(level)).getOrThrow()).getOrThrow();
        SettlementBlock reloaded = loaded.settlementAt(craft).orElseThrow().block(craft).orElseThrow();
        helper.assertTrue(reloaded.restPoints(composter()) == SEED_REST, "The rest survives a save and load: " + reloaded.restPoints(composter()));
        ItemStack hoe = settler.getInventory().getItem(Settler.BELT_START);
        helper.assertTrue(hoe.is(Items.IRON_HOE) && hoe.getDamageValue() == 1, "1 bone meal, 1 durability, not 24: damage " + hoe.getDamageValue());
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** The hoe loses 1 durability per bone meal made, not per item: two bone meal from 22 wheat wear it by 2. */
    private static void hoeWear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WHEAT, WHEAT_STOCK)), List.of(new ItemStack(Items.BONE_MEAL, 2)));
        BlockPos craft = composting(helper, new BlockPos(1, 1, 5));
        Settler settler = composterWith(helper, setup, craft, new ItemStack(Items.IRON_HOE));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        ItemStack hoe = settler.getInventory().getItem(Settler.BELT_START);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 2, "Two bone meal arrived");
        helper.assertTrue(hoe.is(Items.IRON_HOE) && hoe.getDamageValue() == 2, "Two bone meal, 2 durability, not " + WHEAT_FOR_TWO + ": damage " + hoe.getDamageValue());
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** Without a hoe nothing is composted, and the Patterns tab says "Needs Hoe" for the recipe. */
    private static void withoutHoe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WHEAT, WHEAT_STOCK)), List.of(new ItemStack(Items.BONE_MEAL, 2)));
        BlockPos craft = composting(helper, new BlockPos(1, 1, 5));
        Settler settler = composterWith(helper, setup, craft, ItemStack.EMPTY);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WHEAT) == WHEAT_STOCK, "The wheat stays");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 0, "Nothing was composted");
        PatternsTabData tab = PatternsTabData.of(level, craft, composter());
        Optional<String> line = Optional.ofNullable(tab.needs().get(0)).map(Component::getString);
        helper.assertTrue(line.equals(Optional.of("Needs Hoe")), "The recipe is marked: " + tab.needs());
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A request for 2 bone meal is served from a stock of wheat although single items make none: the rounds go on until the
     * amount is delivered, the request is gone then and the rest points stay for the next bone meal.
     */
    private static void requestServed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WHEAT, WHEAT_STOCK)), List.of(new ItemStack(Items.BONE_MEAL, 2)));
        helper.assertTrue(Requests.claimable(setup.settlement()).size() == 1, "One request for bone meal");
        BlockPos craft = composting(helper, new BlockPos(1, 1, 5));
        Settler settler = composterWith(helper, setup, craft, new ItemStack(Items.NETHERITE_HOE));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 2,
                "Two bone meal arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WHEAT) == WHEAT_STOCK - WHEAT_FOR_TWO, "22 wheat were used up, no more");
        helper.assertTrue(StationTypeGameTests.block(level, craft).restPoints(composter()) == WHEAT_REST, "0.3 points are left");
        helper.assertTrue(Requests.claimable(setup.settlement()).isEmpty(), "The request is done: " + Requests.claimable(setup.settlement()));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** The rest points are saved with the JobBlock even while the Composter library holds no pattern. */
    private static void restPointsSaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = composting(helper, new BlockPos(1, 1, 5));
        new LibraryRestPoints(level, craft, composter()).set(SEED_REST);
        SettlementBlock block = StationTypeGameTests.block(level, craft);
        helper.assertTrue(block.patterns(composter()).isEmpty(), "The library holds no pattern: " + block.patterns(composter()));
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, SettlementData.get(level)).getOrThrow()).getOrThrow();
        SettlementBlock reloaded = loaded.settlementAt(craft).orElseThrow().block(craft).orElseThrow();
        helper.assertTrue(reloaded.restPoints(composter()) == SEED_REST, "The rest survives a save and load: " + reloaded.restPoints(composter()));
        helper.succeed();
    }
}
