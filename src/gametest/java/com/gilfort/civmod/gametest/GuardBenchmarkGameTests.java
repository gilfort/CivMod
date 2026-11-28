package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.debug.CivProfiler;
import com.gilfort.civmod.debug.GuardBenchmark;
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
 * The Guard benchmark (vault {@code Jobs/Guard.md}, "Benchmark (G5)"): Guards and monsters on an arena, the baseline "Guards
 * attack only" of spec #349. {@code benchmark_guards} is the long run and only runs on the user's request, like
 * {@code benchmark_smoke}; {@code guard_benchmark_builds} runs the same builder at a tiny scale for a few seconds, so the
 * builder stays working.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GuardBenchmarkGameTests {

    /** The long run, in ticks: the report is written just before the test ends (2 minutes). */
    private static final int RUN_TICKS = 2380;
    /** The tiny run, in ticks. */
    private static final int SMALL_RUN_TICKS = 400;
    /** The monsters are topped up this often (ticks). */
    private static final int TOP_UP_TICKS = 20;
    /** Blocks between the test origin and the corner of the arena: its margin, and a little more. */
    private static final int OFFSET = 16;
    private static final int GROUND_Y = 4;

    private GuardBenchmarkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("benchmark_guards")),
                    (Consumer<GameTestHelper>) test -> run(test, GuardBenchmark.Scale.BASELINE, RUN_TICKS, true));
            helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("guard_benchmark_builds")),
                    (Consumer<GameTestHelper>) test -> run(test, new GuardBenchmark.Scale(2, 4), SMALL_RUN_TICKS, false));
        });
    }

    /**
     * Builds the arena, lets it run and logs the profiler report ("[benchmark]" lines, which {@code scripts/gametest.sh} prints).
     * The small run checks the builder as well: the Guards and the post joined the settlement, and the Guards killed monsters.
     */
    private static void run(GameTestHelper helper, GuardBenchmark.Scale scale, int ticks, boolean report) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(OFFSET, GROUND_Y, OFFSET));
        GuardBenchmark benchmark = new GuardBenchmark(level, origin, scale);
        // The settlers only work in entity-ticking chunks: force the whole arena.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(OFFSET + benchmark.edge(), 0, OFFSET + benchmark.edge()));
        int spawned = benchmark.build();
        helper.assertTrue(spawned == scale.guards(), scale.guards() + " Guards expected, got " + spawned);
        Settlement settlement = SettlementData.get(level).settlementAt(origin).orElseThrow();
        helper.assertTrue(settlement.connectedCount() == settlement.blocks().size(), "Everything joined the settlement: " + settlement.connectedCount() + " of "
                + settlement.blocks().size());
        helper.onEachTick(() -> {
            if (level.getGameTime() % TOP_UP_TICKS == 0) {
                benchmark.topUp();
            }
        });
        helper.runAtTickTime(ticks, () -> {
            long killed = benchmark.monstersSpawned() - benchmark.monstersAlive();
            if (report) {
                CivProfiler.report().forEach(line -> CivMod.LOGGER.info("[benchmark] {}", line));
                CivMod.LOGGER.info("[benchmark] Guards attack only: {} Guards, {} monsters at a time, {} monsters spawned, {} killed in {} s", scale.guards(),
                        scale.monsters(), benchmark.monstersSpawned(), killed, ticks / 20);
            } else {
                helper.assertTrue(killed > 0, "The Guards killed a monster: " + killed + " of " + benchmark.monstersSpawned());
            }
            benchmark.finish();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }
}
