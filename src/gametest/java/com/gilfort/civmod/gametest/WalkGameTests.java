package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for walking: a settler walks to a work place 41 blocks away (beyond the 32-block follow range, in partial
 * paths) without being teleported by the anti-stuck rule.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class WalkGameTests {

    private WalkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("settler_walks_far")),
                (Consumer<GameTestHelper>) WalkGameTests::walks));
    }

    private static void walks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(44, 0, 6));
        for (int x = 0; x < 44; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(new BlockPos(42, y, 3), Blocks.OAK_LOG);
        }
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos log = helper.absolutePos(new BlockPos(42, 1, 3));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        settler.setNoAi(false);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        // Level 5: the work place 41 blocks away lies inside the border.
        com.gilfort.civmod.settlement.SettlementManager.setLevel(data, jobBlock, 5, com.gilfort.civmod.settlement.SettlementManager.Listener.NONE);
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, log, true, false) == JobAssignments.PointResult.WORK, "Work place set");
        JobAssignments.assign(data, jobBlock, settler.getUUID(), "Walker", settlement.id(), List.of());
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
        Vec3[] last = {settler.position()};
        int[] ticks = {0};
        helper.onEachTick(() -> {
            ticks[0]++;
            Vec3 now = settler.position();
            double jump = now.distanceTo(last[0]);
            if (jump > 2.0) {
                helper.fail("Teleported at tick " + ticks[0] + " by " + jump);
            }
            last[0] = now;
            if (now.distanceTo(Vec3.atCenterOf(log)) < 3.5) {
                settler.discard();
                com.gilfort.civmod.settlement.SettlementManager.setLevel(data, jobBlock, 1, com.gilfort.civmod.settlement.SettlementManager.Listener.NONE);
                forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                helper.succeed();
            }
        });
    }
}
