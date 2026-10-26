package com.gilfort.civmod.job;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for sitting during work breaks (#112): the settler sits exactly while its job step is a work break, on the
 * ground where it stands.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SittingGameTests {

    private SittingGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "sitting_hook_pose_and_hitbox", SittingGameTests::hookPoseAndHitbox);
            register(helper, "sitting_break_sits_until_it_ends", SittingGameTests::breakSitsUntilItEnds);
            register(helper, "sitting_not_on_ground_stays_standing", SittingGameTests::notOnGroundStaysStanding);
            register(helper, "sitting_in_water_stays_standing", SittingGameTests::inWaterStaysStanding);
            register(helper, "sitting_other_paused_states_stand", SittingGameTests::otherPausedStatesStand);
            register(helper, "sitting_job_removed_stands_up", SittingGameTests::jobRemovedStandsUp);
            register(helper, "sitting_damage_stands_up", SittingGameTests::damageStandsUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Sitting down lowers the hitbox and eye height, keeps the facing and cannot be pushed; standing up undoes it. */
    private static void hookPoseAndHitbox(GameTestHelper helper) {
        Settler settler = lumberjack(helper).settler();

        settler.sitDown(settler.position(), 90.0F);

        helper.assertTrue(settler.hasPose(Pose.SITTING), "Sits: " + settler.getPose());
        helper.assertTrue(Math.abs(settler.getBbHeight() - 1.3F) < 0.05F, "Hitbox about 1.3 blocks high: " + settler.getBbHeight());
        helper.assertTrue(settler.getEyeHeight() < 1.3F, "Eyes follow the head down: " + settler.getEyeHeight());
        helper.assertTrue(settler.yBodyRot == 90.0F, "Body faces the seat direction: " + settler.yBodyRot);
        helper.assertFalse(settler.isPushable(), "A sitting settler cannot be pushed");

        settler.standUp();

        helper.assertTrue(settler.hasPose(Pose.STANDING), "Stands: " + settler.getPose());
        helper.assertTrue(settler.getBbHeight() == 1.8F, "Full height again: " + settler.getBbHeight());
        helper.assertTrue(settler.isPushable(), "Pushable again");
        settler.discard();
        helper.succeed();
    }

    /** A work break sits on the spot, the body keeps its direction; the next other step stands the settler up. */
    private static void breakSitsUntilItEnds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Working working = lumberjack(helper);
        Settler settler = working.settler();
        SettlerWork work = settler.getWork();
        Vec3 spot = settler.position();
        settler.setYRot(45.0F);

        decide(work, level, working.job(), new JobStep.Pause(JobStatus.PAUSING));

        helper.assertTrue(settler.isSitting(), "The work break sits");
        helper.assertTrue(settler.position().equals(spot), "On the spot: " + settler.position());
        helper.assertTrue(settler.yBodyRot == 45.0F, "Facing the way it stood: " + settler.yBodyRot);

        decide(work, level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
        helper.assertTrue(settler.isSitting(), "Sits on while the break goes on");

        decide(work, level, working.job(), JobStep.skip());
        helper.assertFalse(settler.isSitting(), "The break is over: stands up");
        settler.discard();
        helper.succeed();
    }

    /** Not on the ground when the break starts: stands through the whole break, even once it lands. */
    private static void notOnGroundStaysStanding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Working working = lumberjack(helper);
        Settler settler = working.settler();
        settler.setOnGround(false);

        decide(settler.getWork(), level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
        helper.assertFalse(settler.isSitting(), "In the air: stands");

        settler.setOnGround(true);
        decide(settler.getWork(), level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
        helper.assertFalse(settler.isSitting(), "Decided once at the start of the break: no re-check");

        decide(settler.getWork(), level, working.job(), JobStep.skip());
        decide(settler.getWork(), level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
        helper.assertTrue(settler.isSitting(), "The next break on the ground sits");
        settler.discard();
        helper.succeed();
    }

    /** In water when the break starts: stands. */
    private static void inWaterStaysStanding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Working working = lumberjack(helper);
        Settler settler = working.settler();
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.WATER);
        // The fluid state is updated by the entity's own tick.
        helper.runAfterDelay(3, () -> {
            settler.setOnGround(true);
            decide(settler.getWork(), level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
            helper.assertTrue(settler.isInLiquid(), "Stands in water");
            helper.assertFalse(settler.isSitting(), "In water: stands");
            settler.discard();
            helper.succeed();
        });
    }

    /** Paused for another reason than a work break ("Drop-off full", "No tool"): stands. */
    private static void otherPausedStatesStand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Working working = lumberjack(helper);
        Settler settler = working.settler();

        decide(settler.getWork(), level, working.job(), JobStep.blocked(JobStatus.DROP_OFF_FULL));
        helper.assertFalse(settler.isSitting(), "Drop-off full: stands");
        decide(settler.getWork(), level, working.job(), JobStep.blocked(JobStatus.NO_TOOL));
        helper.assertFalse(settler.isSitting(), "No tool: stands");
        settler.discard();
        helper.succeed();
    }

    /** The job removed during the break: up at once, not only at the next decision. */
    private static void jobRemovedStandsUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Working working = lumberjack(helper);
        Settler settler = working.settler();
        BlockPos jobBlock = working.job().jobBlock();
        decide(settler.getWork(), level, working.job(), JobStep.walk(jobBlock, SettlerWork.REACH, JobStatus.WORKING));
        decide(settler.getWork(), level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
        helper.assertTrue(settler.isSitting(), "The work break sits");

        settler.removeJob(jobBlock);

        helper.assertFalse(settler.isSitting(), "Job removed: stands up");
        settler.discard();
        helper.succeed();
    }

    /** Damage stands the settler up; it stays standing for the rest of that break. */
    private static void damageStandsUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Working working = lumberjack(helper);
        Settler settler = working.settler();
        decide(settler.getWork(), level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
        helper.assertTrue(settler.isSitting(), "The work break sits");

        settler.hurtServer(level, level.damageSources().generic(), 1.0F);

        helper.assertFalse(settler.isSitting(), "Damage: stands up");
        decide(settler.getWork(), level, working.job(), new JobStep.Pause(JobStatus.PAUSING));
        helper.assertFalse(settler.isSitting(), "Stays standing for the rest of the break");
        settler.discard();
        helper.succeed();
    }

    /** One decision that applies the step (the step a job wants is applied, then the decision ends). */
    private static void decide(SettlerWork work, ServerLevel level, SettlerJob job, JobStep step) {
        work.startDecision();
        work.apply(level, job, step);
        work.endDecision();
    }

    /** A Lumberjack settler of the JobBlock's settlement on a stone floor, without AI, standing on the ground. */
    private static Working lumberjack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 1, 4)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.setOnGround(true);
        SettlerJob job = new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock);
        settler.addJob(job);
        return new Working(settler, job);
    }

    /** A settler and the job it works. */
    private record Working(Settler settler, SettlerJob job) {
    }
}
