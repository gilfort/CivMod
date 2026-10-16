package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.Fatigue;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Work rules: live play and catch-up produce alike for the same job, tool, skill and work time.
 * <p>
 * The 3000-tick live run uses its own test environment ({@code civmod:long_run}), so it runs in a batch of its own
 * and does not overlap the benchmark window of {@link BenchmarkGameTests}.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class WorkRulesGameTests {

    private static final long DAY = 24000L;
    /** Work time compared, in ticks (2.5 minutes). */
    private static final int WINDOW = 3000;

    private WorkRulesGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "work_rules_live_matches_catch_up", WorkRulesGameTests::liveMatchesCatchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static int output(ServerLevel level, Settler settler, BlockPos jobBlock) {
        Container container = (Container) level.getBlockEntity(jobBlock);
        int total = settler.getWork().buffer(jobBlock).count();
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    /**
     * A Lumberjack at its skill cap (no level-ups) with a netherite axe works {@value #WINDOW} ticks live, then the same
     * time is caught up (multiplier 1.0). Output per working tick agrees within 25 %: live counts only the ticks shown
     * as working (breaks excluded), catch-up uses the expected work share.
     */
    private static void liveMatchesCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.NETHERITE_AXE));
        int cap = SettlementRules.skillCap(SettlementRules.MIN_LEVEL);
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(cap, 0));
        // Live window starts with the first produced item (the settler stands at its work place).
        int[] start = {-1};
        int[] ticks = new int[1];
        int[] working = new int[1];
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            int now = output(level, settler, jobBlock);
            if (start[0] < 0) {
                if (now > 0) {
                    start[0] = now;
                }
                return;
            }
            ticks[0]++;
            if (settler.getWork().status(jobBlock) == JobStatus.WORKING) {
                working[0]++;
            }
            if (ticks[0] < WINDOW) {
                return;
            }
            done[0] = true;
            int live = now - start[0];
            long clock = level.getOverworldClockTime();
            long yesterday = Math.floorDiv(clock, DAY) * DAY - DAY;
            settler.getWork().setCatchUp(yesterday, yesterday + WINDOW);
            int before = output(level, settler, jobBlock);
            CatchUp.runNow(level, List.of(settler));
            int caughtUp = output(level, settler, jobBlock) - before;
            double liveRate = live / (double) Math.max(1, working[0]);
            double catchUpRate = caughtUp / (WINDOW * Fatigue.expectedWorkShare());
            double ratio = liveRate / Math.max(1.0E-9, catchUpRate);
            helper.assertTrue(live >= 20 && caughtUp >= 20, "Too little output to compare: live " + live + ", catch-up " + caughtUp);
            helper.assertTrue(ratio > 0.75 && ratio < 1.33, "Live " + live + " in " + working[0] + " working ticks vs catch-up " + caughtUp
                    + " in " + WINDOW + " ticks (ratio " + String.format(java.util.Locale.ROOT, "%.2f", ratio) + ")");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }
}
