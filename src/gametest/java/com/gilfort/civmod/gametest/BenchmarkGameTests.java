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

    /** One connected settlement with 27 working settlers (4 of them Deliverymen); the report is written to the log. */
    private static void smoke(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(6, 4, 6));
        // The settlers only work in entity-ticking chunks: force the whole benchmark area.
        java.util.List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(6 + (Benchmark.SIZE - 1) * 6 + 6, 0, 6 + 14));
        int spawned = Benchmark.build(level, origin);
        helper.assertTrue(spawned == Benchmark.SETTLERS, Benchmark.SETTLERS + " settlers expected, got " + spawned);
        Settlement settlement = SettlementData.get(level).settlementAt(origin).orElseThrow();
        // The 20 job JobBlocks plus the Clerk (food point) and the 2 Craftspersons.
        helper.assertTrue(settlement.blocks().size() == Benchmark.JOB_BLOCKS && settlement.connectedCount() == Benchmark.JOB_BLOCKS,
                "All JobBlocks connected: " + settlement.connectedCount());
        helper.runAtTickTime(2380, () -> {
            CivProfiler.report().forEach(line -> CivMod.LOGGER.info("[benchmark] {}", line));
            BlockPos clerk = origin.offset(-3, 0, 0);
            int crafted = com.gilfort.civmod.logistics.ClerkStorage.stock(level, clerk, true)
                    .count(stack -> stack.is(net.minecraft.world.item.Items.TORCH) || stack.is(net.minecraft.world.item.Items.STONE_PICKAXE));
            CivMod.LOGGER.info("[benchmark] Craftspersons: {} item(s) delivered, {} request(s) claimed", crafted,
                    settlement.requests().stream().filter(request -> !request.claims().isEmpty()).count());
            int collected = com.gilfort.civmod.logistics.ClerkStorage.stock(level, clerk, true)
                    .count(stack -> stack.is(net.minecraft.world.item.Items.OAK_LOG) || stack.is(net.minecraft.world.item.Items.GRANITE));
            CivMod.LOGGER.info("[benchmark] Deliverymen: {} item(s) collected into the Clerk, {} pickup(s) open", collected,
                    settlement.deliveries().size());
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }
}
