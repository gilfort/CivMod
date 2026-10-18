package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.debug.Benchmark;
import com.gilfort.civmod.debug.CivProfiler;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.Reservations;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
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

    /** One connected settlement with 30 working settlers (4 of them Deliverymen, 2 Craftspersons on "Deliveryman"); the report is written to the log. */
    private static void smoke(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(6, 4, 6));
        // The settlers only work in entity-ticking chunks: force the whole benchmark area.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(6 + (Benchmark.SIZE - 1) * 6 + 6, 0, 6 + 26));
        int spawned = Benchmark.build(level, origin);
        helper.assertTrue(spawned == Benchmark.SETTLERS, Benchmark.SETTLERS + " settlers expected, got " + spawned);
        Settlement settlement = SettlementData.get(level).settlementAt(origin).orElseThrow();
        // The 20 job JobBlocks plus the two Clerks and the 4 Craftspersons.
        helper.assertTrue(settlement.blocks().size() == Benchmark.JOB_BLOCKS && settlement.connectedCount() == Benchmark.JOB_BLOCKS,
                "All JobBlocks connected: " + settlement.connectedCount());
        helper.runAtTickTime(2380, () -> {
            CivProfiler.report().forEach(line -> CivMod.LOGGER.info("[benchmark] {}", line));
            BlockPos clerk = origin.offset(-3, 0, 0);
            int crafted = ClerkStorage.stock(level, clerk, true)
                    .count(stack -> stack.is(Items.TORCH) || stack.is(Items.STONE_PICKAXE));
            CivMod.LOGGER.info("[benchmark] Craftspersons: {} item(s) delivered, {} request(s) claimed", crafted,
                    settlement.requests().stream().filter(request -> !request.claims().isEmpty()).count());
            int collected = ClerkStorage.stock(level, clerk, true)
                    .count(stack -> stack.is(Items.OAK_LOG) || stack.is(Items.GRANITE));
            CivMod.LOGGER.info("[benchmark] Deliverymen: {} item(s) collected into the Clerk, {} pickup(s) open", collected,
                    settlement.deliveries().stream().filter(task -> task.result().isEmpty()).count());
            BlockPos second = origin.offset(Benchmark.SECOND_CLERK);
            int forSecond = ClerkStorage.stock(level, second, true)
                    .count(stack -> stack.is(Items.TORCH) || stack.is(Items.STONE_PICKAXE));
            CivMod.LOGGER.info("[benchmark] Result deliveries: {} item(s) at the second Clerk, {} result delivery(s) open", forSecond,
                    settlement.deliveries().stream().filter(task -> task.result().isPresent()).count());
            stockReads(level, settlement, clerk);
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** Reads of the main Clerk's whole stock without reservations and with 8 of them (#79), timed. */
    private static void stockReads(ServerLevel level, Settlement settlement, BlockPos clerk) {
        double without = stockReadMicros(level, clerk);
        SettlementData data = SettlementData.get(level);
        long day = DayClock.day(level);
        List<UUID> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BlockPos source = clerk.offset(0, 200, i);
            Deliveries.request(data, settlement, source, clerk, level.getGameTime(), false);
            UUID task = Deliveries.get(settlement, source).orElseThrow().id();
            Deliveries.take(data, settlement, task, UUID.randomUUID(), "Bench", day);
            Reservations.reserve(settlement, clerk, task, i % 2 == 0 ? Items.BREAD : Items.OAK_LOG, 4, day);
            tasks.add(task);
        }
        double with = stockReadMicros(level, clerk);
        tasks.forEach(task -> Deliveries.done(data, settlement, task));
        CivMod.LOGGER.info("[benchmark] Stock reads (counts + edible count): {} us without reservations, {} us with 8",
                String.format(Locale.ROOT, "%.2f", without), String.format(Locale.ROOT, "%.2f", with));
    }

    /** Average time of one full stock read (build, counts, edible count) after a warm-up. */
    private static double stockReadMicros(ServerLevel level, BlockPos clerk) {
        int reads = 20_000;
        long sink = 0;
        for (int i = 0; i < reads; i++) {
            sink += read(level, clerk);
        }
        long start = System.nanoTime();
        for (int i = 0; i < reads; i++) {
            sink += read(level, clerk);
        }
        long elapsed = System.nanoTime() - start;
        return sink < 0 ? -1 : elapsed / 1000.0 / reads;
    }

    private static long read(ServerLevel level, BlockPos clerk) {
        Stock stock = ClerkStorage.stock(level, clerk, true);
        return stock.counts().size() + stock.count(SettlerFood::isEdible);
    }
}
