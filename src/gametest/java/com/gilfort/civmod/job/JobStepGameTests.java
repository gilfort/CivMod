package com.gilfort.civmod.job;

import java.util.Objects;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the seam between the Settler Shell and the category loops (#100): a loop answers with a
 * {@link JobStep} and leaves the shell untouched; only the shell applies the step (active job, walking target, status).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobStepGameTests {

    /** Day time 08:00. */
    private static final long MORNING = 2000L;

    private JobStepGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "job_step_clerk_walks_to_counter", JobStepGameTests::clerkWalksToCounter);
            register(helper, "job_step_lumberjack_blocked_without_tool", JobStepGameTests::lumberjackBlockedWithoutTool);
            register(helper, "job_step_adventurer_waits_for_departure", JobStepGameTests::adventurerWaitsForDeparture);
            register(helper, "job_step_deliveryman_skips_without_tasks", JobStepGameTests::deliverymanSkipsWithoutTasks);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A Clerk on shift asks to walk to its counter (next to the JobBlock without stand spots); the shell applies it. */
    private static void clerkWalksToCounter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        Settler settler = settler(helper, clerk, new BlockPos(5, 1, 5));
        SettlerJob job = new SettlerJob(ModJobs.CLERK.get(), clerk);
        settler.addJob(job);
        SettlerWork work = settler.getWork();
        JobBlockSite site = JobBlockSite.at(level, clerk).orElseThrow();
        long day = DayClock.day(level);

        JobStep step = Objects.requireNonNull(work.loop(job)).plan(level, job, site, day, MORNING);

        helper.assertTrue(step instanceof JobStep.Walk walk && walk.target().equals(clerk) && walk.reach() == SettlerWork.REACH
                && walk.status() == JobStatus.SERVING, "Walk to the counter, serving: " + step);
        helper.assertTrue(work.activeJob().isEmpty(), "The loop does not set the active job");
        helper.assertTrue(work.status(clerk) == JobStatus.IDLE, "The loop does not set the status: " + work.status(clerk));

        helper.assertTrue(work.apply(level, job, step), "The step keeps the settler busy");
        helper.assertTrue(work.activeJob().filter(job::equals).isPresent(), "The shell sets the active job");
        helper.assertTrue(work.status(clerk) == JobStatus.SERVING, "The shell sets the status: " + work.status(clerk));
        settler.discard();
        helper.succeed();
    }

    /** A Lumberjack without an axe on its belt or in its collect inventory answers "blocked: no tool". */
    private static void lumberjackBlockedWithoutTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        Settler settler = settler(helper, jobBlock, new BlockPos(3, 1, 3));
        SettlerJob job = new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock);
        settler.addJob(job);
        SettlerWork work = settler.getWork();
        JobBlockSite site = JobBlockSite.at(level, jobBlock).orElseThrow();

        JobStep step = Objects.requireNonNull(work.loop(job)).plan(level, job, site, DayClock.day(level), MORNING);

        helper.assertTrue(step instanceof JobStep.Blocked blocked && blocked.status() == JobStatus.NO_TOOL && blocked.reason() == null,
                "Blocked without a tool: " + step);
        helper.assertTrue(work.status(jobBlock) == JobStatus.IDLE, "The loop does not set the status: " + work.status(jobBlock));
        helper.assertFalse(work.apply(level, job, step), "A blocked job leaves the settler to its next priority");
        helper.assertTrue(work.status(jobBlock) == JobStatus.NO_TOOL, "The shell blocks the job: " + work.status(jobBlock));
        helper.assertTrue(work.workplace(jobBlock).isBlocked(level.getGameTime()), "Retried after a while");
        settler.discard();
        helper.succeed();
    }

    /**
     * An Adventurer with a reserved mission after the departure time waits at the board: the loop answers "wait", the
     * shell keeps it busy with the job, standing.
     */
    private static void adventurerWaitsForDeparture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.MISSION_BOARD.get());
        BlockPos board = helper.absolutePos(new BlockPos(1, 1, 1));
        Settler settler = settler(helper, board, new BlockPos(3, 1, 3));
        SettlerJob job = new SettlerJob(ModJobs.ADVENTURER.get(), board);
        settler.addJob(job);
        SettlerWork work = settler.getWork();
        work.missions().reserve(board, CivMod.id("job_step_test"), false);
        JobBlockSite site = JobBlockSite.at(level, board).orElseThrow();

        JobStep step = Objects.requireNonNull(work.loop(job)).plan(level, job, site, DayClock.day(level), MissionLoop.DEPARTURE_END);

        helper.assertTrue(step instanceof JobStep.Wait wait && wait.status() == JobStatus.WAITING_DEPARTURE, "Waits for the departure: " + step);
        helper.assertTrue(work.activeJob().isEmpty(), "The loop does not set the active job");
        helper.assertTrue(work.apply(level, job, step), "Waiting keeps the settler busy with the job");
        helper.assertTrue(work.activeJob().filter(job::equals).isPresent(), "The shell sets the active job");
        settler.discard();
        helper.succeed();
    }

    /** A Deliveryman without delivery tasks answers "skip: no pickups"; the shell sets the status. */
    private static void deliverymanSkipsWithoutTasks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        BlockPos warehouse = helper.absolutePos(new BlockPos(1, 1, 1));
        Settler settler = settler(helper, warehouse, new BlockPos(3, 1, 3));
        SettlerJob job = new SettlerJob(ModJobs.DELIVERYMAN.get(), warehouse);
        settler.addJob(job);
        SettlerWork work = settler.getWork();
        JobBlockSite site = JobBlockSite.at(level, warehouse).orElseThrow();

        JobStep step = Objects.requireNonNull(work.loop(job)).plan(level, job, site, DayClock.day(level), MORNING);

        helper.assertTrue(step instanceof JobStep.Skip skip && skip.status().filter(JobStatus.NO_PICKUPS::equals).isPresent(),
                "Nothing to carry: " + step);
        helper.assertTrue(work.status(warehouse) == JobStatus.IDLE, "The loop does not set the status: " + work.status(warehouse));
        helper.assertFalse(work.apply(level, job, step), "The next priority works");
        helper.assertTrue(work.status(warehouse) == JobStatus.NO_PICKUPS, "The shell sets the status: " + work.status(warehouse));
        settler.discard();
        helper.succeed();
    }

    /** A settler of the JobBlock's settlement at a relative position, without AI. */
    private static Settler settler(GameTestHelper helper, BlockPos jobBlock, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        return settler;
    }
}
