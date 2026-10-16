package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.debug.Benchmark;
import com.gilfort.civmod.debug.CivProfiler;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Smoke test of the PoC benchmark: builds it, lets it run and logs the profiler report.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BenchmarkGameTests {

    private BenchmarkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> helper.register(
                ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("benchmark_smoke")), (Consumer<GameTestHelper>) BenchmarkGameTests::smoke));
    }

    /** One connected settlement with 20 working settlers; the report is written to the log. */
    private static void smoke(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(6, 4, 6));
        int spawned = Benchmark.build(level, origin);
        helper.assertTrue(spawned == Benchmark.SIZE, "20 settlers expected, got " + spawned);
        Settlement settlement = SettlementData.get(level).settlementAt(origin).orElseThrow();
        // The 20 job JobBlocks plus the Clerk (food point).
        helper.assertTrue(settlement.blocks().size() == Benchmark.SIZE + 1 && settlement.connectedCount() == Benchmark.SIZE + 1,
                "All 21 JobBlocks connected: " + settlement.connectedCount());
        helper.runAtTickTime(2380, () -> {
            CivProfiler.report().forEach(line -> CivMod.LOGGER.info("[benchmark] {}", line));
            helper.succeed();
        });
    }
}
