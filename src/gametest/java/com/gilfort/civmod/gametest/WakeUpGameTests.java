package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the night (environment {@code civmod:night}: the clock stands at 20:00): a settler goes to bed, the
 * night is skipped like a sleeping player does, and the woken settler stands on the ground next to its bed (or where it
 * lay) and walks normally – no floating, no teleport.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class WakeUpGameTests {

    private static final BlockPos BOARD = new BlockPos(1, 1, 1);
    private static final BlockPos BED = new BlockPos(6, 1, 2);
    private static final BlockPos GOAL = new BlockPos(1, 1, 7);

    private WakeUpGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "wake_up_from_bed", test -> wakeUp(test, true));
            register(helper, "wake_up_from_straw", test -> wakeUp(test, false));
            register(helper, "wake_up_by_vanilla", WakeUpGameTests::wokenByVanilla);
            register(helper, "wake_up_saved_on_straw", WakeUpGameTests::savedOnStraw);
            register(helper, "anti_stuck_after_long_stay", WakeUpGameTests::antiStuckAfterLongStay);
        });
    }

    /**
     * Vanilla wakes a sleeper on its own (first tick after loading, hurt, bed gone): a settler on a straw bed then gets
     * its gravity back and stands on the floor instead of floating where it lay, and the Schedule sends it to bed again.
     */
    private static void wokenByVanilla(GameTestHelper helper) {
        Settler settler = sleeper(helper, false);
        double floor = helper.absolutePos(BlockPos.ZERO).getY() + 1.0;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.isSleeping() && settler.isSleepingWithoutBed(), "Sleeps on straw"))
                .thenExecuteAfter(20, settler::stopSleeping)
                .thenExecuteAfter(20, () -> {
                    helper.assertFalse(settler.getGravity() == 0.0 || settler.isSleepingWithoutBed(), "No straw bed left behind");
                    helper.assertTrue(settler.onGround() && Math.abs(settler.getY() - floor) < 0.01,
                            "On the floor, not floating: " + (settler.getY() - floor) + " above the floor");
                })
                .thenWaitUntil(() -> helper.assertTrue(settler.isSleeping(), "Back to bed at night"))
                .thenExecute(() -> cleanUp(helper, settler))
                .thenSucceed();
    }

    /**
     * Anti-stuck: a Clerk that stood at its counter for longer than the anti-stuck time and is moved away walks back;
     * standing at the target is progress, so it is not teleported at once.
     */
    private static void antiStuckAfterLongStay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal("Clara"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getFood().setMorningDay(DayClock.day(level));
        JobAssignments.assign(data, clerk, settler.getUUID(), "Clara", settlement.id(), List.of());
        settler.addJob(new SettlerJob(ModJobs.CLERK.get(), clerk));
        double[] jump = {0.0};
        Vec3[] last = {null};
        helper.onEachTick(() -> {
            Vec3 now = settler.position();
            if (last[0] == null) {
                return;
            }
            jump[0] = Math.max(jump[0], now.distanceTo(last[0]));
            last[0] = now;
        });
        int stay = com.gilfort.civmod.config.CivModConfig.ANTI_STUCK_SECONDS.get() * 20 + 100;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.position().distanceTo(Vec3.atBottomCenterOf(clerk)) < 2.5,
                        "At the counter: " + settler.statusText().getString()))
                // A first push: the walk back is measured (as on any longer walk to the counter).
                .thenExecute(() -> {
                    Vec3 away = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(7, 1, 7)));
                    settler.teleportTo(away.x, away.y, away.z);
                    settler.getNavigation().stop();
                })
                .thenIdle(com.gilfort.civmod.job.SettlerWork.DECISION_INTERVAL + 10)
                .thenWaitUntil(() -> helper.assertTrue(settler.position().distanceTo(Vec3.atBottomCenterOf(clerk)) < 2.5,
                        "Back at the counter: " + settler.statusText().getString()))
                .thenIdle(stay)
                .thenExecute(() -> {
                    // Pushed away (here: moved) after a long stay at the counter.
                    Vec3 away = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(7, 1, 7)));
                    settler.teleportTo(away.x, away.y, away.z);
                    settler.getNavigation().stop();
                    jump[0] = 0.0;
                    last[0] = away;
                })
                .thenWaitUntil(() -> helper.assertTrue(settler.position().distanceTo(Vec3.atBottomCenterOf(clerk)) < 2.5,
                        "Walks back to the counter: at " + helper.relativePos(settler.blockPosition())))
                .thenExecute(() -> {
                    helper.assertTrue(jump[0] < 1.0, "Walked back, not teleported: largest step " + jump[0]);
                    JobAssignments.unassign(data, clerk);
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * An Adventurer without a mission next to its Mission Board, fed and done for the day (it only goes to bed), with a
     * bed or without one (straw bed).
     */
    private static Settler sleeper(GameTestHelper helper, boolean bed) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        LumberjackGameTests.forceChunks(helper);
        helper.setBlock(BOARD, ModBlocks.MISSION_BOARD.get());
        BlockPos board = helper.absolutePos(BOARD);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(board).orElseThrow();
        if (bed) {
            BlockPos head = helper.absolutePos(BED);
            BlockState state = Blocks.BED.pick(DyeColor.BLUE).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(head.relative(Direction.SOUTH), state.setValue(BedBlock.PART, BedPart.FOOT), flags);
            level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), flags);
        }
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 1, 4)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal("Sleeper"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        long day = DayClock.day(level);
        // Fed and done for the day: it only goes to bed.
        settler.getFood().setMorningDay(day);
        settler.getFood().setEatenDay(day);
        JobAssignments.assign(data, board, settler.getUUID(), "Sleeper", settlement.id(), List.of());
        settler.addJob(new SettlerJob(ModJobs.ADVENTURER.get(), board));
        return settler;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static void cleanUp(GameTestHelper helper, Settler settler) {
        ServerLevel level = helper.getLevel();
        JobAssignments.unassign(SettlementData.get(level), helper.absolutePos(BOARD));
        settler.discard();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * An Adventurer without a mission goes to bed (a bed, or a straw bed without one), the night is skipped, it wakes:
     * afterwards it stands on the floor and walks to a spot when told to, without teleporting.
     */
    private static void wakeUp(GameTestHelper helper, boolean bed) {
        ServerLevel level = helper.getLevel();
        Settler settler = sleeper(helper, bed);
        long day = DayClock.day(level);
        double floor = helper.absolutePos(BlockPos.ZERO).getY() + 1.0;
        double[] jump = {0.0};
        Vec3[] last = {null};
        boolean[] watching = {false};

        // Largest step between two ticks once awake (a teleport is a big step).
        helper.onEachTick(() -> {
            Vec3 now = settler.position();
            if (!watching[0] || settler.isSleeping()) {
                last[0] = null;
                return;
            }
            if (last[0] != null) {
                jump[0] = Math.max(jump[0], now.distanceTo(last[0]));
            }
            last[0] = now;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.isSleeping(), "Goes to bed: at " + helper.relativePos(settler.blockPosition())
                        + ", status " + settler.statusText().getString()))
                .thenExecuteAfter(20, () -> {
                    // The player sleeps: the clock jumps to 06:35 of the next day, after every waking time (06:00–06:30).
                    level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD),
                            (day + 1) * DayClock.DAY_TICKS + 600L);
                    watching[0] = true;
                })
                .thenWaitUntil(() -> helper.assertFalse(settler.isSleeping() || settler.isSleepingWithoutBed(), "Wakes up"))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(settler.getPose() == Pose.STANDING, "Standing: " + settler.getPose());
                    helper.assertTrue(settler.getGravity() > 0.0, "Gravity again");
                    helper.assertTrue(settler.onGround() && Math.abs(settler.getY() - floor) < 0.01,
                            "On the floor, not floating: " + (settler.getY() - floor) + " above the floor, on ground " + settler.onGround());
                    helper.assertFalse(level.getBlockState(settler.blockPosition()).getBlock() instanceof BedBlock, "Not on the bed");
                })
                .thenWaitUntil(() -> {
                    if (settler.getNavigation().isDone()) {
                        BlockPos goal = helper.absolutePos(GOAL);
                        settler.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 0.6);
                    }
                    helper.assertTrue(settler.position().distanceTo(Vec3.atBottomCenterOf(helper.absolutePos(GOAL))) < 1.5,
                            "Walks after waking up: at " + helper.relativePos(settler.blockPosition()));
                })
                .thenExecute(() -> {
                    helper.assertTrue(jump[0] < 1.0, "No teleport after waking up: largest step " + jump[0]);
                    cleanUp(helper, settler);
                })
                .thenSucceed();
    }

    /**
     * A settler saved while lying on straw (no gravity while it lies there) has gravity again after loading and waking:
     * the save keeps no NoGravity flag, and a flag older saves wrote is dropped on load. Before, such settlers floated on
     * the block edge, found no path (never on the ground) and were teleported by the anti-stuck rule instead of walking.
     */
    private static void savedOnStraw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos spot = helper.absolutePos(new BlockPos(1, 1, 1));
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.0F, 0.0F);
        settler.sleepOnStraw(new com.gilfort.civmod.npc.SettlerBeds.StrawSpot(spot, Direction.NORTH), true);
        helper.assertTrue(settler.getGravity() == 0.0, "No gravity while lying on straw");

        net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        net.minecraft.nbt.CompoundTag tag = output.buildResult();
        helper.assertFalse(tag.getBooleanOr("NoGravity", false), "The lying state is not saved as a NoGravity flag");

        // An older save that has the flag: dropped on load.
        tag.putBoolean("NoGravity", true);
        Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.LOAD);
        loaded.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
        loaded.wakeUp();
        helper.assertFalse(loaded.isNoGravity(), "No NoGravity flag after loading");
        helper.assertTrue(loaded.getGravity() > 0.0, "Gravity again after loading and waking");
        settler.discard();
        loaded.discard();
        helper.succeed();
    }
}
