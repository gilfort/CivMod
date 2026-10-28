package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.FarmCurve;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StationTypesTab;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Plantation Farmer (vault {@code Jobs/Plantation Farmer.md}), the Station Types of the Gatherer
 * JobBlock and the farm curve of the plant Jobs (vault {@code Systems/Resource Collection Jobs.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PlantationFarmerGameTests {

    private static final double EPSILON = 1.0E-6;

    private PlantationFarmerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "plantation_bamboo_unlocks", PlantationFarmerGameTests::bambooUnlocks);
            register(helper, "plantation_farmer_delivers_with_axe", PlantationFarmerGameTests::deliversWithAxe);
            register(helper, "plantation_farmer_no_tool", PlantationFarmerGameTests::noTool);
            register(helper, "plant_farm_curve", PlantationFarmerGameTests::farmCurve);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static List<Job> offered(ServerLevel level, BlockPos pos) {
        return JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList();
    }

    /**
     * The Gatherer JobBlock shows the Station Types tab; slot 1 holds its sweet berries and is locked; bamboo in the open
     * slot of a level-2 JobBlock offers the Plantation Farmer.
     */
    private static void bambooUnlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        BlockPos pos = helper.absolutePos(relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, helper.makeMockPlayer(GameType.SURVIVAL), pos);
        helper.assertTrue(menu.tab(StationTypesTab.class).filter(StationTypesTab::shown).isPresent(), "Station Types tab shown");
        helper.assertValueEqual(offered(level, pos), List.of(ModJobs.GATHERER.get()), "Only the Gatherer before an unlock");
        helper.assertFalse(StationTypeGameTests.unlock(helper, pos, 0, Items.BAMBOO), "The sweet berry slot is locked");
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.BAMBOO), "Bamboo taken");
        helper.assertTrue(offered(level, pos).contains(ModJobs.PLANTATION_FARMER.get()), "Plantation Farmer offered: " + offered(level, pos));
        helper.succeed();
    }

    /**
     * A stone floor with a grass layer on top (y 1); sugar cane on sand beside an enclosed water strip; a level-2
     * Gatherer JobBlock at (1, 2, 1) with bamboo in slot 1.
     *
     * @return the JobBlock position (absolute)
     */
    private static BlockPos sugarCaneFarm(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.GRASS_BLOCK);
            }
        }
        for (int x = 5; x < 7; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), Blocks.SAND);
            helper.setBlock(new BlockPos(x, 1, 6), Blocks.WATER);
            helper.setBlock(new BlockPos(x, 2, 5), Blocks.SUGAR_CANE);
        }
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.SUGAR_CANE);
        BlockPos relative = new BlockPos(1, 2, 1);
        helper.setBlock(relative, ModBlocks.GATHERER_BLOCK.get());
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos jobBlock = helper.absolutePos(relative);
        TestAssignments.setStationType(SettlementData.get(helper.getLevel()), jobBlock, 1, Items.BAMBOO);
        return jobBlock;
    }

    private static Settler plantationFarmer(GameTestHelper helper, BlockPos jobBlock) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 2, 3), "Planter");
        TestAssignments.assign(data, jobBlock, settler.getUUID(), "Planter", data.settlementAt(jobBlock).orElseThrow().id(), List.of(),
                ModJobs.PLANTATION_FARMER.get());
        settler.addJob(new SettlerJob(ModJobs.PLANTATION_FARMER.get(), jobBlock));
        return settler;
    }

    /** A Plantation Farmer with an axe delivers sugar cane; the sugar cane stays where it grows. */
    private static void deliversWithAxe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = sugarCaneFarm(helper);
        Settler settler = plantationFarmer(helper, jobBlock);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).counts().getOrDefault(Items.SUGAR_CANE, 0) > 0,
                    "No sugar cane yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(helper.getBlockState(new BlockPos(5, 3, 5)).is(Blocks.SUGAR_CANE), "The sugar cane is never cut");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** Without an axe the Plantation Farmer reports "No tool". */
    private static void noTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = sugarCaneFarm(helper);
        Settler settler = plantationFarmer(helper, jobBlock);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_TOOL, "Status " + settler.getWork().status(jobBlock));
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 0, "Nothing collected");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * The farm curve on real scans: one pumpkin is slow (0.2 × 1.005), 300 pumpkins count only 100 at JobBlock level 1
     * (0.3) but all at level 2 (0.5); the values come from the Job's data.
     */
    private static void farmCurve(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        Job job = ModJobs.PLANTATION_FARMER.get();
        helper.assertValueEqual(job.farmCurve(), java.util.Optional.of(new FarmCurve(0.005, List.of(100, 350, 750, 1350, 2000))),
                "Plantation Farmer curve");
        helper.assertValueEqual(ModJobs.GATHERER.get().farmCurve(), job.farmCurve(), "Gatherer curve");
        ResourceCollectionProfile profile = job.profile(ResourceCollectionProfile.class).orElseThrow();
        BlockPos center = helper.absolutePos(new BlockPos(10, 1, 10));
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.PUMPKIN);
        ScanResult one = profile.createScan(center, 30).runAll(level);
        assertNear(helper, job.productivity(one, 1), 0.201, "One pumpkin");
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 15; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.PUMPKIN);
            }
        }
        ScanResult field = profile.createScan(center, 30).runAll(level);
        assertNear(helper, job.productivity(field, 1), 0.3, "300 pumpkins at level 1 (cap 100)");
        assertNear(helper, job.productivity(field, 2), 0.5, "300 pumpkins at level 2");
        assertNear(helper, job.productivity(field, 5), 0.5, "300 pumpkins at level 5");
        helper.assertTrue(job.farmCurve().orElseThrow().productivity(5000, 5) == FarmCurve.MAX, "At most twice the shared formula");
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }

    private static void assertNear(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < EPSILON, what + ": " + actual + " (expected " + expected + ")");
    }
}
