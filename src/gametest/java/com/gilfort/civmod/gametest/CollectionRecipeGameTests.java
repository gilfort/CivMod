package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.job.CollectionRecipes;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.HolderSet;
import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the innate recipes of Resource Collection Jobs (vault {@code Systems/Resource Collection Jobs.md},
 * decisions 2026-10-27): a recipe whose {@code requires} is in the scan joins the roll; the settler fetches the input
 * from its collect inventory, consumes it and collects the result. Each test plays live until the scan is done (and the
 * input fetched), then catches up a day: one cow gives only a few live rolls, a caught-up day enough for a sure result.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CollectionRecipeGameTests {

    private static final long DAY = 24000L;
    private static final Identifier MILK = CivMod.id("animal_farmer/milk_bucket");
    private static final Identifier HONEY = CivMod.id("beekeeper/honey_bottle");
    private static final Identifier COW = Identifier.withDefaultNamespace("cow");
    private static final Identifier BEEHIVE = Identifier.withDefaultNamespace("beehive");

    private CollectionRecipeGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "collection_recipe_milk", CollectionRecipeGameTests::milk);
            register(helper, "collection_recipe_no_input", CollectionRecipeGameTests::noInput);
            register(helper, "collection_recipe_max", CollectionRecipeGameTests::atMax);
            register(helper, "collection_recipe_honey_needs_full_hive", CollectionRecipeGameTests::honeyNeedsFullHive);
            register(helper, "collection_recipe_honey_bottles", CollectionRecipeGameTests::honeyBottles);
            register(helper, "collection_recipe_full_hive_per_block", CollectionRecipeGameTests::fullHivePerBlock);
            register(helper, "collection_recipe_block_state_requirement", CollectionRecipeGameTests::blockStateRequirement);
            register(helper, "collection_recipe_weight_by_count", CollectionRecipeGameTests::weightByCount);
            register(helper, "collection_recipe_keeps_job_running", CollectionRecipeGameTests::keepsJobRunning);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** How many of an item a container holds. */
    static int count(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                total += container.getItem(i).getCount();
            }
        }
        return total;
    }

    /** How many of an item the settler carries on its belt. */
    static int belt(Settler settler, Item item) {
        int total = 0;
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            if (settler.getInventory().getItem(i).is(item)) {
                total += settler.getInventory().getItem(i).getCount();
            }
        }
        return total;
    }

    /** How many of the items the settler collected: in its buffer and the containers (collect inventory, Clerk). */
    private static int collected(Settler settler, BlockPos jobBlock, List<Container> containers, Item... items) {
        int total = 0;
        for (Item item : items) {
            total += settler.getWork().buffer(jobBlock).counts().getOrDefault(item, 0);
            for (Container container : containers) {
                total += count(container, item);
            }
        }
        return total;
    }

    private static void catchUpDay(ServerLevel level, Settler settler) {
        long clock = level.getOverworldClockTime();
        settler.getWork().setCatchUp(clock - DAY, clock);
        CatchUp.runNow(level, List.of(settler));
    }

    private static void cleanUp(GameTestHelper helper, Settler settler, List<long[]> forced) {
        settler.discard();
        forced.forEach(chunk -> helper.getLevel().setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    /** An Animal Farmer in a pen with one cow and a stone axe; {@code buckets} buckets in its collect inventory. */
    private static Settler milkingFarmer(GameTestHelper helper, BlockPos jobBlock, int buckets) {
        AnimalFarmerGameTests.animal(helper, EntityTypes.COW, new BlockPos(6, 1, 6), false);
        if (buckets > 0) {
            ((Container) helper.getLevel().getBlockEntity(jobBlock)).setItem(0, new ItemStack(Items.BUCKET, buckets));
        }
        Settler settler = AnimalFarmerGameTests.animalFarmer(helper, jobBlock);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        return settler;
    }

    /**
     * An Animal Farmer with a cow and buckets in its collect inventory fetches buckets onto its belt and delivers milk
     * buckets: each milk bucket used up one bucket.
     */
    private static void milk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        Container collect = (Container) level.getBlockEntity(jobBlock);
        Settler settler = milkingFarmer(helper, jobBlock, 16);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(belt(settler, Items.BUCKET) > 0,
                        "No buckets fetched (status " + settler.getWork().status(jobBlock) + ", tab " + FoundTabData.of(level, jobBlock) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(FoundTabData.of(level, jobBlock).entry(MILK).flatMap(FoundTabData.Entry::needsInput).isEmpty(),
                            "Milk entry not greyed out with buckets");
                    catchUpDay(level, settler);
                    int milk = collected(settler, jobBlock, List.of(collect), Items.MILK_BUCKET);
                    helper.assertTrue(milk > 0, "No milk in a day (buffer " + settler.getWork().buffer(jobBlock).counts() + ")");
                    helper.assertValueEqual(count(collect, Items.BUCKET) + belt(settler, Items.BUCKET) + milk, 16, "Each milk bucket used up one bucket");
                    cleanUp(helper, settler, forced);
                })
                .thenSucceed();
    }

    /**
     * Without buckets the milk entry is listed greyed out ("Needs: Bucket") and the Animal Farmer is not blocked: it
     * collects cow loot.
     */
    private static void noInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        Container collect = (Container) level.getBlockEntity(jobBlock);
        Settler settler = milkingFarmer(helper, jobBlock, 0);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FoundTabData.of(level, jobBlock).entry(MILK).flatMap(FoundTabData.Entry::needsInput).isPresent(),
                        "Milk entry not greyed out: " + FoundTabData.of(level, jobBlock)))
                .thenExecute(() -> {
                    catchUpDay(level, settler);
                    helper.assertTrue(collected(settler, jobBlock, List.of(collect), Items.BEEF, Items.LEATHER) > 0,
                            "No cow loot (status " + settler.getWork().status(jobBlock) + ")");
                    helper.assertValueEqual(collected(settler, jobBlock, List.of(collect), Items.MILK_BUCKET), 0, "No milk without buckets");
                    cleanUp(helper, settler, forced);
                })
                .thenSucceed();
    }

    /**
     * With its Max of milk buckets at the Home Clerk the milk entry is marked and leaves the roll: the Animal Farmer
     * collects cow loot but no milk, and takes and uses up no bucket.
     */
    private static void atMax(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        BlockPos clerkRelative = new BlockPos(1, 1, 7);
        helper.setBlock(clerkRelative, ModBlocks.CLERK_BLOCK.get());
        BlockPos clerk = helper.absolutePos(clerkRelative);
        helper.assertValueEqual(JobBlockSite.blockAt(level, jobBlock).orElseThrow().homeClerk(), Optional.of(clerk), "Home Clerk");
        helper.assertTrue(StockRules.setRule(SettlementData.get(level), clerk, new StockRule(0, Items.MILK_BUCKET, 0, 1, 0, 0)), "Max rule set");
        Container store = (Container) level.getBlockEntity(clerk);
        store.setItem(0, new ItemStack(Items.MILK_BUCKET, 1));
        Container collect = (Container) level.getBlockEntity(jobBlock);
        Settler settler = milkingFarmer(helper, jobBlock, 16);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FoundTabData.of(level, jobBlock).entry(MILK).flatMap(FoundTabData.Entry::maxAt).isPresent(),
                        "Milk entry not at Max: " + FoundTabData.of(level, jobBlock)))
                .thenExecute(() -> {
                    catchUpDay(level, settler);
                    helper.assertTrue(collected(settler, jobBlock, List.of(collect, store), Items.BEEF, Items.LEATHER) > 0,
                            "No cow loot (status " + settler.getWork().status(jobBlock) + ")");
                    helper.assertValueEqual(collected(settler, jobBlock, List.of(collect, store), Items.MILK_BUCKET), 1, "No milk at Max");
                    helper.assertValueEqual(count(collect, Items.BUCKET), 16, "No bucket taken or used up");
                    cleanUp(helper, settler, forced);
                })
                .thenSucceed();
    }

    /** A Beekeeper with shears and 16 glass bottles in its collect inventory. */
    private static Settler bottlingBeekeeper(GameTestHelper helper, BlockPos jobBlock) {
        ((Container) helper.getLevel().getBlockEntity(jobBlock)).setItem(0, new ItemStack(Items.GLASS_BOTTLE, 16));
        Settler settler = BeekeeperGameTests.beekeeper(helper, jobBlock);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.SHEARS));
        return settler;
    }

    /** A hive that is not full offers no honey bottles: the entry is absent and the glass bottles stay unused. */
    private static void honeyNeedsFullHive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = BeekeeperGameTests.apiary(helper, 3, 0);
        Container collect = (Container) level.getBlockEntity(jobBlock);
        Settler settler = bottlingBeekeeper(helper, jobBlock);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FoundTabData.of(level, jobBlock).entry(BEEHIVE).isPresent(),
                        "Hive not found: " + FoundTabData.of(level, jobBlock)))
                .thenExecute(() -> {
                    helper.assertTrue(FoundTabData.of(level, jobBlock).entry(HONEY).isEmpty(), "Honey bottle offered without a full hive");
                    catchUpDay(level, settler);
                    helper.assertTrue(collected(settler, jobBlock, List.of(collect), Items.HONEYCOMB) > 0,
                            "No honeycomb (status " + settler.getWork().status(jobBlock) + ")");
                    helper.assertValueEqual(collected(settler, jobBlock, List.of(collect), Items.HONEY_BOTTLE), 0, "No honey bottles");
                    helper.assertValueEqual(count(collect, Items.GLASS_BOTTLE), 16, "No glass bottle taken or used up");
                    cleanUp(helper, settler, forced);
                })
                .thenSucceed();
    }

    /** A full hive offers honey bottles: the Beekeeper fills glass bottles it fetched from its collect inventory. */
    private static void honeyBottles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = BeekeeperGameTests.apiary(helper, BeehiveBlock.MAX_HONEY_LEVELS, 0);
        Container collect = (Container) level.getBlockEntity(jobBlock);
        Settler settler = bottlingBeekeeper(helper, jobBlock);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(belt(settler, Items.GLASS_BOTTLE) > 0,
                        "No glass bottles fetched (status " + settler.getWork().status(jobBlock) + ", tab " + FoundTabData.of(level, jobBlock) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(FoundTabData.of(level, jobBlock).entry(HONEY).isPresent(), "Honey bottle entry listed");
                    catchUpDay(level, settler);
                    int bottles = collected(settler, jobBlock, List.of(collect), Items.HONEY_BOTTLE);
                    helper.assertTrue(bottles > 0, "No honey bottles in a day");
                    helper.assertValueEqual(count(collect, Items.GLASS_BOTTLE) + belt(settler, Items.GLASS_BOTTLE) + bottles, 16,
                            "Each honey bottle used up a glass bottle");
                    helper.assertValueEqual(helper.getBlockState(new BlockPos(4, 1, 4)).getValue(BeehiveBlock.HONEY_LEVEL), BeehiveBlock.MAX_HONEY_LEVELS,
                            "Hive honey level unchanged");
                    cleanUp(helper, settler, forced);
                })
                .thenSucceed();
    }

    /** A block requirement with a state, for a test. */
    private static JobCraftingRecipe.Requirement requires(Block block, StatePropertiesPredicate.Builder state) {
        return new JobCraftingRecipe.Requirement(Optional.empty(), Optional.of(HolderSet.direct(block.builtInRegistryHolder())), state.build());
    }

    /**
     * A full hive counts for its own block only: with a full bee nest and a beehive that is not full, a full-beehive
     * requirement finds nothing, a full-bee-nest requirement finds the nest.
     */
    private static void fullHivePerBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BeekeeperGameTests.apiary(helper, 3, BeehiveBlock.MAX_HONEY_LEVELS);
        ResourceCollectionProfile profile = ModJobs.BEEKEEPER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        ScanResult scan = profile.createScan(helper.absolutePos(new BlockPos(4, 1, 4)), 10).runAll(level);
        StatePropertiesPredicate.Builder full = StatePropertiesPredicate.Builder.properties().hasProperty(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS);
        helper.assertValueEqual(profile.count(scan, requires(Blocks.BEEHIVE, full)), 0, "No full beehive");
        helper.assertValueEqual(profile.count(scan, requires(Blocks.BEE_NEST, full)), 1, "One full bee nest");
        helper.succeed();
    }

    /**
     * A builder Job's block requirement with a state matches the states its scan counted: age-3 berry bushes for an
     * age-3 requirement, nothing while only age-2 bushes were counted.
     */
    private static void blockStateRequirement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GathererGameTests.gathererBlock(helper);
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        JobCraftingRecipe.Requirement ripe = requires(Blocks.SWEET_BERRY_BUSH, StatePropertiesPredicate.Builder.properties().hasProperty(SweetBerryBushBlock.AGE, 3));
        BlockPos center = helper.absolutePos(new BlockPos(5, 1, 5));
        for (int x = 4; x < 7; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), GathererGameTests.bush(3));
        }
        helper.assertValueEqual(profile.count(profile.createScan(center, 4).runAll(level), ripe), 3, "Three age-3 bushes");
        for (int x = 4; x < 7; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), GathererGameTests.bush(2));
        }
        helper.assertValueEqual(profile.count(profile.createScan(center, 4).runAll(level), ripe), 0, "No age-3 bush");
        helper.succeed();
    }

    /** An offered recipe weighs its {@code weight} × the count of its {@code requires}: milk weighs 3 with three cows. */
    private static void weightByCount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AnimalFarmerGameTests.pen(helper);
        for (int x = 5; x < 8; x++) {
            AnimalFarmerGameTests.animal(helper, EntityTypes.COW, new BlockPos(x, 1, 6), false);
        }
        Job job = ModJobs.ANIMAL_FARMER.get();
        ResourceCollectionProfile profile = job.profile(ResourceCollectionProfile.class).orElseThrow();
        ScanResult scan = profile.createScan(helper.absolutePos(new BlockPos(4, 1, 4)), 10).runAll(level);
        Optional<CollectionRecipes.Offer> milk = CollectionRecipes.offered(level, job, profile, scan).stream()
                .filter(offer -> CollectionRecipes.type(offer.holder()).equals(MILK)).findFirst();
        helper.assertValueEqual(milk.map(CollectionRecipes.Offer::weight), Optional.of(3), "Milk weight with three cows");
        helper.succeed();
    }

    /**
     * An offered recipe keeps the Job running like a found type: with the cow excluded the Animal Farmer still fetches
     * buckets and makes milk, no cow loot; with the milk excluded too it pauses ("All resources excluded").
     */
    private static void keepsJobRunning(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        Container collect = (Container) level.getBlockEntity(jobBlock);
        Settler settler = milkingFarmer(helper, jobBlock, 16);
        Job job = ModJobs.ANIMAL_FARMER.get();
        SettlementWorld.toggleExcluded(level, jobBlock, job, COW);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(belt(settler, Items.BUCKET) > 0,
                        "No buckets fetched (status " + settler.getWork().status(jobBlock) + ")"))
                .thenExecute(() -> {
                    catchUpDay(level, settler);
                    helper.assertTrue(collected(settler, jobBlock, List.of(collect), Items.MILK_BUCKET) > 0,
                            "No milk with the cow excluded (status " + settler.getWork().status(jobBlock) + ")");
                    helper.assertValueEqual(collected(settler, jobBlock, List.of(collect), Items.BEEF, Items.LEATHER), 0, "No cow loot");
                    SettlementWorld.toggleExcluded(level, jobBlock, job, MILK);
                })
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.ALL_EXCLUDED,
                        "Cow and milk excluded: status " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> cleanUp(helper, settler, forced))
                .thenSucceed();
    }
}
