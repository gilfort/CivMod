package com.gilfort.civmod.gametest;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.Fatigue;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.LumberjackOutput;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.job.ToolTiers;
import com.gilfort.civmod.job.WoodScan;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Story 5 (Lumberjack and the job framework).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class LumberjackGameTests {

    private static final Identifier OAK_LOG = Identifier.withDefaultNamespace("oak_log");
    private static final Identifier BIRCH_LOG = Identifier.withDefaultNamespace("birch_log");
    private static final Identifier OAK_LEAVES = Identifier.withDefaultNamespace("oak_leaves");
    private static final Identifier BIRCH_LEAVES = Identifier.withDefaultNamespace("birch_leaves");
    private static final int SAMPLES = 20000;

    private LumberjackGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "lumberjack_output_distribution", LumberjackGameTests::outputDistribution);
            register(helper, "lumberjack_rare_variants", LumberjackGameTests::rareVariants);
            register(helper, "lumberjack_speed_and_threshold", LumberjackGameTests::speedAndThreshold);
            register(helper, "lumberjack_tool_tiers", LumberjackGameTests::toolTiers);
            register(helper, "lumberjack_buffer_delivery", LumberjackGameTests::bufferDelivery);
            register(helper, "lumberjack_fatigue", LumberjackGameTests::fatigue);
            register(helper, "lumberjack_sampled_scan", LumberjackGameTests::sampledScan);
            register(helper, "lumberjack_works_in_world", LumberjackGameTests::worksInWorld);
            register(helper, "lumberjack_works_from_bed", LumberjackGameTests::worksFromBed);
            register(helper, "lumberjack_work_state_persistence", LumberjackGameTests::workStatePersistence);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Map<Item, Integer> sample(WoodScan.Result scan, Identifier poi, boolean leaves, boolean silk, Map<LumberjackOutput.Kind, Integer> kinds) {
        RandomSource random = RandomSource.create(7L);
        Map<Item, Integer> items = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            LumberjackOutput.Produced produced = LumberjackOutput.produce(scan, poi, leaves, silk, random).orElseThrow();
            items.merge(produced.stack().getItem(), 1, Integer::sum);
            kinds.merge(produced.kind(), 1, Integer::sum);
        }
        return items;
    }

    private static boolean near(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    /** 90 % logs, distributed by wood type with ×2 for the work place of the day. */
    private static void outputDistribution(GameTestHelper helper) {
        WoodScan.Result scan = new WoodScan.Result(Map.of(OAK_LOG, 60, BIRCH_LOG, 40), Map.of(OAK_LEAVES, 30, BIRCH_LEAVES, 30));
        Map<LumberjackOutput.Kind, Integer> kinds = new EnumMap<>(LumberjackOutput.Kind.class);
        Map<Item, Integer> items = sample(scan, BIRCH_LOG, false, false, kinds);
        double logShare = kinds.getOrDefault(LumberjackOutput.Kind.LOG, 0) / (double) SAMPLES;
        helper.assertTrue(near(logShare, 0.9, 0.015), "Log share should be ~90 %: " + logShare);
        double birchShare = items.getOrDefault(Items.BIRCH_LOG, 0) / (double) kinds.get(LumberjackOutput.Kind.LOG);
        helper.assertTrue(near(birchShare, 80.0 / 140.0, 0.02), "Birch (POI ×2) should be ~57 % of logs: " + birchShare);
        helper.assertTrue(items.getOrDefault(Items.OAK_LEAVES, 0) == 0, "No leaves without the toggle");
        helper.succeed();
    }

    /** Rare split: apples only with oak leaves, leaves with toggle, Silk Touch → only leaves. */
    private static void rareVariants(GameTestHelper helper) {
        WoodScan.Result oak = new WoodScan.Result(Map.of(OAK_LOG, 40), Map.of(OAK_LEAVES, 40));
        Map<LumberjackOutput.Kind, Integer> kinds = new EnumMap<>(LumberjackOutput.Kind.class);
        Map<Item, Integer> items = sample(oak, OAK_LOG, false, false, kinds);
        int rare = SAMPLES - kinds.get(LumberjackOutput.Kind.LOG);
        helper.assertTrue(near(kinds.getOrDefault(LumberjackOutput.Kind.STICK, 0) / (double) rare, 0.55, 0.04), "Sticks ~55 % of rare");
        helper.assertTrue(near(kinds.getOrDefault(LumberjackOutput.Kind.APPLE, 0) / (double) rare, 0.10, 0.03), "Apples ~10 % of rare");
        helper.assertTrue(items.getOrDefault(Items.OAK_SAPLING, 0) > 0, "Oak saplings from oak leaves");

        WoodScan.Result birch = new WoodScan.Result(Map.of(BIRCH_LOG, 40), Map.of(BIRCH_LEAVES, 40));
        kinds.clear();
        items = sample(birch, BIRCH_LOG, true, false, kinds);
        helper.assertTrue(kinds.getOrDefault(LumberjackOutput.Kind.APPLE, 0) == 0, "No apples without oak leaves");
        rare = SAMPLES - kinds.get(LumberjackOutput.Kind.LOG);
        helper.assertTrue(near(kinds.getOrDefault(LumberjackOutput.Kind.LEAVES, 0) / (double) rare, 25.0 / 90.0, 0.04), "Leaves share with toggle");
        helper.assertTrue(items.getOrDefault(Items.BIRCH_LEAVES, 0) > 0 && items.getOrDefault(Items.BIRCH_SAPLING, 0) > 0, "Birch leaves and saplings");

        kinds.clear();
        sample(birch, BIRCH_LOG, false, true, kinds);
        rare = SAMPLES - kinds.get(LumberjackOutput.Kind.LOG);
        helper.assertTrue(kinds.getOrDefault(LumberjackOutput.Kind.LEAVES, 0) == rare, "Silk Touch: all rare drops are leaves");
        helper.assertTrue(LumberjackOutput.sapling(Identifier.withDefaultNamespace("mangrove_leaves")).orElse(null) == Items.MANGROVE_PROPAGULE,
                "Mangrove leaves → propagule");
        helper.succeed();
    }

    /** Speed formula anchored at level 20, threshold malus, tool tier multipliers. */
    private static void speedAndThreshold(GameTestHelper helper) {
        helper.assertTrue(near(LumberjackOutput.speed(20), 0.25, 1e-9), "Level 20 = 0.25/s");
        helper.assertTrue(near(LumberjackOutput.speed(1), 0.160, 0.002), "Level 1 ≈ 0.160/s");
        helper.assertTrue(near(LumberjackOutput.speed(100), 1.682, 0.002), "Level 100 ≈ 1.682/s");
        helper.assertTrue(near(LumberjackOutput.productivity(20), 1.0, 1e-9) && near(LumberjackOutput.productivity(40), 1.0, 1e-9),
                "Full productivity from 20 logs");
        helper.assertTrue(near(LumberjackOutput.productivity(15), 0.75, 1e-9), "15 logs → −25 %");
        helper.assertTrue(LumberjackOutput.productivity(0) == 0.0, "No logs → no production");
        helper.assertTrue(ToolTiers.multiplier(0) == 0.5 && ToolTiers.multiplier(1) == 1.0 && ToolTiers.multiplier(2) == 1.2
                && ToolTiers.multiplier(3) == 1.5 && ToolTiers.multiplier(4) == 2.0, "Tier multipliers");
        helper.succeed();
    }

    /** Tool tiers come from the mining level of the tool component. */
    private static void toolTiers(GameTestHelper helper) {
        Map<Item, Integer> expected = Map.of(Items.WOODEN_AXE, 0, Items.GOLDEN_AXE, 0, Items.STONE_AXE, 1, Items.COPPER_AXE, 1,
                Items.IRON_AXE, 2, Items.DIAMOND_AXE, 3, Items.NETHERITE_AXE, 4, Items.STICK, 1);
        expected.forEach((item, tier) -> helper.assertTrue(ToolTiers.tier(new ItemStack(item)) == tier,
                item + " should be tier " + tier + " but is " + ToolTiers.tier(new ItemStack(item))));
        helper.succeed();
    }

    /** The buffer merges stacks, reports full and keeps what the drop-off does not accept. */
    private static void bufferDelivery(GameTestHelper helper) {
        JobBuffer buffer = new JobBuffer();
        for (int i = 0; i < 300; i++) {
            buffer.add(new ItemStack(Items.OAK_LOG));
        }
        helper.assertTrue(buffer.count() == 300 && buffer.stacks().size() == 5, "300 logs in 5 stacks");
        helper.assertTrue(buffer.isFull() && buffer.shouldDeliver(), "Full and due for delivery");
        int[] room = {100};
        boolean all = buffer.deliver(stack -> {
            int accepted = Math.min(room[0], stack.getCount());
            room[0] -= accepted;
            return accepted;
        });
        helper.assertFalse(all, "Drop-off with room for 100 cannot take everything");
        helper.assertTrue(buffer.count() == 200 && !buffer.isFull(), "200 logs left in the buffer");
        helper.assertTrue(buffer.deliver(ItemStack::getCount) && buffer.isEmpty(), "A drop-off with room takes the rest");
        helper.succeed();
    }

    /** A break comes at the latest after 7 minutes; afterwards 5 minutes stay break-free. */
    private static void fatigue(GameTestHelper helper) {
        Fatigue fatigue = new Fatigue();
        RandomSource random = RandomSource.create(3L);
        int ticks = 0;
        while (!fatigue.advance(100, random)) {
            ticks += 100;
            helper.assertTrue(ticks <= 7 * 1200, "No break within 7 minutes although the chance reached 100 %");
        }
        while (fatigue.advance(100, random)) {
            // break running
        }
        for (int i = 0; i < 5 * 12 - 1; i++) {
            helper.assertFalse(fatigue.advance(100, random), "Break during the 5 break-free minutes");
        }
        helper.succeed();
    }

    /** A settler with a Lumberjack job and a stone axe walks to the trees and produces wood into its buffer. */
    private static void worksInWorld(GameTestHelper helper) {
        worksInWorld(helper, false);
    }

    /** Starting on top of a bed (beds are blocked for the pathfinder), the Lumberjack still reaches the tree in time. */
    private static void worksFromBed(GameTestHelper helper) {
        worksInWorld(helper, true);
    }

    private static void worksInWorld(GameTestHelper helper, boolean onBed) {
        // Own floor: test places are spread over the world and the terrain below them varies.
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos jobBlockRelative = new BlockPos(1, 1, 1);
        helper.setBlock(jobBlockRelative, ModBlocks.LUMBERJACK_BLOCK.get());
        for (int x = 4; x < 8; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
            }
        }
        ServerLevel level = helper.getLevel();
        // The test area is not always entity-ticking (the settler then never ticks): keep its chunks forced.
        List<long[]> forced = forceChunks(helper);
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        if (onBed) {
            // Far from the tree, on top of a bed.
            net.minecraft.world.level.block.state.BlockState bed = Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState()
                    .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH);
            helper.setBlock(new BlockPos(1, 1, 7), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
            helper.setBlock(new BlockPos(1, 1, 6), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
            start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 6))).add(0.0, 0.5625, 0.0);
        }
        Vec3 spawn = start;
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Tester"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(4, 1, 1)), true, false);
        helper.assertTrue(JobAssignments.assign(data, jobBlock, settler.getUUID(), "Tester", settlement.id(), java.util.List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(JobType.LUMBERJACK, jobBlock));
        if (onBed) {
            helper.runAtTickTime(300, () -> helper.assertTrue(settler.position().distanceTo(spawn) > 2.0,
                    "Still on the bed after 15 s (status " + settler.getWork().status(jobBlock) + ")"));
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() > 0,
                    "No wood produced yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(settler.getSkills().get(JobType.LUMBERJACK).exp() > 0 || settler.getSkills().get(JobType.LUMBERJACK).level() > 1,
                    "Producing gives EXP");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    static List<long[]> forceChunks(GameTestHelper helper) {
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        BlockPos max = helper.absolutePos(new BlockPos(8, 0, 8));
        List<long[]> chunks = new java.util.ArrayList<>();
        for (int x = SectionPos.blockToSectionCoord(Math.min(min.getX(), max.getX())); x <= SectionPos.blockToSectionCoord(Math.max(min.getX(), max.getX())); x++) {
            for (int z = SectionPos.blockToSectionCoord(Math.min(min.getZ(), max.getZ())); z <= SectionPos.blockToSectionCoord(Math.max(min.getZ(), max.getZ())); z++) {
                if (helper.getLevel().setChunkForced(x, z, true)) {
                    chunks.add(new long[] {x, z});
                }
            }
        }
        return chunks;
    }

    /** Buffers and the work place of the day survive saving; the earlier save format still loads. */
    private static void workStatePersistence(GameTestHelper helper) {
        JobBuffer buffer = new JobBuffer();
        buffer.add(new ItemStack(Items.OAK_LOG, 12));
        BlockPos jobBlock = new BlockPos(1, 2, 3);
        SettlerWork.BuffersSnapshot snapshot = new SettlerWork.BuffersSnapshot(java.util.List.of(new SettlerWork.BufferEntry(jobBlock, buffer)),
                java.util.List.of(new SettlerWork.WorkPlaceEntry(jobBlock, new BlockPos(4, 5, 6), 7L)));
        var ops = helper.getLevel().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var json = SettlerWork.BuffersSnapshot.CODEC.encodeStart(ops, snapshot).getOrThrow();
        SettlerWork.BuffersSnapshot loaded = SettlerWork.BuffersSnapshot.CODEC.parse(ops, json).getOrThrow();
        helper.assertTrue(loaded.entries().get(0).buffer().count() == 12, "Buffer lost");
        helper.assertTrue(loaded.workPlaces().get(0).poi().equals(new BlockPos(4, 5, 6)) && loaded.workPlaces().get(0).day() == 7L,
                "Work place of the day lost");
        var legacy = SettlerWork.BuffersSnapshot.CODEC.encodeStart(ops, snapshot).getOrThrow().getAsJsonObject().get("buffers");
        SettlerWork.BuffersSnapshot fromLegacy = SettlerWork.BuffersSnapshot.CODEC.parse(ops, legacy).getOrThrow();
        helper.assertTrue(fromLegacy.entries().get(0).buffer().count() == 12, "Earlier save format (plain buffer list) not readable");
        helper.succeed();
    }

    /** The sampled scan counts a 4×4×3 block of logs and a 4×4 layer of leaves exactly. */
    private static void sampledScan(GameTestHelper helper) {
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
                helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_LEAVES);
            }
        }
        WoodScan.Result result = new WoodScan(helper.absolutePos(new BlockPos(1, 2, 1)), 10).runAll(helper.getLevel());
        helper.assertTrue(result.logs().getOrDefault(OAK_LOG, 0) == 48, "Expected 48 sampled logs: " + result.logs());
        helper.assertTrue(result.leaves().getOrDefault(OAK_LEAVES, 0) == 16, "Expected 16 sampled leaves: " + result.leaves());
        helper.succeed();
    }
}
