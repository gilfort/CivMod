package com.gilfort.civmod.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.MineScan;
import com.gilfort.civmod.job.MinerOutput;
import com.gilfort.civmod.job.MinerProfile;
import com.gilfort.civmod.job.MinerWeights;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.google.gson.JsonParser;

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
    private static final int SAMPLES = 20000;

    private MinerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "miner_roll", MinerGameTests::roll);
            register(helper, "miner_weights_merge", MinerGameTests::weightsMerge);
            register(helper, "miner_palette_scan", MinerGameTests::paletteScan);
            register(helper, "miner_exposed_work_place", MinerGameTests::exposedWorkPlace);
            register(helper, "miner_works_in_world", MinerGameTests::worksInWorld);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Map<Identifier, Integer> sample(MineScan.Result scan, Identifier poi, java.util.function.Predicate<Identifier> harvestable,
            int level, MinerWeights weights) {
        RandomSource random = RandomSource.create(11L);
        Map<Identifier, Integer> counts = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            Identifier type = MinerOutput.roll(scan, poi, harvestable, level, weights, random).orElse(Identifier.withDefaultNamespace("air"));
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    /** Stone share by level, POI boost, missing group, harvest level exclusion. */
    private static void roll(GameTestHelper helper) {
        MinerWeights weights = new MinerWeights(99.7, -0.34, Map.of(STONE, 100.0, GRANITE, 100.0), Map.of(IRON_ORE, 70.0, DIAMOND_ORE, 30.0), 10, 10);
        MineScan.Result scan = new MineScan.Result(Set.of(STONE, GRANITE), Set.of(IRON_ORE, DIAMOND_ORE));
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

        Map<Identifier, Integer> stonesOnly = sample(new MineScan.Result(Set.of(STONE), Set.of()), STONE, type -> true, 100, weights);
        double nothing = stonesOnly.getOrDefault(Identifier.withDefaultNamespace("air"), 0) / (double) SAMPLES;
        helper.assertTrue(Math.abs(nothing - 0.3396) < 0.015, "A missing ore group yields nothing for those rolls: " + nothing);
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

    /** The palette scan finds placed stone and ore types; types outside the radius are ignored. */
    private static void paletteScan(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.GRANITE);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.EMERALD_ORE);
        ServerLevel level = helper.getLevel();
        MineScan.Result result = new MineScan(helper.absolutePos(new BlockPos(2, 1, 2)), 10).runAll(level);
        helper.assertTrue(result.stones().contains(GRANITE), "Granite should be found: " + result);
        helper.assertTrue(result.ores().contains(IRON_ORE) && result.ores().contains(Identifier.withDefaultNamespace("emerald_ore")),
                "Ores should be found: " + result);
        helper.assertFalse(result.stones().contains(Identifier.withDefaultNamespace("oak_log")), "Only stones and ores");
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
        helper.assertFalse(MinerProfile.isExposed(level, helper.absolutePos(new BlockPos(4, 3, 4))), "Enclosed block is not exposed");
        helper.assertTrue(MinerProfile.isExposed(level, helper.absolutePos(new BlockPos(4, 5, 4))), "Top block is exposed");
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
        helper.setBlock(jobBlockRelative, ModBlocks.MINER_BLOCK.get());
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
        helper.assertTrue(JobAssignments.assign(data, jobBlock, settler.getUUID(), "Digger", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(JobType.MINER, jobBlock));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() > 0,
                    "Nothing mined yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(level.getBlockState(poi).is(Blocks.GRANITE), "The work place is never destroyed");
            helper.assertTrue(settler.getWork().found(settler.getJobs().get(0)).stream()
                    .anyMatch(found -> found.icon().is(Items.GRANITE)), "Found list contains granite");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
