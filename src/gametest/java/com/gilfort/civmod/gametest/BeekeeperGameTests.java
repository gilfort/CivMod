package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.FarmCurve;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Beekeeper (vault {@code Jobs/Beekeeper.md}): honeycomb from the hives and nests with honey around
 * its work place, the honey level never changed, shears needed.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BeekeeperGameTests {

    private static final double EPSILON = 1.0E-6;
    private static final BlockPos HIVE = new BlockPos(4, 1, 4);
    private static final BlockPos NEST = new BlockPos(6, 1, 6);

    private BeekeeperGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "beekeeper_delivers_honeycomb", BeekeeperGameTests::deliversHoneycomb);
            register(helper, "beekeeper_needs_honey", BeekeeperGameTests::needsHoney);
            register(helper, "beekeeper_no_tool", BeekeeperGameTests::noTool);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    static BlockState hive(Block block, int honey) {
        return block.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, honey);
    }

    /** A level-2 Gatherer JobBlock with the beehive in slot 1, a beehive and a bee nest with the given honey levels. */
    static BlockPos apiary(GameTestHelper helper, int hiveHoney, int nestHoney) {
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos jobBlock = helper.absolutePos(relative);
        TestAssignments.setStationType(SettlementData.get(helper.getLevel()), jobBlock, 1, Items.BEEHIVE);
        helper.setBlock(HIVE, hive(Blocks.BEEHIVE, hiveHoney));
        helper.setBlock(NEST, hive(Blocks.BEE_NEST, nestHoney));
        return jobBlock;
    }

    static Settler beekeeper(GameTestHelper helper, BlockPos jobBlock) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Keeper");
        TestAssignments.assign(data, jobBlock, settler.getUUID(), "Keeper", data.settlementAt(jobBlock).orElseThrow().id(), List.of(),
                ModJobs.BEEKEEPER.get());
        settler.addJob(new SettlerJob(ModJobs.BEEKEEPER.get(), jobBlock));
        return settler;
    }

    private static int honey(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockState(pos).getValue(BeehiveBlock.HONEY_LEVEL);
    }

    /**
     * Only the hive with honey counts (farm curve {@code k} 0.2, cap 10 / 20 / 30 / 40 / 50); the Beekeeper delivers
     * honeycomb with shears and never changes a honey level.
     */
    private static void deliversHoneycomb(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = apiary(helper, 3, 0);
        Job job = ModJobs.BEEKEEPER.get();
        ResourceCollectionProfile profile = job.profile(ResourceCollectionProfile.class).orElseThrow();
        helper.assertValueEqual(job.farmCurve(), Optional.of(new FarmCurve(0.2, List.of(10, 20, 30, 40, 50))), "Beekeeper curve");
        ScanResult scan = profile.createScan(helper.absolutePos(HIVE), profile.scanRadius(2)).runAll(level);
        helper.assertValueEqual(scan.foundBlocks(), List.of(Identifier.withDefaultNamespace("beehive")), "Only the hive with honey");
        helper.assertValueEqual(profile.farmCount(scan), 1, "One hive counted");
        assertNear(helper, job.productivity(scan, 2), 0.24, "One hive");
        helper.assertValueEqual(profile.scanRadius(4), 20, "Radius 5 × JobBlock level");
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SavedScan saved = SavedScan.of(jobBlock, helper.absolutePos(HIVE), 10, 3, scan).orElseThrow(() -> new AssertionError("Hive scan is saved"));
        helper.assertValueEqual(SavedScan.CODEC.parse(ops, SavedScan.CODEC.encodeStart(ops, saved).getOrThrow()).getOrThrow().result(), scan, "Saved");
        Settler settler = beekeeper(helper, jobBlock);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.SHEARS));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).counts().getOrDefault(Items.HONEYCOMB, 0) > 0,
                    "No honeycomb yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertValueEqual(honey(helper, HIVE), 3, "Hive honey level unchanged");
            helper.assertValueEqual(honey(helper, NEST), 0, "Nest honey level unchanged");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** Hives without honey give nothing: "No resources". */
    private static void needsHoney(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = apiary(helper, 0, 0);
        Settler settler = beekeeper(helper, jobBlock);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.SHEARS));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_RESOURCES, "Status " + settler.getWork().status(jobBlock));
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 0, "Nothing collected");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** Without shears the Beekeeper reports "No tool". */
    private static void noTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = apiary(helper, 5, 5);
        Settler settler = beekeeper(helper, jobBlock);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_TOOL, "Status " + settler.getWork().status(jobBlock));
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 0, "Nothing collected");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    private static void assertNear(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < EPSILON, what + ": " + actual + " (expected " + expected + ")");
    }
}
