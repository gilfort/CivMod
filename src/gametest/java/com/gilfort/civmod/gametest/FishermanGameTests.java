package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.FishermanProfile;
import com.gilfort.civmod.job.WaterScan;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Fisherman story.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FishermanGameTests {

    private static final int SIZE = 10;
    private static final int POOL_MIN = 4;
    private static final int POOL_MAX = 8;

    private FishermanGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "fisherman_formulas", FishermanGameTests::formulas);
            register(helper, "fisherman_water_scan", FishermanGameTests::waterScan);
            register(helper, "fisherman_open_water_and_shore", FishermanGameTests::openWaterAndShore);
            register(helper, "fisherman_works_in_world", FishermanGameTests::worksInWorld);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * Ground of stone (y 0–1) with a 5×5 pool, 2 deep (y 1–2), its surface level with the walkable ground (y 2).
     */
    private static void pond(GameTestHelper helper) {
        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                boolean pool = x >= POOL_MIN && x <= POOL_MAX && z >= POOL_MIN && z <= POOL_MAX;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 1, z), pool ? Blocks.WATER : Blocks.STONE);
                helper.setBlock(new BlockPos(x, 2, z), pool ? Blocks.WATER : Blocks.AIR);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
            }
        }
    }

    /** Speed with Lure, treasure share with Luck of the Sea, water productivity. */
    private static void formulas(GameTestHelper helper) {
        helper.assertTrue(Math.abs(FishermanProfile.speed(1, 0) - 0.05) < 1.0E-9 && Math.abs(FishermanProfile.speed(100, 0) - 0.2) < 1.0E-9,
                "One catch per 20 s at level 1, per 5 s at level 100");
        helper.assertTrue(Math.abs(FishermanProfile.speed(1, 3) - 0.05 * 1.45) < 1.0E-9, "Lure III: +45 %");
        helper.assertTrue(FishermanProfile.treasureChance(1, 0) == 0.0 && Math.abs(FishermanProfile.treasureChance(100, 0) - 0.25) < 1.0E-9,
                "Treasure 0 % at level 1, 25 % at level 100");
        helper.assertTrue(Math.abs(FishermanProfile.treasureChance(1, 3) - 0.06) < 1.0E-9, "Luck of the Sea III: +6 points");
        helper.assertTrue(Math.abs(FishermanProfile.waterProductivity(35) - 0.85) < 1.0E-9 && FishermanProfile.waterProductivity(80) == 1.0,
                "−1 % per missing water block below 50");
        helper.succeed();
    }

    /** The water scan counts the pool. */
    private static void waterScan(GameTestHelper helper) {
        pond(helper);
        WaterScan.Result result = new WaterScan(helper.absolutePos(new BlockPos(6, 2, 6)), 10).runAll(helper.getLevel());
        helper.assertTrue(result.water() >= 30, "Pool water should be counted: " + result.water());
        helper.succeed();
    }

    /** Open water needs water around and depth; the shore must be within the fishing reach. */
    private static void openWaterAndShore(GameTestHelper helper) {
        pond(helper);
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(6, 2, 6));
        helper.assertTrue(FishermanProfile.isOpenWater(level, center), "Pool centre is open water");
        helper.assertTrue(FishermanProfile.hasShore(level, center), "Shore within 6 blocks");
        helper.assertFalse(FishermanProfile.isOpenWater(level, helper.absolutePos(new BlockPos(POOL_MIN, 2, 6))), "Pool edge touches ground");
        helper.assertFalse(FishermanProfile.isOpenWater(level, helper.absolutePos(new BlockPos(6, 1, 6))), "Below the surface is not open");
        helper.succeed();
    }

    /** A Fisherman with a rod catches fish from the pool, standing on the shore. */
    private static void worksInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        pond(helper);
        BlockPos jobBlockRelative = new BlockPos(1, 2, 1);
        helper.setBlock(jobBlockRelative, ModBlocks.FISHERMAN_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Angler"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.FISHING_ROD));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(6, 2, 6)), true, false);
        helper.assertTrue(JobAssignments.assign(data, jobBlock, settler.getUUID(), "Angler", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.FISHERMAN.get(), jobBlock));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() > 0,
                    "Nothing caught yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertFalse(settler.isInWater(), "The Fisherman stands on the shore");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
