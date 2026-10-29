package com.gilfort.civmod.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.FarmCurve;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.block.ScarecrowBlock;
import com.gilfort.civmod.job.BlockCountScan;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Veggy Farmer and the Scarecrow (vault {@code Jobs/Veggy Farmer.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VeggyFarmerGameTests {

    private static final Identifier CARROTS = Identifier.withDefaultNamespace("carrots");
    private static final Identifier NETHER_WART = Identifier.withDefaultNamespace("nether_wart");
    private static final Identifier WHEAT = Identifier.withDefaultNamespace("wheat");
    private static final int SAMPLES = 2000;
    private static final double EPSILON = 1.0E-6;

    private VeggyFarmerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "veggy_farmer_only_mature_crops", VeggyFarmerGameTests::onlyMatureCrops);
            register(helper, "veggy_farmer_crop_curve", VeggyFarmerGameTests::cropCurve);
            register(helper, "veggy_farmer_no_scarecrow", VeggyFarmerGameTests::noScarecrow);
            register(helper, "veggy_farmer_works_scroll_scarecrow", VeggyFarmerGameTests::worksScrollScarecrow);
            register(helper, "veggy_farmer_fortune_hoe", VeggyFarmerGameTests::fortuneHoe);
            register(helper, "scarecrow_block", VeggyFarmerGameTests::scarecrowBlock);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * A grass floor (y 0) with farmland under the field (x 4–8, z 4–8); a level-2 Gatherer JobBlock at (1, 1, 1) with a
     * composter in slot 1.
     *
     * @return the JobBlock position (absolute)
     */
    private static BlockPos farm(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), x >= 4 && z >= 4 ? Blocks.FARMLAND : Blocks.GRASS_BLOCK);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.GATHERER_BLOCK.get());
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos jobBlock = helper.absolutePos(relative);
        TestAssignments.setStationType(SettlementData.get(helper.getLevel()), jobBlock, 1, Items.COMPOSTER);
        return jobBlock;
    }

    /** Places both halves of a scarecrow with its lower half at the relative position. */
    static void scarecrow(GameTestHelper helper, BlockPos relative) {
        BlockState lower = ModBlocks.SCARECROW.get().defaultBlockState();
        helper.setBlock(relative, lower);
        helper.setBlock(relative.above(), lower.setValue(ScarecrowBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static BlockState crop(Block block, int age) {
        return ((CropBlock) block).getStateForAge(age);
    }

    private static Settler veggyFarmer(GameTestHelper helper, BlockPos jobBlock) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 3), "Farmer");
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_HOE));
        TestAssignments.assign(data, jobBlock, settler.getUUID(), "Farmer", data.settlementAt(jobBlock).orElseThrow().id(), List.of(),
                ModJobs.VEGGY_FARMER.get());
        settler.addJob(new SettlerJob(ModJobs.VEGGY_FARMER.get(), jobBlock));
        return settler;
    }

    /**
     * Only mature crops of {@code #minecraft:crops} and mature nether wart count: young wheat, a grown melon stem (not a
     * crop) and a grown pitcher crop (two blocks, not a crop) do not. A field of young wheat gives nothing: the Veggy
     * Farmer reports "No resources".
     */
    private static void onlyMatureCrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = farm(helper);
        ResourceCollectionProfile profile = ModJobs.VEGGY_FARMER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        for (int x = 4; x < 7; x++) {
            helper.setBlock(new BlockPos(x, 1, 4), crop(Blocks.CARROTS, 7));
            helper.setBlock(new BlockPos(x, 1, 5), crop(Blocks.WHEAT, 3));
        }
        helper.setBlock(new BlockPos(4, 1, 6), Blocks.MELON_STEM.defaultBlockState().setValue(StemBlock.AGE, StemBlock.MAX_AGE));
        helper.setBlock(new BlockPos(8, 0, 8), Blocks.SOUL_SAND);
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE));
        helper.setBlock(new BlockPos(8, 1, 6), Blocks.NETHER_WART);
        BlockPos center = helper.absolutePos(new BlockPos(5, 1, 5));
        ScanResult mixed = profile.createScan(center, 10).runAll(level);
        helper.assertValueEqual(((BlockCountScan.Result) mixed).counts(), Map.of(CARROTS, 3, NETHER_WART, 1), "Mature crops only");

        for (int x = 4; x < 9; x++) {
            for (int z = 4; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 1, z), crop(Blocks.WHEAT, 3));
            }
        }
        helper.assertTrue(profile.createScan(center, 10).runAll(level).isEmpty(), "Young wheat is not counted");
        scarecrow(helper, new BlockPos(6, 1, 2));
        Settler settler = veggyFarmer(helper, jobBlock);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_RESOURCES, "Status " + settler.getWork().status(jobBlock));
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 0, "Nothing collected");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * The plant farm curve on real scans: one mature wheat is slow (0.2 × 1.005), 300 count only 100 at JobBlock level 1
     * (0.3) but all at level 2 (0.5); young wheat does not count. The values come from the Job's data.
     */
    private static void cropCurve(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        Job job = ModJobs.VEGGY_FARMER.get();
        helper.assertValueEqual(job.farmCurve(), Optional.of(new FarmCurve(0.005, List.of(100, 350, 750, 1350, 2000))), "Veggy Farmer curve");
        ResourceCollectionProfile profile = job.profile(ResourceCollectionProfile.class).orElseThrow();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 15; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND);
                helper.setBlock(new BlockPos(x, 1, z), crop(Blocks.WHEAT, 3));
            }
        }
        helper.setBlock(new BlockPos(10, 1, 10), crop(Blocks.WHEAT, 7));
        BlockPos center = helper.absolutePos(new BlockPos(10, 1, 10));
        assertNear(helper, job.productivity(profile.createScan(center, 30).runAll(level), 1), 0.201, "One mature wheat");
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 15; z++) {
                helper.setBlock(new BlockPos(x, 1, z), crop(Blocks.WHEAT, 7));
            }
        }
        ScanResult field = profile.createScan(center, 30).runAll(level);
        assertNear(helper, job.productivity(field, 1), 0.3, "300 mature wheat at level 1 (cap 100)");
        assertNear(helper, job.productivity(field, 2), 0.5, "300 mature wheat at level 2");
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }

    /** A field of mature wheat but no scarecrow: the Veggy Farmer is blocked with "No scarecrow" and collects nothing. */
    private static void noScarecrow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = farm(helper);
        for (int x = 4; x < 9; x++) {
            helper.setBlock(new BlockPos(x, 1, 6), crop(Blocks.WHEAT, 7));
        }
        Settler settler = veggyFarmer(helper, jobBlock);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.BLOCKED, "Status " + settler.getWork().status(jobBlock));
            helper.assertValueEqual(settler.getWork().statusText(jobBlock).getString(), "No scarecrow", "Reason");
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 0, "Nothing collected");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * Two scarecrows: one near the JobBlock without crops, one set with the Scroll of Assignment in a mature wheat field.
     * The Veggy Farmer works the scroll's field: it delivers wheat, the Found tab lists wheat in its section, the crops
     * stay mature; excluding wheat pauses it.
     */
    private static void worksScrollScarecrow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        for (int x = 0; x < 25; x++) {
            for (int z = 0; z < 25; z++) {
                boolean field = x >= 12 && x <= 16 && z >= 6 && z <= 10;
                helper.setBlock(new BlockPos(x, 0, z), field ? Blocks.FARMLAND : Blocks.GRASS_BLOCK);
                if (field && !(x == 14 && z == 8)) {
                    helper.setBlock(new BlockPos(x, 1, z), crop(Blocks.WHEAT, 7));
                }
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.GATHERER_BLOCK.get());
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos jobBlock = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        TestAssignments.setStationType(data, jobBlock, 1, Items.COMPOSTER);
        // More than 10 blocks (the scan radius at level 2) from the field.
        scarecrow(helper, new BlockPos(1, 1, 5));
        scarecrow(helper, new BlockPos(14, 1, 8));
        Job job = ModJobs.VEGGY_FARMER.get();
        BlockPos fieldScarecrow = helper.absolutePos(new BlockPos(14, 1, 8));
        helper.assertTrue(job.isValidWorkPlace(level, fieldScarecrow), "A scarecrow is a work place");
        helper.assertFalse(job.isValidWorkPlace(level, fieldScarecrow.above()), "Only its lower half");
        helper.assertValueEqual(JobAssignments.cyclePoint(data, jobBlock, fieldScarecrow, true, false), JobAssignments.PointResult.WORK, "Scroll");
        Settler settler = veggyFarmer(helper, jobBlock);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, helper.makeMockPlayer(GameType.CREATIVE), jobBlock);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().buffer(jobBlock).counts().getOrDefault(Items.WHEAT, 0) > 0,
                        "No wheat yet (status " + settler.getWork().status(jobBlock) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getBlockState(new BlockPos(13, 1, 7)).equals(crop(Blocks.WHEAT, 7)), "The wheat is never harvested");
                    List<Identifier> found = FoundTabData.of(level, jobBlock).section(job).orElseThrow().entries().stream()
                            .map(FoundTabData.Entry::type).toList();
                    helper.assertValueEqual(found, List.of(WHEAT), "Found tab");
                    helper.assertTrue(menu.tab(FoundTab.class).orElseThrow().toggleExcluded(job, WHEAT), "Wheat excluded");
                })
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.ALL_EXCLUDED,
                        "Not paused: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> {
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * Fortune on the hoe raises the carrot yield like for a player (vanilla: 2 + binomial(3 + level, 0.57)); an excluded
     * crop type leaves the roll.
     */
    private static void fortuneHoe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceCollectionProfile profile = ModJobs.VEGGY_FARMER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        BlockPos poi = helper.absolutePos(new BlockPos(1, 1, 1));
        ScanResult scan = new BlockCountScan.Result(Map.of(CARROTS, 5, WHEAT, 5));
        ItemStack fortune = new ItemStack(Items.DIAMOND_HOE);
        Holder<Enchantment> enchantment = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE);
        fortune.enchant(enchantment, 3);
        Map<Item, Integer> plain = roll(profile, settler, level, poi, scan, new ItemStack(Items.STONE_HOE));
        Map<Item, Integer> lucky = roll(profile, settler, level, poi, scan, fortune);
        helper.assertFalse(plain.containsKey(Items.WHEAT) || lucky.containsKey(Items.WHEAT), "Excluded wheat is never rolled: " + plain);
        double plainMean = plain.getOrDefault(Items.CARROT, 0) / (double) SAMPLES;
        double luckyMean = lucky.getOrDefault(Items.CARROT, 0) / (double) SAMPLES;
        helper.assertTrue(Math.abs(plainMean - 3.71) < 0.15, "About 3.7 carrots without Fortune: " + plainMean);
        helper.assertTrue(Math.abs(luckyMean - 5.43) < 0.2, "About 5.4 carrots with Fortune III: " + luckyMean);
        settler.discard();
        helper.succeed();
    }

    /** Rolls {@value #SAMPLES} times with wheat excluded; counts the items produced. */
    private static Map<Item, Integer> roll(ResourceCollectionProfile profile, Settler settler, ServerLevel level, BlockPos poi, ScanResult scan,
            ItemStack tool) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            Optional<ResourceCollectionProfile.Produced> produced = profile.produce(new ResourceCollectionProfile.Context(level, settler, scan, poi,
                    tool, Set.of(WHEAT), 1, Set.of(), type -> profile.droppedItems(level, poi, type, tool)));
            produced.ifPresent(found -> found.stacks().forEach(stack -> counts.merge(stack.getItem(), stack.getCount(), Integer::sum)));
        }
        return counts;
    }

    /**
     * The scarecrow stands on farmland and keeps it farmland; a half without its partner vanishes; it is crafted from a
     * carved pumpkin over a hay bale over a stick; a composter in the Gatherer JobBlock offers the Veggy Farmer.
     */
    private static void scarecrowBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.FARMLAND);
        scarecrow(helper, new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(5, 0, 2), Blocks.STONE);
        scarecrow(helper, new BlockPos(5, 1, 2));
        helper.setBlock(new BlockPos(5, 2, 2), Blocks.AIR);
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(5, 1, 2));
        Optional<RecipeHolder<?>> recipe = level.getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, CivMod.id("scarecrow")));
        helper.assertTrue(recipe.isPresent(), "Scarecrow recipe");
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        helper.setBlock(relative, ModBlocks.GATHERER_BLOCK.get());
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos pos = helper.absolutePos(relative);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.COMPOSTER), "Composter taken");
        List<Job> offered = JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList();
        helper.assertTrue(offered.contains(ModJobs.VEGGY_FARMER.get()), "Veggy Farmer offered: " + offered);
        helper.runAfterDelay(10, () -> {
            helper.assertBlockPresent(Blocks.FARMLAND, new BlockPos(2, 0, 2));
            helper.assertBlockPresent(ModBlocks.SCARECROW.get(), new BlockPos(2, 2, 2));
            helper.succeed();
        });
    }

    private static void assertNear(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < EPSILON, what + ": " + actual + " (expected " + expected + ")");
    }
}
