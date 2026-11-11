package com.gilfort.civmod.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.job.BlockSearch;
import com.gilfort.civmod.job.MineScan;
import com.gilfort.civmod.job.MinerOutput;
import com.gilfort.civmod.job.MinerWeights;
import com.gilfort.civmod.job.SavedScan;
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
import com.gilfort.civmod.settlement.TestSettlements;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Miner story.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MinerGameTests {

    private static final Identifier STONE = Identifier.withDefaultNamespace("stone");
    private static final Identifier GRANITE = Identifier.withDefaultNamespace("granite");
    private static final Identifier IRON_ORE = Identifier.withDefaultNamespace("iron_ore");
    private static final Identifier DIAMOND_ORE = Identifier.withDefaultNamespace("diamond_ore");
    private static final Identifier EMPTY_ROLL = CivMod.id("empty_roll");
    private static final int SAMPLES = 20000;

    private MinerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "miner_roll", MinerGameTests::roll);
            register(helper, "miner_excluded_roll", MinerGameTests::excludedRoll);
            register(helper, "miner_roll_counts", MinerGameTests::rollWeightsUseCounts);
            register(helper, "miner_found_chance", MinerGameTests::foundTabShowsChance);
            register(helper, "miner_weights_merge", MinerGameTests::weightsMerge);
            register(helper, "miner_palette_scan", MinerGameTests::paletteScan);
            register(helper, "miner_scan_edge_section", MinerGameTests::edgeSectionCountsWhole);
            register(helper, "miner_scan_codec", MinerGameTests::scanCodec);
            register(helper, "miner_exposed_work_place", MinerGameTests::exposedWorkPlace);
            register(helper, "miner_works_in_world", MinerGameTests::worksInWorld);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Map<Identifier, Integer> sample(MineScan.Result scan, Identifier poi, java.util.function.Predicate<Identifier> harvestable,
            int level, MinerWeights weights) {
        return sample(scan, poi, harvestable, Set.of(), level, weights);
    }

    /** Counts rolled types; a missing group counts as air, an empty roll of a fully excluded group as {@link #EMPTY_ROLL}. */
    private static Map<Identifier, Integer> sample(MineScan.Result scan, Identifier poi, java.util.function.Predicate<Identifier> harvestable,
            Set<Identifier> excluded, int level, MinerWeights weights) {
        RandomSource random = RandomSource.create(11L);
        Map<Identifier, Integer> counts = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            MinerOutput.Roll roll = MinerOutput.roll(scan, poi, harvestable, excluded, level, weights, 1.0, random);
            Identifier type = roll.type().orElse(roll.emptyRoll() ? EMPTY_ROLL : Identifier.withDefaultNamespace("air"));
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Excluded types are never rolled and their weight spreads over the rest of the group; a fully excluded stone group
     * gives empty rolls at the stone share instead of more ores.
     */
    private static void excludedRoll(GameTestHelper helper) {
        MinerWeights weights = new MinerWeights(99.7, -0.34, Map.of(STONE, 100.0, GRANITE, 100.0), Map.of(IRON_ORE, 70.0, DIAMOND_ORE, 30.0), 10, 10);
        MineScan.Result scan = new MineScan.Result(Map.of(STONE, 1, GRANITE, 1), Map.of(IRON_ORE, 1, DIAMOND_ORE, 1));

        Map<Identifier, Integer> noGranite = sample(scan, STONE, type -> true, Set.of(GRANITE), 100, weights);
        helper.assertTrue(noGranite.getOrDefault(GRANITE, 0) == 0, "Excluded granite is never rolled");
        double stone = noGranite.getOrDefault(STONE, 0) / (double) SAMPLES;
        helper.assertTrue(Math.abs(stone - 0.6604) < 0.015, "Stone takes the whole stone share (~66 %): " + stone);

        Map<Identifier, Integer> noIron = sample(scan, STONE, type -> true, Set.of(IRON_ORE), 100, weights);
        helper.assertTrue(noIron.getOrDefault(IRON_ORE, 0) == 0, "Excluded iron is never rolled");
        double diamond = noIron.getOrDefault(DIAMOND_ORE, 0) / (double) SAMPLES;
        helper.assertTrue(Math.abs(diamond - 0.3396) < 0.015, "Diamond takes the whole ore share (~34 %): " + diamond);

        Map<Identifier, Integer> noStones = sample(scan, STONE, type -> true, Set.of(STONE, GRANITE), 1, weights);
        double empty = noStones.getOrDefault(EMPTY_ROLL, 0) / (double) SAMPLES;
        double ores = (noStones.getOrDefault(IRON_ORE, 0) + noStones.getOrDefault(DIAMOND_ORE, 0)) / (double) SAMPLES;
        helper.assertTrue(Math.abs(empty - 0.997) < 0.005 && ores < 0.01,
                "All stones excluded at level 1: empty rolls at the stone share, ores at the old rate (empty " + empty + ", ores " + ores + ")");
        helper.assertTrue(noStones.getOrDefault(Identifier.withDefaultNamespace("air"), 0) == 0, "An excluded group is an empty roll, not a missing one");
        helper.succeed();
    }

    /** Stone share by level, POI boost, missing group, harvest level exclusion. */
    private static void roll(GameTestHelper helper) {
        MinerWeights weights = new MinerWeights(99.7, -0.34, Map.of(STONE, 100.0, GRANITE, 100.0), Map.of(IRON_ORE, 70.0, DIAMOND_ORE, 30.0), 10, 10);
        MineScan.Result scan = new MineScan.Result(Map.of(STONE, 1, GRANITE, 1), Map.of(IRON_ORE, 1, DIAMOND_ORE, 1));
        helper.assertTrue(Math.abs(weights.stoneShare(1) - 0.997) < 1.0E-9 && Math.abs(weights.stoneShare(100) - 0.6604) < 1.0E-9,
                "Stone share: 99.7 % at level 1, 66.04 % at level 100");

        Map<Identifier, Integer> level100 = sample(scan, STONE, type -> true, 100, weights);
        double ores = (level100.getOrDefault(IRON_ORE, 0) + level100.getOrDefault(DIAMOND_ORE, 0)) / (double) SAMPLES;
        helper.assertTrue(Math.abs(ores - 0.3396) < 0.015, "Ore share at level 100 should be ~34 %: " + ores);
        double stoneOfGroup = level100.get(STONE) / (double) (level100.get(STONE) + level100.get(GRANITE));
        helper.assertTrue(Math.abs(stoneOfGroup - 2.0 / 3.0) < 0.02, "POI stone has double weight in its group: " + stoneOfGroup);

        Map<Identifier, Integer> noDiamond = sample(scan, STONE, type -> !type.equals(DIAMOND_ORE), 100, weights);
        helper.assertTrue(noDiamond.getOrDefault(DIAMOND_ORE, 0) == 0 && noDiamond.getOrDefault(IRON_ORE, 0) > 0,
                "Ores the pickaxe cannot harvest are never rolled");

        Map<Identifier, Integer> stonesOnly = sample(new MineScan.Result(Map.of(STONE, 1), Map.of()), STONE, type -> true, 100, weights);
        double nothing = stonesOnly.getOrDefault(Identifier.withDefaultNamespace("air"), 0) / (double) SAMPLES;
        helper.assertTrue(Math.abs(nothing - 0.3396) < 0.015, "A missing ore group yields nothing for those rolls: " + nothing);
        helper.succeed();
    }

    /**
     * The weight of a type is its base weight times the square root of its block count, inside its group; an ore without
     * a base weight falls back to the unknown ore weight. The groups keep their skill share.
     */
    private static void rollWeightsUseCounts(GameTestHelper helper) {
        Identifier ruby = Identifier.fromNamespaceAndPath("modded", "ruby_ore");
        MinerWeights weights = new MinerWeights(99.7, -0.34, Map.of(STONE, 100.0), Map.of(IRON_ORE, 70.0, DIAMOND_ORE, 70.0), 10, 10);
        MineScan.Result scan = new MineScan.Result(Map.of(STONE, 1), Map.of(IRON_ORE, 400, DIAMOND_ORE, 100, ruby, 100));

        Map<Identifier, Integer> level100 = sample(scan, STONE, type -> true, 100, weights);
        double ores = (level100.getOrDefault(IRON_ORE, 0) + level100.getOrDefault(DIAMOND_ORE, 0) + level100.getOrDefault(ruby, 0)) / (double) SAMPLES;
        helper.assertTrue(Math.abs(ores - 0.3396) < 0.015, "The ore group keeps its skill share (~34 %): " + ores);
        // 70 x sqrt(400) = 1400, 70 x sqrt(100) = 700, unknown 10 x sqrt(100) = 100: shares 14/22, 7/22, 1/22 of the ore rolls.
        double oreRolls = ores * SAMPLES;
        double iron = level100.getOrDefault(IRON_ORE, 0) / oreRolls;
        double diamond = level100.getOrDefault(DIAMOND_ORE, 0) / oreRolls;
        double unknown = level100.getOrDefault(ruby, 0) / oreRolls;
        helper.assertTrue(Math.abs(iron - 14.0 / 22.0) < 0.03, "Iron: 4x the blocks gives 2x the weight (~64 % of the ores): " + iron);
        helper.assertTrue(Math.abs(diamond - 7.0 / 22.0) < 0.03, "Diamond (~32 % of the ores): " + diamond);
        helper.assertTrue(Math.abs(unknown - 1.0 / 22.0) < 0.02, "An ore without a weight uses the unknown ore weight (~5 %): " + unknown);
        helper.succeed();
    }

    /** The Found tab names each kind with its chance and never shows the number of blocks. */
    private static void foundTabShowsChance(GameTestHelper helper) {
        ResourceCollectionProfile profile = ModJobs.MINER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        MineScan.Result scan = new MineScan.Result(Map.of(STONE, 4321), Map.of(IRON_ORE, 5));
        Map<Identifier, String> names = new HashMap<>();
        profile.found(scan, Optional.empty()).forEach(found -> names.put(found.type(), found.icon().getHoverName().getString()));
        helper.assertTrue(names.get(STONE).contains("99.7"), "The only stone has the whole stone share at skill 1: " + names.get(STONE));
        helper.assertTrue(names.get(IRON_ORE).contains("0.3"), "The only ore has the whole ore share at skill 1: " + names.get(IRON_ORE));
        names.values().forEach(name -> helper.assertFalse(name.contains("4321") || name.contains("5 "), "No block count in " + name));
        helper.succeed();
    }

    /** Later files override earlier ones; unset fields keep their values. */
    private static void weightsMerge(GameTestHelper helper) {
        MinerWeights merged = MinerWeights.merge(List.of(
                JsonParser.parseString("{\"stone_share\":{\"level_1\":90,\"per_level\":-0.5},\"stones\":{\"minecraft:stone\":100},\"ores\":{\"minecraft:iron_ore\":70},\"unknown_ore\":5}"),
                JsonParser.parseString("{\"ores\":{\"minecraft:iron_ore\":20,\"minecraft:coal_ore\":80}}")));
        helper.assertTrue(merged.stoneShareLevel1() == 90 && merged.stoneSharePerLevel() == -0.5, "Stone share from the first file");
        helper.assertTrue(merged.weight(IRON_ORE, false) == 20 && merged.weight(Identifier.withDefaultNamespace("coal_ore"), false) == 80,
                "Second file overrides and extends ores");
        helper.assertTrue(merged.weight(STONE, true) == 100 && merged.weight(GRANITE, true) == MinerWeights.FALLBACK.unknownStone()
                && merged.weight(DIAMOND_ORE, false) == 5, "Unknown types use the fallback weights");
        helper.succeed();
    }

    /** The scan counts the placed stone and ore blocks per type, and a single ore block is never missed. */
    private static void paletteScan(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.GRANITE);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.EMERALD_ORE);
        helper.setBlock(new BlockPos(5, 1, 2), Blocks.OAK_LOG);
        ServerLevel level = helper.getLevel();
        MineScan.Result result = new MineScan(helper.absolutePos(new BlockPos(2, 1, 2)), 10).runAll(level);
        helper.assertTrue(result.stones().getOrDefault(GRANITE, 0) == 1, "One granite block should be counted: " + result);
        helper.assertTrue(result.ores().getOrDefault(IRON_ORE, 0) == 3, "Three iron ore blocks should be counted: " + result);
        helper.assertTrue(result.ores().getOrDefault(Identifier.withDefaultNamespace("emerald_ore"), 0) == 1,
                "A single emerald ore block is never missed: " + result);
        helper.assertFalse(result.stones().containsKey(Identifier.withDefaultNamespace("oak_log")), "Only stones and ores");
        helper.succeed();
    }

    /** A section the radius touches counts whole: a block in the same section but outside the radius is counted too. */
    private static void edgeSectionCountsWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        // The far corner of the centre's chunk section: more than the radius of 1 away, but in the same section.
        BlockPos far = new BlockPos(farEdge(center.getX()), farEdge(center.getY()), farEdge(center.getZ()));
        BlockState before = level.getBlockState(far);
        level.setBlock(far, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        try {
            MineScan.Result result = new MineScan(center, 1).runAll(level);
            helper.assertTrue(result.ores().getOrDefault(DIAMOND_ORE, 0) == 1,
                    "An edge section counts whole, so the far diamond ore is counted: " + result);
        } finally {
            level.setBlock(far, before, 2);
        }
        helper.succeed();
    }

    /** The coordinate at the far end of the section of {@code coordinate}, seen from {@code coordinate}. */
    private static int farEdge(int coordinate) {
        int offset = Math.floorMod(coordinate, 16);
        return coordinate - offset + (offset < 8 ? 15 : 0);
    }

    /** The saved scan keeps the counts. */
    private static void scanCodec(GameTestHelper helper) {
        MineScan.Result scan = new MineScan.Result(Map.of(STONE, 4321, GRANITE, 7), Map.of(IRON_ORE, 12, DIAMOND_ORE, 1));
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SavedScan saved = SavedScan.of(BlockPos.ZERO, BlockPos.ZERO, 10, -1L, scan).orElseThrow(() -> new AssertionError("Mine scan is saved"));
        helper.assertValueEqual(SavedScan.CODEC.parse(ops, SavedScan.CODEC.encodeStart(ops, saved).getOrThrow()).getOrThrow().result(), scan, "Saved");
        helper.succeed();
    }

    /** Only blocks a settler can stand next to or on top of are exposed. */
    private static void exposedWorkPlace(GameTestHelper helper) {
        for (int x = 2; x <= 6; x++) {
            for (int y = 1; y <= 5; y++) {
                for (int z = 2; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        ServerLevel level = helper.getLevel();
        helper.assertFalse(BlockSearch.isExposed(level, helper.absolutePos(new BlockPos(4, 3, 4))), "Enclosed block is not exposed");
        helper.assertTrue(BlockSearch.isExposed(level, helper.absolutePos(new BlockPos(4, 5, 4))), "Top block is exposed");
        helper.succeed();
    }

    /** A Miner with a stone pickaxe produces stone from its work place in the world. */
    private static void worksInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos jobBlockRelative = new BlockPos(1, 1, 1);
        TestSettlements.setBlock(helper, jobBlockRelative, ModBlocks.MINER_BLOCK.get());
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.GRANITE);
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Digger"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_PICKAXE));
        BlockPos poi = helper.absolutePos(new BlockPos(5, 1, 5));
        JobAssignments.cyclePoint(data, jobBlock, poi, true, false);
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Digger", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), jobBlock));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() > 0,
                    "Nothing mined yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(level.getBlockState(poi).is(Blocks.GRANITE), "The work place is never destroyed");
            helper.assertTrue(FoundTabData.of(level, jobBlock).entry(GRANITE).isPresent(), "Found tab lists granite");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
