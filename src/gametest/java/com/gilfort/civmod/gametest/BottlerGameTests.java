package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Bottler (#255, vault {@code Jobs/Bottler.md}): a Specialization of the Craftsperson unlocked by a
 * cauldron that works innate recipes only. A bucket becomes a water bucket at a water cauldron (the water stays) or a
 * lava bucket at a lava cauldron (the cauldron is empty afterwards, {@code station_after}); a glass bottle becomes
 * dragon's breath at a placed dragon egg, which stays.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BottlerGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few executions at job factor 1.0. */
    private static final long WORK_TICKS = 4000L;
    /** How many innate recipes the Bottler has: water bucket, lava bucket, dragon's breath and water bottle. */
    private static final int RECIPES = 4;

    private BottlerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "bottler_unlock", BottlerGameTests::unlock);
            TestFunctions.register(helper, "bottler_recipes", BottlerGameTests::recipes);
            TestFunctions.register(helper, "bottler_water_bucket", BottlerGameTests::waterBucket);
            TestFunctions.register(helper, "bottler_lava_bucket", BottlerGameTests::lavaBucket);
            TestFunctions.register(helper, "bottler_water_without_cauldron", BottlerGameTests::waterWithoutCauldron);
            TestFunctions.register(helper, "bottler_dragons_breath", BottlerGameTests::dragonsBreath);
            TestFunctions.register(helper, "bottler_water_bottle", BottlerGameTests::waterBottle);
            TestFunctions.register(helper, "bottler_missing_bucket", BottlerGameTests::missingBucket);
        });
    }


    /** The Bottler. */
    static Job bottler() {
        return StationTypeGameTests.job(CivMod.id("bottler"));
    }

    /** A level-2 Craftsperson JobBlock with the Bottler unlocked by a cauldron in its second Station Types slot. */
    static BlockPos bottlery(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.CAULDRON), "A cauldron unlocks the Bottler");
        return pos;
    }

    /**
     * The Bottler specializes the Craftsperson (job factor 1.0, base EXP 2, hunger 0.5), is unlocked by a cauldron, works
     * innate recipes only at the water cauldron station and looks like a cauldron with a water bucket.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job bottler = bottler();
        helper.assertTrue(bottler != null && bottler.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Bottler specializes the Craftsperson");
        helper.assertTrue(bottler.unlockItem().equals(Optional.of(Items.CAULDRON)), "Unlocked by a cauldron: " + bottler.unlockItem());
        helper.assertTrue(bottler.jobFactor() == 1.0 && bottler.baseExp() == 2 && bottler.hungerFactor() == 0.5,
                "Job factor 1.0, base EXP 2, hunger 0.5: " + bottler.jobFactor() + " / " + bottler.baseExp() + " / " + bottler.hungerFactor());
        helper.assertTrue(bottler.icon().is(Items.CAULDRON) && bottler.bubbleIcon().is(Items.WATER_BUCKET)
                && bottler.displayItem().equals(Optional.of(Items.WATER_BUCKET)), "Icon cauldron, bubble and display item water bucket");
        helper.assertTrue(bottler.description().getString().equals("Fills buckets and bottles at cauldrons and at a dragon egg"),
                "Description: " + bottler.description().getString());
        ProducingProfile profile = bottler.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting is the only recipe type");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).location().equals(CivMod.id("stations/bottler")),
                "The main station tag is the union of the three stations: " + profile.station(RecipeType.CRAFTING));
        BlockPos pos = bottlery(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(bottler),
                "The job picker offers the Bottler");
        helper.succeed();
    }

    /**
     * Four innate recipes, each one input to one product: bucket to water bucket (no station of its own, so the main
     * water cauldron), bucket to lava bucket at a lava cauldron that becomes an empty cauldron, glass bottle to dragon's
     * breath at a dragon egg that stays, glass bottle to water bottle (a water potion) at a water cauldron that stays. The block states survive the network codec (recipe viewers).
     */
    private static void recipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<JobCraftingRecipe> recipes = InnateRecipes.of(level, bottler()).stream().map(RecipeHolder::value).toList();
        helper.assertTrue(recipes.size() == RECIPES, "Four recipes: " + recipes.size());
        JobCraftingRecipe water = recipe(helper, recipes, Items.WATER_BUCKET);
        JobCraftingRecipe lava = recipe(helper, recipes, Items.LAVA_BUCKET);
        JobCraftingRecipe breath = recipe(helper, recipes, Items.DRAGON_BREATH);
        JobCraftingRecipe bottle = recipes.stream().filter(recipe -> recipe.result().filter(made -> ItemKey.of(made.create()).equals(waterBottle())).isPresent())
                .findFirst().orElse(null);
        helper.assertTrue(bottle != null, "A recipe makes a water bottle");
        helper.assertTrue(bottle.station().filter(tag -> tag.location().equals(CivMod.id("stations/water_cauldron"))).isPresent()
                && bottle.stationAfter().isEmpty() && bottle.tool().isEmpty(), "Water bottle: water cauldron, left as it is, no tool");
        helper.assertTrue(bottle.ingredients().size() == 1 && bottle.ingredients().getFirst().test(new ItemStack(Items.GLASS_BOTTLE)),
                "A glass bottle makes the water bottle");
        helper.assertTrue(water.station().filter(tag -> tag.location().equals(CivMod.id("stations/water_cauldron"))).isPresent()
                && water.stationAfter().isEmpty(), "Water: water cauldron, left as it is");
        helper.assertTrue(lava.station().filter(tag -> tag.location().equals(CivMod.id("stations/lava_cauldron"))).isPresent()
                && lava.stationAfter().equals(Optional.of(Blocks.CAULDRON.defaultBlockState())), "Lava: lava cauldron, empty cauldron afterwards");
        helper.assertTrue(breath.station().filter(tag -> tag.location().equals(CivMod.id("stations/dragon_egg"))).isPresent()
                && breath.stationAfter().isEmpty(), "Dragon's breath: dragon egg, left as it is");
        helper.assertTrue(lava.ingredients().getFirst().test(new ItemStack(Items.BUCKET)) && breath.ingredients().getFirst().test(new ItemStack(Items.GLASS_BOTTLE)),
                "A bucket makes the lava bucket, a glass bottle the breath");
        helper.assertTrue(lava.tool().isEmpty() && water.tool().isEmpty() && breath.tool().isEmpty(), "No recipe needs a tool");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        JobCraftingRecipe.STREAM_CODEC.encode(buffer, lava);
        JobCraftingRecipe copy = JobCraftingRecipe.STREAM_CODEC.decode(buffer);
        helper.assertTrue(copy.stationAfter().equals(lava.stationAfter()), "station_after travels to the clients: " + copy.stationAfter());
        helper.succeed();
    }

    private static JobCraftingRecipe recipe(GameTestHelper helper, List<JobCraftingRecipe> recipes, Item result) {
        Optional<JobCraftingRecipe> found = recipes.stream().filter(recipe -> recipe.result().filter(made -> made.create().is(result)).isPresent()).findFirst();
        helper.assertTrue(found.isPresent(), "A recipe makes " + result);
        return found.orElseThrow();
    }

    /** The key of a water bottle: a potion item with water contents. */
    static ItemKey waterBottle() {
        return ItemKey.of(ItemKeyGameTests.potion(Potions.WATER));
    }

    /**
     * A Clerk Min rule on water bottles, an awkward potion and a glass bottle in stock: the awkward potion does not meet the
     * rule, the Bottler fills the glass bottle at a water cauldron that keeps its water, and the result is a real water bottle.
     */
    private static void waterBottle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(ItemKeyGameTests.potion(Potions.AWKWARD), new ItemStack(Items.GLASS_BOTTLE)),
                List.of(ItemKeyGameTests.potion(Potions.WATER)));
        BlockPos craft = bottlery(helper, new BlockPos(1, 1, 5));
        BlockPos cauldron = new BlockPos(3, 1, 5);
        BlockState filled = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 2);
        helper.setBlock(cauldron, filled);
        helper.assertTrue(ClerkStorage.stock(level, setup.clerk(), true).count(waterBottle()::matches) == 0, "An awkward potion is no water bottle");
        Settler settler = FletcherGameTests.worker(helper, setup, craft, bottler());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(ClerkStorage.stock(level, setup.clerk(), true).count(waterBottle()::matches) == 1,
                "The water bottle arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(ClerkStorage.stock(level, setup.clerk(), true).count(ItemKey.of(ItemKeyGameTests.potion(Potions.AWKWARD))::matches) == 1,
                "The awkward potion is still there, untouched");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.GLASS_BOTTLE) == 0, "The glass bottle is used up");
        helper.assertTrue(helper.getBlockState(cauldron).equals(filled), "The cauldron holds the same water level: " + helper.getBlockState(cauldron));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** A Clerk Min rule on a water bucket, a bucket in stock: the Bottler fills it at a water cauldron that keeps its water. */
    private static void waterBucket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.BUCKET)), List.of(new ItemStack(Items.WATER_BUCKET)));
        BlockPos craft = bottlery(helper, new BlockPos(1, 1, 5));
        BlockPos cauldron = new BlockPos(3, 1, 5);
        BlockState filled = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 2);
        helper.setBlock(cauldron, filled);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, bottler());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WATER_BUCKET) == 1,
                "The water bucket arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BUCKET) == 0, "The empty bucket is used up");
        helper.assertTrue(helper.getBlockState(cauldron).equals(filled), "The cauldron holds the same water level: " + helper.getBlockState(cauldron));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A Clerk asks for a lava bucket: the Bottler fills a bucket at a lava cauldron, which is an empty cauldron afterwards. A
     * second request stays open, marked "Needs a station nearby: Lava Cauldron" while no lava cauldron is left.
     */
    private static void lavaBucket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.BUCKET, 2)), List.of(new ItemStack(Items.LAVA_BUCKET)));
        BlockPos craft = bottlery(helper, new BlockPos(1, 1, 5));
        BlockPos lava = new BlockPos(3, 1, 5);
        helper.setBlock(lava, Blocks.LAVA_CAULDRON);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, bottler());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.LAVA_BUCKET) == 1,
                "The lava bucket arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(helper.getBlockState(lava).is(Blocks.CAULDRON), "The lava cauldron is empty afterwards: " + helper.getBlockState(lava));

        helper.assertTrue(StockRules.setRule(setup.data(), setup.clerk(), StockRule.minimum(0, ItemKey.of(Items.LAVA_BUCKET), 2, 0)), "Second lava bucket wanted");
        StockRules.check(level, setup.clerk(), ClerkGameTests.MORNING);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.BLOCKED, "No station at all: " + settler.getWork().status(craft));
        helper.assertValueEqual(settler.getWork().statusText(craft).getString(), "Needs a station nearby: Cauldron or Dragon Egg", "Reason");
        // A water cauldron stands again: the job works, and the lava recipe alone is marked.
        helper.setBlock(new BlockPos(9, 1, 5), Blocks.WATER_CAULDRON);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.LAVA_BUCKET) == 1, "No second lava bucket without lava");
        helper.assertTrue(Requests.ofClerk(setup.settlement(), setup.clerk()).stream().anyMatch(request -> request.item().is(Items.LAVA_BUCKET)),
                "The request stays open");
        PatternsTabData tab = PatternsTabData.of(level, craft, bottler());
        helper.assertTrue(tab.needs().values().stream().map(Component::getString).toList().contains("Needs a station nearby: Lava Cauldron"),
                "The lava recipe is marked: " + tab.needs());
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * With a lava cauldron alone (no water cauldron) a water bucket request stays open and the recipe is marked "Needs a
     * station nearby: Water Cauldron": every recipe checks its own station.
     */
    private static void waterWithoutCauldron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.BUCKET)), List.of(new ItemStack(Items.WATER_BUCKET)));
        BlockPos craft = bottlery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.LAVA_CAULDRON);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, bottler());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WATER_BUCKET) == 0, "No water bucket without a water cauldron");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BUCKET) == 1, "The empty bucket stays");
        PatternsTabData tab = PatternsTabData.of(level, craft, bottler());
        helper.assertTrue(tab.needs().values().stream().map(Component::getString).toList().contains("Needs a station nearby: Water Cauldron"),
                "The water recipe is marked: " + tab.needs());
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** A glass bottle becomes dragon's breath at a placed dragon egg, with no cauldron at all; the egg is still there afterwards. */
    private static void dragonsBreath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.GLASS_BOTTLE)), List.of(new ItemStack(Items.DRAGON_BREATH)));
        BlockPos craft = bottlery(helper, new BlockPos(1, 1, 5));
        BlockPos egg = new BlockPos(3, 1, 5);
        helper.setBlock(egg, Blocks.DRAGON_EGG);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, bottler());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.DRAGON_BREATH) == 1,
                "The dragon's breath arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.GLASS_BOTTLE) == 0, "The glass bottle is used up");
        helper.assertTrue(helper.getBlockState(egg).is(Blocks.DRAGON_EGG), "The egg stays: " + helper.getBlockState(egg));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * No empty bucket anywhere: the Bottler asks the settlement for it like for any missing input (a child request for the
     * Clerk), which a Craftsperson with a bucket pattern can make.
     */
    private static void missingBucket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(), List.of(new ItemStack(Items.LAVA_BUCKET)));
        BlockPos workshop = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 9));
        CraftspersonGameTests.define(helper, workshop, 0, Items.BUCKET);
        helper.assertTrue(TestAssignments.assign(setup.data(), workshop, UUID.randomUUID(), "Carl", setup.settlement().id(), List.of())
                == JobAssignments.AssignResult.OK, "Craftsperson assigned");
        BlockPos craft = bottlery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.LAVA_CAULDRON);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, bottler());
        Request lava = Requests.claimable(setup.settlement()).getFirst();
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        List<Request> children = Requests.children(setup.settlement(), lava.id());
        helper.assertTrue(children.size() == 1 && children.getFirst().item().is(Items.BUCKET) && children.getFirst().target().equals(Optional.of(setup.clerk())),
                "A child request for the bucket at the Clerk: " + children);
        TestAssignments.unassign(setup.data(), workshop);
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }
}
