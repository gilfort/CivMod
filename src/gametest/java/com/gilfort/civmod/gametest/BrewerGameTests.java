package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Brewer (#256, vault {@code Jobs/Brewer.md}): a Specialization of the Craftsperson unlocked by a barrel
 * that works one innate recipe, 1 water bucket + 3 wheat + 3 glass bottles for 3 beer with the empty bucket as remainder.
 * Beer is food that settlers never eat.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BrewerGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few executions. */
    private static final long WORK_TICKS = 4000L;
    /** Most catch-up passes the chain test needs until the beer arrives. */
    private static final int MAX_PASSES = 4;
    /** Beer one execution makes, and the wheat and glass bottles it takes. */
    private static final int BATCH = 3;
    /** Empty buckets that lie in the Brewer's buffer before the round of the spare bucket test. */
    private static final int SPARE_BUCKETS = 2;
    /** Ticks of the nausea beer gives (3 seconds). */
    private static final int NAUSEA_TICKS = 60;
    /** Ticks of the regeneration beer gives (10 seconds). */
    private static final int REGENERATION_TICKS = 200;

    private BrewerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "brewer_beer_item", BrewerGameTests::beerItem);
            TestFunctions.register(helper, "brewer_unlock", BrewerGameTests::unlock);
            TestFunctions.register(helper, "brewer_recipe", BrewerGameTests::recipe);
            TestFunctions.register(helper, "brewer_brews_beer", BrewerGameTests::brewsBeer);
            TestFunctions.register(helper, "brewer_spare_buckets_stay", BrewerGameTests::spareBucketsStay);
            TestFunctions.register(helper, "brewer_beer_not_eaten", BrewerGameTests::beerNotEaten);
            TestFunctions.register(helper, "brewer_bottler_chain", BrewerGameTests::bottlerChain);
        });
    }


    /** The Brewer. */
    static Job brewer() {
        return StationTypeGameTests.job(CivMod.id("brewer"));
    }

    /** A level-2 Craftsperson JobBlock with the Brewer unlocked by a barrel in its second Station Types slot. */
    static BlockPos brewery(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.BARREL), "A barrel unlocks the Brewer");
        return pos;
    }

    /**
     * Beer is food with nutrition 1 and saturation 2, drunk in 1.6 seconds like the honey bottle: nausea for 3 seconds and
     * regeneration for 10 seconds. It stacks to 16 and leaves a glass bottle.
     */
    private static void beerItem(GameTestHelper helper) {
        ItemStack beer = new ItemStack(ModItems.BEER.get());
        FoodProperties food = beer.get(DataComponents.FOOD);
        helper.assertTrue(food != null && food.nutrition() == 1 && food.saturation() == 2.0F, "Nutrition 1, saturation 2: " + food);
        helper.assertTrue(beer.getMaxStackSize() == 16, "Stacks to 16: " + beer.getMaxStackSize());
        Consumable drink = beer.get(DataComponents.CONSUMABLE);
        helper.assertTrue(drink != null && drink.animation() == ItemUseAnimation.DRINK, "Drunk with the drink animation: " + drink);
        List<MobEffectInstance> effects = drink.onConsumeEffects().stream().filter(ApplyStatusEffectsConsumeEffect.class::isInstance)
                .map(ApplyStatusEffectsConsumeEffect.class::cast).flatMap(effect -> effect.effects().stream()).toList();
        helper.assertTrue(effects.size() == 2 && hasEffect(effects, MobEffects.NAUSEA, NAUSEA_TICKS)
                && hasEffect(effects, MobEffects.REGENERATION, REGENERATION_TICKS), "Nausea 3 s and regeneration 10 s: " + effects);
        helper.assertTrue(beer.get(DataComponents.USE_REMAINDER) != null && beer.get(DataComponents.USE_REMAINDER).convertInto().create().is(Items.GLASS_BOTTLE),
                "Drinking leaves a glass bottle");
        helper.assertTrue(beer.is(SettlerFood.INEDIBLE) && !SettlerFood.isEdible(beer), "Settlers never eat it, so it never counts for food variety");
        helper.succeed();
    }

    private static boolean hasEffect(List<MobEffectInstance> effects, Holder<MobEffect> type, int ticks) {
        return effects.stream().anyMatch(effect -> effect.getEffect().equals(type) && effect.getDuration() == ticks);
    }

    /**
     * The Brewer specializes the Craftsperson (job factor 0.5, base EXP 2, hunger 0.5), is unlocked by a barrel, works innate
     * recipes only at a barrel and looks like a barrel with beer.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job brewer = brewer();
        helper.assertTrue(brewer != null && brewer.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Brewer specializes the Craftsperson");
        helper.assertTrue(brewer.unlockItem().equals(Optional.of(Items.BARREL)), "Unlocked by a barrel: " + brewer.unlockItem());
        helper.assertTrue(brewer.jobFactor() == 0.5 && brewer.baseExp() == 2 && brewer.hungerFactor() == 0.5,
                "Job factor 0.5, base EXP 2, hunger 0.5: " + brewer.jobFactor() + " / " + brewer.baseExp() + " / " + brewer.hungerFactor());
        helper.assertTrue(brewer.icon().is(Items.BARREL) && brewer.bubbleIcon().is(ModItems.BEER.get())
                && brewer.displayItem().equals(Optional.of(ModItems.BEER.get())), "Icon barrel, bubble and display item beer");
        helper.assertTrue(brewer.description().getString().equals("Brews beer from water, wheat and glass bottles in a barrel"),
                "Description: " + brewer.description().getString());
        ProducingProfile profile = brewer.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting is the only recipe type");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).location().equals(CivMod.id("stations/barrel")),
                "The main station tag is the barrel's: " + profile.station(RecipeType.CRAFTING));
        BlockPos pos = brewery(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(brewer),
                "The job picker offers the Brewer");
        helper.succeed();
    }

    /**
     * The one innate recipe: a water bucket (any bucket of the water tag), three wheat and three glass bottles make three
     * beer. The Brewer has no recipe besides it.
     */
    private static void recipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<JobCraftingRecipe> recipes = InnateRecipes.of(level, brewer()).stream().map(RecipeHolder::value).toList();
        helper.assertTrue(recipes.size() == 1, "One recipe: " + recipes.size());
        JobCraftingRecipe beer = recipes.getFirst();
        helper.assertTrue(beer.result().filter(made -> made.create().is(ModItems.BEER.get()) && made.count() == BATCH).isPresent(), "Makes 3 beer: " + beer.result());
        helper.assertTrue(beer.ingredients().size() == 1 + 2 * BATCH, "Seven ingredients: " + beer.ingredients().size());
        helper.assertTrue(count(beer, new ItemStack(Items.WATER_BUCKET)) == 1, "One water bucket");
        helper.assertTrue(count(beer, new ItemStack(Items.WHEAT)) == BATCH, "Three wheat");
        helper.assertTrue(count(beer, new ItemStack(Items.GLASS_BOTTLE)) == BATCH, "Three glass bottles");
        helper.assertTrue(count(beer, new ItemStack(Items.BUCKET)) == 0 && count(beer, new ItemStack(Items.WATER_BUCKET)) == 1,
                "The empty bucket is no input, any water bucket is");
        helper.assertTrue(beer.station().isEmpty() && beer.stationAfter().isEmpty() && beer.tool().isEmpty(),
                "Worked at the main barrel, which stays, without a tool");
        helper.succeed();
    }

    /** How many of the recipe's ingredients accept the stack. */
    private static int count(JobCraftingRecipe recipe, ItemStack stack) {
        return (int) recipe.ingredients().stream().filter(ingredient -> ingredient.test(stack)).count();
    }

    /**
     * A Clerk asks for 3 beer; a Brewer with the water bucket, wheat and glass bottles in stock delivers 3 beer and the empty
     * bucket, and the barrel stays.
     */
    private static void brewsBeer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper,
                List.of(new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.WHEAT, BATCH), new ItemStack(Items.GLASS_BOTTLE, BATCH)),
                List.of(new ItemStack(ModItems.BEER.get(), BATCH)));
        BlockPos craft = brewery(helper, new BlockPos(1, 1, 5));
        BlockPos barrel = new BlockPos(3, 1, 5);
        helper.setBlock(barrel, Blocks.BARREL);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, brewer());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), ModItems.BEER.get()) == BATCH,
                "Three beer arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BUCKET) == 1, "The empty bucket came back");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WATER_BUCKET) == 0
                && FletcherGameTests.count(level, setup.clerk(), Items.WHEAT) == 0
                && FletcherGameTests.count(level, setup.clerk(), Items.GLASS_BOTTLE) == 0, "The inputs are used up");
        helper.assertTrue(helper.getBlockState(barrel).is(Blocks.BARREL), "The barrel stays: " + helper.getBlockState(barrel));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Two empty buckets lie in the Brewer's buffer before the round: only the one bucket the brewing leaves is delivered
     * with the beer, the spare ones stay in the buffer.
     */
    private static void spareBucketsStay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper,
                List.of(new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.WHEAT, BATCH), new ItemStack(Items.GLASS_BOTTLE, BATCH)),
                List.of(new ItemStack(ModItems.BEER.get(), BATCH)));
        BlockPos craft = brewery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.BARREL);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, brewer());
        // A first pass of one tick creates the JobBlock's workplace, whose buffer takes the spare buckets.
        CraftspersonGameTests.catchUp(level, settler, 1L);
        settler.getWork().buffer(craft).add(new ItemStack(Items.BUCKET, SPARE_BUCKETS));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), ModItems.BEER.get()) == BATCH,
                "Three beer arrived (status " + settler.getWork().status(craft) + ")");
        int delivered = FletcherGameTests.count(level, setup.clerk(), Items.BUCKET);
        helper.assertTrue(delivered == 1, "Only the bucket the brewing left was delivered: " + delivered);
        int kept = settler.getWork().buffer(craft).counts().getOrDefault(ItemKey.of(Items.BUCKET), 0);
        helper.assertTrue(kept == SPARE_BUCKETS, "The spare buckets stayed in the buffer: " + kept);
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A Pantry that holds only beer is no food point: the settler finds nothing to eat there, and beer is no meal in the food
     * choice either. With bread next to it the Pantry is a food point again, so the beer is the only difference.
     */
    private static void beerNotEaten(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = helper.absolutePos(new BlockPos(6, 1, 6));
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        TestSettlements.setBlock(helper, new BlockPos(6, 1, 6), ModBlocks.PANTRY_BLOCK.get());
        Container storage = (Container) level.getBlockEntity(pantry);
        storage.setItem(0, new ItemStack(ModItems.BEER.get(), 16));
        Settlement settlement = SettlementData.get(level).settlementAt(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
        BlockPos from = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.assertTrue(ClerkStorage.nearestFood(level, settlement, from, ClerkGameTests.MORNING, Optional.empty(), pos -> true).isEmpty(),
                "A Pantry with only beer is no food point");
        helper.assertTrue(SettlerFood.choose(List.of(new ItemStack(ModItems.BEER.get(), 16)), 0).isEmpty(), "Beer is never chosen as a meal");
        storage.setItem(1, new ItemStack(Items.BREAD, 4));
        helper.assertTrue(ClerkStorage.nearestFood(level, settlement, from, ClerkGameTests.MORNING, Optional.empty(), pos -> true).isPresent(),
                "With bread the Pantry is a food point");
        helper.assertTrue(SettlerFood.choose(List.of(new ItemStack(ModItems.BEER.get(), 16), new ItemStack(Items.BREAD, 4)), 0).equals(Optional.of(1)),
                "Bread is chosen, never the beer next to it");
        helper.succeed();
    }

    /**
     * The chain: a Clerk asks for beer, only an empty bucket, wheat and glass bottles are in stock. The Brewer asks for the
     * water bucket (a child request for the Clerk), the Bottler fills a bucket at a water cauldron, the Brewer brews and
     * the empty bucket comes back to the Clerk.
     */
    private static void bottlerChain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper,
                List.of(new ItemStack(Items.BUCKET), new ItemStack(Items.WHEAT, BATCH), new ItemStack(Items.GLASS_BOTTLE, BATCH)),
                List.of(new ItemStack(ModItems.BEER.get(), BATCH)));
        UUID beerRequest = Requests.claimable(setup.settlement()).getFirst().id();
        BlockPos brewery = brewery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.BARREL);
        BlockPos bottlery = BottlerGameTests.bottlery(helper, new BlockPos(1, 1, 9));
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.WATER_CAULDRON);
        Settler brewer = FletcherGameTests.worker(helper, setup, brewery, brewer());
        Settler bottler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 11), setup.data(), setup.settlement());
        bottler.setCustomName(Component.literal("Bruno"));
        bottler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(setup.data(), bottlery, bottler.getUUID(), "Bruno",
                setup.settlement().id(), List.of(), BottlerGameTests.bottler()) == JobAssignments.AssignResult.OK, "Bottler assigned");
        bottler.addJob(new SettlerJob(BottlerGameTests.bottler(), bottlery));

        // The server lays out the innate recipes of every library when it starts; the Bottler checks its water bucket recipe.
        Patterns.syncInnate(level);

        // The Brewer alone: no water bucket, so it asks for one and waits.
        CraftspersonGameTests.catchUp(level, brewer, WORK_TICKS);
        List<Request> children = Requests.children(setup.settlement(), beerRequest);
        helper.assertTrue(children.size() == 1 && children.getFirst().item().is(Items.WATER_BUCKET) && children.getFirst().target().equals(Optional.of(setup.clerk())),
                "A child request for the water bucket at the Clerk: " + children);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), ModItems.BEER.get()) == 0, "No beer yet");

        // The Bottler fills the bucket; then the Brewer takes it and brews.
        for (int pass = 0; pass < MAX_PASSES && FletcherGameTests.count(level, setup.clerk(), ModItems.BEER.get()) == 0; pass++) {
            CraftspersonGameTests.catchUp(level, bottler, WORK_TICKS);
            CraftspersonGameTests.catchUp(level, brewer, WORK_TICKS);
        }
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), ModItems.BEER.get()) == BATCH,
                "Three beer arrived (status " + brewer.getWork().status(brewery) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BUCKET) == 1, "The empty bucket is back at the Clerk");
        TestAssignments.unassign(setup.data(), bottlery);
        bottler.discard();
        FletcherGameTests.cleanUp(setup, brewer);
        helper.succeed();
    }
}
