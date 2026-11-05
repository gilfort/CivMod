package com.gilfort.civmod.job;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the search throttle of the tool need (vault {@code Systems/Needs.md}): every search of the collect
 * inventory runs at most once per in-game hour and settler Job after a fruitless one. The seam is the counted search.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ToolSearchThrottleGameTests {

    private ToolSearchThrottleGameTests() {
    }

    /**
     * Lets the settler's next search of the collect inventory run: a test that drives catch-up over a past window while
     * the live settler already searched in vain at the current game time would otherwise be throttled.
     *
     * @param settler the settler
     */
    public static void forgetSearches(com.gilfort.civmod.npc.Settler settler) {
        settler.getJobs().forEach(job -> settler.getWork().toolNeeds().searched(job, Long.MIN_VALUE / 2));
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("tool_search_throttle_counted")),
                    (Consumer<GameTestHelper>) ToolSearchThrottleGameTests::counted);
        });
    }

    /** A fruitless search is not repeated within the hour (per Job), a fruitful one is not recorded. */
    private static void counted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos otherBlock = helper.absolutePos(new BlockPos(5, 1, 2));
        TestSettlements.place(helper, jobBlock, ModJobs.LUMBERJACK.get(), 5);
        TestSettlements.place(helper, otherBlock, ModJobs.LUMBERJACK.get(), 5);
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        SettlerJob job = new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock);
        SettlerJob other = new SettlerJob(ModJobs.LUMBERJACK.get(), otherBlock);
        settler.addJob(job);
        settler.addJob(other);
        ToolNeeds tools = settler.getWork().toolNeeds();
        int[] scans = new int[1];
        long t = 100_000L;

        helper.assertTrue(!tools.search(job, t, () -> {
            scans[0]++;
            return false;
        }), "A fruitless search finds nothing");
        helper.assertTrue(scans[0] == 1, "The first search ran");
        tools.search(job, t + 1, () -> {
            scans[0]++;
            return true;
        });
        helper.assertTrue(scans[0] == 1, "No second scan within the hour: " + scans[0]);
        helper.assertTrue(!tools.search(job, t + ToolNeeds.SEARCH_INTERVAL - 1, () -> {
            scans[0]++;
            return true;
        }) && scans[0] == 1, "Still throttled just before the hour is over");
        helper.assertTrue(tools.search(other, t + 2, () -> {
            scans[0]++;
            return true;
        }) && scans[0] == 2, "The other Job has its own throttle");
        helper.assertTrue(tools.search(other, t + 3, () -> {
            scans[0]++;
            return true;
        }) && scans[0] == 3, "A search that found a tool is not recorded: the settler may walk on");
        helper.assertTrue(tools.search(job, t + ToolNeeds.SEARCH_INTERVAL, () -> {
            scans[0]++;
            return true;
        }) && scans[0] == 4, "An hour later it searches again");

        settler.discard();
        SettlementManager.remove(data, jobBlock, 0L, SettlementManager.Listener.NONE);
        SettlementManager.remove(data, otherBlock, 0L, SettlementManager.Listener.NONE);
        helper.succeed();
    }
}
