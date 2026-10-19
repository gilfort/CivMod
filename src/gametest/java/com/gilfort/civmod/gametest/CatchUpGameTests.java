package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.Fatigue;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.job.WaterScan;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the catch-up simulation.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CatchUpGameTests {

    private static final long DAY = 24000L;

    private CatchUpGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "catch_up_rules", CatchUpGameTests::rules);
            register(helper, "catch_up_persistence", CatchUpGameTests::persistence);
            register(helper, "catch_up_lumberjack", CatchUpGameTests::lumberjack);
            register(helper, "catch_up_full_drop_off", CatchUpGameTests::fullDropOff);
            register(helper, "catch_up_foraging", CatchUpGameTests::foraging);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Daily rotation of the settler order, average work share, day cap. */
    private static void rules(GameTestHelper helper) {
        List<String> settlers = List.of("a", "b", "c");
        helper.assertTrue(CatchUp.order(settlers, 0).equals(List.of("a", "b", "c")) && CatchUp.order(settlers, 1).equals(List.of("b", "c", "a"))
                && CatchUp.order(settlers, 5).equals(List.of("c", "a", "b")), "The order rotates every day");
        double share = Fatigue.expectedWorkShare();
        helper.assertTrue(share > 0.85 && share < 0.99, "Average work share without breaks: " + share);

        Settler settler = ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
        settler.getWork().setCatchUp(0L, 30 * DAY);
        helper.assertTrue(settler.getWork().catchUpFrom() == 20 * DAY, "At most 10 days are caught up");
        settler.discard();
        helper.succeed();
    }

    /** Scan results and the missed time survive saving. */
    private static void persistence(GameTestHelper helper) {
        SettlerWork.BuffersSnapshot snapshot = new SettlerWork.BuffersSnapshot(List.of(), List.of(),
                List.of(SavedScan.of(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6), 10, -1L, new WaterScan.Result(77)).orElseThrow()),
                new SettlerWork.CatchUpState(5000L, 100L, 4000L));
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlerWork.BuffersSnapshot loaded = SettlerWork.BuffersSnapshot.CODEC.parse(ops,
                SettlerWork.BuffersSnapshot.CODEC.encodeStart(ops, snapshot).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.scans().size() == 1 && loaded.scans().get(0).result() instanceof WaterScan.Result water && water.water() == 77,
                "Scan result saved");
        helper.assertTrue(loaded.catchUp().equals(snapshot.catchUp()), "Catch-up state saved");
        helper.succeed();
    }

    /**
     * Sets up a Lumberjack at (1, 1, 1) with a stone axe, plenty of food, a work point on a block of logs.
     *
     * @return the settler (its job is the first one)
     */
    static Settler lumberjack(GameTestHelper helper, List<long[]> forced) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos jobBlockRelative = new BlockPos(1, 1, 1);
        helper.setBlock(jobBlockRelative, ModBlocks.LUMBERJACK_BLOCK.get());
        for (int x = 4; x < 8; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
            }
        }
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Catcher"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        // Plenty of food: hunger must not limit the test.
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(4, 1, 1)), true, false);
        JobAssignments.assign(data, jobBlock, settler.getUUID(), "Catcher", settlement.id(), List.of());
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
        return settler;
    }

    private static int count(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    /** One missed day: output in the drop-off, tool wear and EXP. */
    private static void lumberjack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        // Wait until the live scan exists (the catch-up needs it), then simulate one missed day once.
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || LumberjackGameTests.foundNothing(settler, settler.getJobs().get(0))) {
                return;
            }
            done[0] = true;
            Container container = (Container) level.getBlockEntity(jobBlock);
            int before = count(container) + settler.getWork().buffer(jobBlock).count();
            int damageBefore = settler.getInventory().getItem(Settler.BELT_START).getDamageValue();
            int expBefore = settler.getSkills().get(ModJobs.LUMBERJACK.get()).exp() + 1000 * settler.getSkills().get(ModJobs.LUMBERJACK.get()).level();
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            int produced = count(container) + settler.getWork().buffer(jobBlock).count() - before;
            helper.assertTrue(produced >= 40, "About a day of wood expected: " + produced + " (food " + settler.getFood().level()
                    + ", tool " + settler.getInventory().getItem(Settler.BELT_START) + ", status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(settler.getInventory().getItem(Settler.BELT_START).isEmpty()
                    || settler.getInventory().getItem(Settler.BELT_START).getDamageValue() > damageBefore, "The axe wears");
            int expAfter = settler.getSkills().get(ModJobs.LUMBERJACK.get()).exp() + 1000 * settler.getSkills().get(ModJobs.LUMBERJACK.get()).level();
            helper.assertTrue(expAfter > expBefore, "EXP gained");
            helper.assertFalse(settler.getWork().hasPendingCatchUp(), "Nothing pending afterwards");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** A full drop-off fills the buffer, then the job ends with "Drop-off full". */
    private static void fullDropOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || LumberjackGameTests.foundNothing(settler, settler.getJobs().get(0))) {
                return;
            }
            done[0] = true;
            Container container = (Container) level.getBlockEntity(jobBlock);
            for (int i = 0; i < container.getContainerSize(); i++) {
                container.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.NETHERITE_AXE));
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - 6 * DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(settler.getWork().buffer(jobBlock).isFull(), "Buffer full: " + settler.getWork().buffer(jobBlock).count());
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.DROP_OFF_FULL, "Status: " + settler.getWork().status(jobBlock));
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** Without any food a hungry settler forages through the missed days (+5 each). */
    private static void foraging(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.MINER_BLOCK.get());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getFood().setLevel(4);
        settler.getFood().setSaturation(0.0F);
        settler.getFood().setAppetite(1.0F);
        long clock = level.getOverworldClockTime();
        long dayStart = Math.floorDiv(clock, DAY) * DAY;
        // Two full missed days before today.
        settler.getWork().setCatchUp(dayStart - 2 * DAY, dayStart);
        CatchUp.runNow(level, List.of(settler));
        // Each day: forages from the morning (no hunger while away), back at 17:30 with 5 food, then hungry like live
        // play until bed at 20:00: 2500 of 14000 awake ticks at 8 food per day = 1.43 points. Over both days 2.86
        // points, of which 2 whole points are lost (no saturation): 4 + 5 + 5 - 2 = 12.
        helper.assertTrue(settler.getFood().level() == 12, "Two foraging days: 4 + 5 + 5 - 2, got " + settler.getFood().level());
        helper.assertFalse(settler.getFood().isForaging(), "Not foraging afterwards");
        settler.discard();
        helper.succeed();
    }
}
