package com.gilfort.civmod.gametest;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.Fatigue;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.LumberjackOutput;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.job.ToolTiers;
import com.gilfort.civmod.job.WoodScan;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
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
    /** Chunks around a forced chunk that must be loaded before it ticks entities. */
    private static final int TICKING_BORDER = 2;

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
            register(helper, "lumberjack_scan_exact_in_sparse_areas", LumberjackGameTests::scanExactInSparseAreas);
            register(helper, "lumberjack_log_thresholds_config", LumberjackGameTests::logThresholdsConfig);
            register(helper, "lumberjack_too_few_logs_next_job", LumberjackGameTests::tooFewLogsNextJob);
            register(helper, "lumberjack_too_few_logs_catch_up", LumberjackGameTests::tooFewLogsCatchUp);
            register(helper, "lumberjack_excluded_logs", LumberjackGameTests::excludedLogs);
            register(helper, "lumberjack_scan_ignores_stripped", LumberjackGameTests::scanIgnoresStripped);
            register(helper, "lumberjack_works_in_world", LumberjackGameTests::worksInWorld);
            register(helper, "lumberjack_works_from_bed", LumberjackGameTests::worksFromBed);
            register(helper, "lumberjack_work_state_persistence", LumberjackGameTests::workStatePersistence);
            register(helper, "lumberjack_rescans_after_readd", LumberjackGameTests::rescansAfterReadd);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Map<Item, Integer> sample(WoodScan.Result scan, Identifier poi, boolean leaves, boolean silk, Map<LumberjackOutput.Kind, Integer> kinds) {
        return sample(scan, poi, leaves, silk, Set.of(), kinds);
    }

    /** Counts produced items by item (an empty roll counts as air) and by kind. */
    private static Map<Item, Integer> sample(WoodScan.Result scan, Identifier poi, boolean leaves, boolean silk, Set<Identifier> excluded,
            Map<LumberjackOutput.Kind, Integer> kinds) {
        RandomSource random = RandomSource.create(7L);
        Map<Item, Integer> items = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            LumberjackOutput.Produced produced = LumberjackOutput.produce(scan, poi, leaves, silk, excluded, random).orElseThrow();
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
        helper.assertTrue(near(LumberjackOutput.productivity(19), 0.95, 1e-9), "19 logs → 95 %");
        helper.assertTrue(near(LumberjackOutput.productivity(10), 0.5, 1e-9), "10 logs → 50 %");
        helper.assertTrue(near(LumberjackOutput.productivity(4), 0.2, 1e-9), "4 logs → 20 %");
        helper.assertTrue(LumberjackOutput.productivity(3) == 0.0, "3 logs → no production");
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
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Tester", settlement.id(), java.util.List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
        if (onBed) {
            helper.runAtTickTime(300, () -> helper.assertTrue(settler.position().distanceTo(spawn) > 2.0,
                    "Still on the bed after 15 s (status " + settler.getWork().status(jobBlock) + ")"));
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() > 0,
                    "No wood produced yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(settler.getSkills().get(ModJobs.LUMBERJACK.get()).exp() > 0 || settler.getSkills().get(ModJobs.LUMBERJACK.get()).level() > 1,
                    "Producing gives EXP");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * A job removed and assigned again at the same JobBlock (same radius, same day) gets a fresh scan: removing it must
     * not leave the old scan key behind, or the new job never scans and never produces.
     */
    private static void rescansAfterReadd(GameTestHelper helper) {
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
        List<long[]> forced = forceChunks(helper);
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Tester"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(4, 1, 1)), true, false);
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Tester", settlement.id(), java.util.List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        SettlerJob job = new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock);
        settler.addJob(job);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(foundNothing(settler, job),
                        "First job never scanned (status " + settler.getWork().status(jobBlock) + ")"))
                .thenExecute(() -> {
                    settler.removeJob(jobBlock);
                    helper.assertTrue(foundNothing(settler, job), "Removed job still has a scan");
                    settler.addJob(job);
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(foundNothing(settler, job),
                            "Re-added job never scanned (status " + settler.getWork().status(jobBlock) + ")");
                    helper.assertTrue(settler.getWork().buffer(jobBlock).count() > 0,
                            "Re-added job produced no wood (status " + settler.getWork().status(jobBlock) + ")");
                })
                .thenExecute(() -> {
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * @param settler a settler
     * @param job     one of its jobs
     * @return whether that job has no scan yet or its scan found nothing
     */
    static boolean foundNothing(Settler settler, SettlerJob job) {
        return settler.getWork().scan(job.jobBlock()).map(ScanResult::isEmpty).orElse(true);
    }

    static List<long[]> forceChunks(GameTestHelper helper) {
        return forceChunks(helper, new BlockPos(8, 0, 8));
    }

    /**
     * Forces the chunks from the test origin up to a relative corner and loads them with their neighbours right away.
     * <p>
     * Entities only tick in entity-ticking chunks, which need their neighbours (up to {@value #TICKING_BORDER} chunks)
     * generated. The GameTest server does not wait between ticks, so asynchronous generation at the random far-away test
     * position costs a varying number of ticks: settlers stood still without a single decision for over 2000 ticks and
     * timed tests failed now and then. Loading synchronously makes the start independent of the machine's speed.
     */
    static List<long[]> forceChunks(GameTestHelper helper, BlockPos corner) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        BlockPos max = helper.absolutePos(corner);
        int minX = SectionPos.blockToSectionCoord(Math.min(min.getX(), max.getX()));
        int maxX = SectionPos.blockToSectionCoord(Math.max(min.getX(), max.getX()));
        int minZ = SectionPos.blockToSectionCoord(Math.min(min.getZ(), max.getZ()));
        int maxZ = SectionPos.blockToSectionCoord(Math.max(min.getZ(), max.getZ()));
        List<long[]> chunks = new java.util.ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (level.setChunkForced(x, z, true)) {
                    chunks.add(new long[] {x, z});
                }
            }
        }
        for (int x = minX - TICKING_BORDER; x <= maxX + TICKING_BORDER; x++) {
            for (int z = minZ - TICKING_BORDER; z <= maxZ + TICKING_BORDER; z++) {
                level.getChunk(x, z);
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

    /**
     * An excluded wood type is never produced and the log share goes to the other types; with every log type excluded
     * the log rolls are empty and the rare drops keep their share.
     */
    private static void excludedLogs(GameTestHelper helper) {
        WoodScan.Result scan = new WoodScan.Result(Map.of(OAK_LOG, 60, BIRCH_LOG, 40), Map.of(OAK_LEAVES, 30, BIRCH_LEAVES, 30));
        Map<LumberjackOutput.Kind, Integer> kinds = new EnumMap<>(LumberjackOutput.Kind.class);
        Map<Item, Integer> items = sample(scan, BIRCH_LOG, false, false, Set.of(OAK_LOG), kinds);
        helper.assertTrue(items.getOrDefault(Items.OAK_LOG, 0) == 0, "Excluded oak is never produced");
        double birch = items.getOrDefault(Items.BIRCH_LOG, 0) / (double) SAMPLES;
        helper.assertTrue(near(birch, 0.9, 0.015), "Birch takes the whole log share (~90 %): " + birch);

        kinds.clear();
        items = sample(scan, BIRCH_LOG, false, false, Set.of(OAK_LOG, BIRCH_LOG), kinds);
        double empty = items.getOrDefault(Items.AIR, 0) / (double) SAMPLES;
        helper.assertTrue(near(empty, 0.9, 0.015), "All logs excluded: the log rolls are empty (~90 %): " + empty);
        helper.assertTrue(items.getOrDefault(Items.OAK_LOG, 0) + items.getOrDefault(Items.BIRCH_LOG, 0) == 0, "No logs at all");
        double rare = (SAMPLES - kinds.getOrDefault(LumberjackOutput.Kind.LOG, 0)) / (double) SAMPLES;
        helper.assertTrue(near(rare, 0.1, 0.015), "Rare drops keep their 10 %: " + rare);
        helper.succeed();
    }

    /** Stripped logs and stripped wood (buildings) are not counted; wood blocks still are. */
    private static void scanIgnoresStripped(GameTestHelper helper) {
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STRIPPED_OAK_LOG);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STRIPPED_SPRUCE_WOOD);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.OAK_WOOD);
            }
        }
        WoodScan.Result result = new WoodScan(helper.absolutePos(new BlockPos(1, 2, 1)), 10).runAll(helper.getLevel());
        helper.assertTrue(result.logs().keySet().equals(Set.of(Identifier.withDefaultNamespace("oak_wood"))),
                "Only the oak wood counts: " + result.logs());
        helper.succeed();
    }

    /**
     * A lone tree is counted exactly wherever it stands on the sampling grid: 3 logs are 3 at all four column positions,
     * not 0 or 12.
     */
    private static void scanExactInSparseAreas(GameTestHelper helper) {
        assertColumnsCountedExactly(helper);
        int full = CivModConfig.LUMBERJACK_FULL_LOGS.get();
        try {
            // A minimum above the full amount still needs real logs: 12 estimated logs must not pass as 3 real ones.
            CivModConfig.LUMBERJACK_FULL_LOGS.set(1);
            assertColumnsCountedExactly(helper);
        } finally {
            CivModConfig.LUMBERJACK_FULL_LOGS.set(full);
        }
        helper.succeed();
    }

    private static void assertColumnsCountedExactly(GameTestHelper helper) {
        BlockPos center = helper.absolutePos(new BlockPos(1, 2, 1));
        for (int x = 3; x <= 4; x++) {
            for (int z = 3; z <= 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
                WoodScan.Result result = new WoodScan(center, 10).runAll(helper.getLevel());
                helper.assertTrue(result.totalLogs() == 3, "Expected 3 logs at column " + x + "/" + z + ": " + result.logs());
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    /** Changed config values apply at once, to the speed and to what blocks the job. */
    private static void logThresholdsConfig(GameTestHelper helper) {
        int minimum = CivModConfig.LUMBERJACK_MINIMUM_LOGS.get();
        int full = CivModConfig.LUMBERJACK_FULL_LOGS.get();
        ResourceCollectionProfile profile = ModJobs.LUMBERJACK.get().profile(ResourceCollectionProfile.class).orElseThrow();
        try {
            CivModConfig.LUMBERJACK_MINIMUM_LOGS.set(2);
            CivModConfig.LUMBERJACK_FULL_LOGS.set(8);
            helper.assertTrue(near(LumberjackOutput.productivity(2), 0.25, 1e-9), "2 of 8 logs → 25 %");
            helper.assertTrue(near(LumberjackOutput.productivity(8), 1.0, 1e-9), "8 of 8 logs → 100 %");
            helper.assertTrue(LumberjackOutput.productivity(1) == 0.0, "Below the minimum of 2 → no production");
            helper.assertTrue(profile.hasResources(new WoodScan.Result(Map.of(OAK_LOG, 2), Map.of())), "2 logs reach the minimum of 2");
            helper.assertFalse(profile.hasResources(new WoodScan.Result(Map.of(OAK_LOG, 1), Map.of())), "1 log is below the minimum of 2");
        } finally {
            CivModConfig.LUMBERJACK_MINIMUM_LOGS.set(minimum);
            CivModConfig.LUMBERJACK_FULL_LOGS.set(full);
        }
        helper.succeed();
    }

    /**
     * A settler with a Lumberjack job at a 3 log tree and a Miner job as its next priority.
     *
     * @return the settler; its first job is the Lumberjack, its second the Miner
     */
    private static Settler tooFewLogs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(new BlockPos(1, 1, 5), ModBlocks.MINER_BLOCK.get());
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.GRANITE);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(7, y, 1), Blocks.OAK_LOG);
        }
        BlockPos lumberjack = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos miner = helper.absolutePos(new BlockPos(1, 1, 5));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(lumberjack).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Tester"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        settler.getInventory().setItem(Settler.BELT_START + 1, new ItemStack(Items.NETHERITE_PICKAXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        JobAssignments.cyclePoint(data, lumberjack, helper.absolutePos(new BlockPos(7, 1, 1)), true, false);
        JobAssignments.cyclePoint(data, miner, helper.absolutePos(new BlockPos(5, 1, 5)), true, false);
        helper.assertTrue(TestAssignments.assign(data, lumberjack, settler.getUUID(), "Tester", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Lumberjack assignment failed");
        helper.assertTrue(TestAssignments.assign(data, miner, settler.getUUID(), "Tester", settlement.id(), List.of(ModJobs.LUMBERJACK.get()))
                == JobAssignments.AssignResult.OK, "Miner assignment failed");
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), lumberjack));
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), miner));
        return settler;
    }

    /** 3 logs: "No resources", the Found tab lists the logs all the same, and the settler produces at its next job. */
    private static void tooFewLogsNextJob(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceChunks(helper);
        Settler settler = tooFewLogs(helper);
        BlockPos lumberjack = settler.getJobs().get(0).jobBlock();
        BlockPos miner = settler.getJobs().get(1).jobBlock();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(lumberjack) == JobStatus.NO_RESOURCES,
                        "Not blocked: " + settler.getWork().status(lumberjack)))
                .thenExecute(() -> helper.assertTrue(FoundTabData.of(level, lumberjack).entry(OAK_LOG).isPresent(),
                        "The tab lists the logs: " + FoundTabData.of(level, lumberjack)))
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().buffer(miner).count() > 0,
                        "Next job never worked (status " + settler.getWork().status(miner) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(settler.getWork().buffer(lumberjack).count() == 0, "Nothing cut from 3 logs");
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** Catch-up follows the same rules: a caught-up day at 3 logs cuts nothing and goes to the next job. */
    private static void tooFewLogsCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceChunks(helper);
        Settler settler = tooFewLogs(helper);
        BlockPos lumberjack = settler.getJobs().get(0).jobBlock();
        BlockPos miner = settler.getJobs().get(1).jobBlock();
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || settler.getWork().scan(lumberjack).isEmpty() || settler.getWork().scan(miner).isEmpty()) {
                return;
            }
            done[0] = true;
            Container minerStorage = (Container) level.getBlockEntity(miner);
            int before = stored(minerStorage) + settler.getWork().buffer(miner).count();
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - 24000L, clock);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(stored((Container) level.getBlockEntity(lumberjack)) + settler.getWork().buffer(lumberjack).count() == 0,
                    "Wood cut from 3 logs in catch-up");
            int mined = stored(minerStorage) + settler.getWork().buffer(miner).count() - before;
            helper.assertTrue(mined > 0, "The next job took over the day");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    private static int stored(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    /** The sampled scan estimates a dense 4×4×6 block of logs and a 4×4 layer of leaves on its 2×2 grid. */
    private static void sampledScan(GameTestHelper helper) {
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 1; y <= 6; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
                helper.setBlock(new BlockPos(x, 7, z), Blocks.OAK_LEAVES);
            }
        }
        WoodScan.Result result = new WoodScan(helper.absolutePos(new BlockPos(1, 2, 1)), 10).runAll(helper.getLevel());
        helper.assertTrue(result.logs().getOrDefault(OAK_LOG, 0) == 96, "Expected 96 sampled logs: " + result.logs());
        helper.assertTrue(result.leaves().getOrDefault(OAK_LEAVES, 0) == 16, "Expected 16 sampled leaves: " + result.leaves());
        helper.succeed();
    }
}
