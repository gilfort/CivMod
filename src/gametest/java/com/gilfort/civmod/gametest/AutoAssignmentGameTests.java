package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for automatic job assignment (vault {@code Systems/Scroll of Assignment.md}): a toggle per offered Job on a
 * JobBlock lets jobless settlers take its free places when it is switched on and every morning at 06:00.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AutoAssignmentGameTests {

    private static final BlockPos LUMBERJACK = new BlockPos(1, 1, 1);
    private static final BlockPos MINER = new BlockPos(5, 1, 1);
    private static final BlockPos CLERK = new BlockPos(1, 1, 5);
    private static final long DAY = 24000L;

    private AutoAssignmentGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "auto_assign_default_off", AutoAssignmentGameTests::defaultOff);
            register(helper, "auto_assign_toggle_on", AutoAssignmentGameTests::toggleOn);
            register(helper, "auto_assign_highest_skill", AutoAssignmentGameTests::highestSkill);
            register(helper, "auto_assign_freed_place", AutoAssignmentGameTests::freedPlace);
            register(helper, "auto_assign_catch_up", AutoAssignmentGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Places a JobBlock on a stone floor and returns its settlement. */
    private static Settlement jobBlock(GameTestHelper helper, BlockPos pos, net.minecraft.world.level.block.Block block) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(pos, block);
        return SettlementData.get(helper.getLevel()).settlementAt(helper.absolutePos(pos)).orElseThrow();
    }

    private static void level(GameTestHelper helper, BlockPos pos, int level) {
        SettlementManager.setLevel(SettlementData.get(helper.getLevel()), helper.absolutePos(pos), level, SettlementManager.Listener.NONE);
    }

    /** A settler of the settlement at a relative position, jobless. */
    private static Settler settler(GameTestHelper helper, Settlement settlement, String name, BlockPos at) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(at));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal(name));
        level.addFreshEntity(settler);
        settler.joinSettlement(SettlementData.get(level), settlement);
        return settler;
    }

    /** Gives a settler a Job by hand (as the job picker does). */
    private static void hire(GameTestHelper helper, Settler settler, BlockPos pos, Job job) {
        SettlementWorld.assign(helper.getLevel(), helper.absolutePos(pos), settler, job);
        helper.assertTrue(holds(helper, settler, pos, job), settler.getName().getString() + " hired as " + job);
    }

    private static boolean holds(GameTestHelper helper, Settler settler, BlockPos pos, Job job) {
        BlockPos abs = helper.absolutePos(pos);
        boolean entity = settler.getJobs().stream().anyMatch(held -> held.jobBlock().equals(abs) && held.jobType() == job);
        boolean block = block(helper, pos).assignee(settler.getUUID()).filter(assignee -> assignee.job() == job).isPresent();
        return entity && block;
    }

    private static SettlementBlock block(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        return SettlementData.get(helper.getLevel()).settlementAt(abs).flatMap(settlement -> settlement.block(abs)).orElseThrow();
    }

    private static void toggle(GameTestHelper helper, BlockPos pos, Job job, boolean on) {
        AutoAssignment.setAuto(helper.getLevel(), helper.absolutePos(pos), job, on);
    }

    private static void discard(Settler... settlers) {
        for (Settler settler : settlers) {
            settler.discard();
        }
    }

    /**
     * Every Job of every JobBlock starts with the toggle off, also when read from an old save; the morning assigns
     * nobody then. A switched-on toggle is saved.
     */
    private static void defaultOff(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, CLERK, ModBlocks.CLERK_BLOCK.get());
        Settler idle = settler(helper, settlement, "Idle", new BlockPos(3, 1, 3));
        helper.assertTrue(JobPlaces.lines(block(helper, CLERK)).stream().noneMatch(JobPlaces.Line::auto),
                "Every Job starts off: " + JobPlaces.lines(block(helper, CLERK)));
        AutoAssignment.morning(helper.getLevel(), settlement);
        helper.assertTrue(idle.getJobs().isEmpty() && block(helper, CLERK).assignees().isEmpty(), "Nobody takes a Job while the toggles are off");

        JsonObject old = SettlementBlock.CODEC.encodeStart(JsonOps.INSTANCE, block(helper, CLERK)).getOrThrow().getAsJsonObject();
        old.remove("auto_assign");
        SettlementBlock read = SettlementBlock.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        helper.assertTrue(JobPlaces.lines(read).stream().noneMatch(JobPlaces.Line::auto), "An old save reads every toggle as off");

        // The idle settler would take a Deliveryman place: remove it, so only the toggle changes.
        idle.discard();
        toggle(helper, CLERK, ModJobs.DELIVERYMAN.get(), true);
        SettlementBlock saved = SettlementBlock.CODEC.parse(JsonOps.INSTANCE,
                SettlementBlock.CODEC.encodeStart(JsonOps.INSTANCE, block(helper, CLERK)).getOrThrow()).getOrThrow();
        helper.assertTrue(JobPlaces.lines(saved).stream().map(line -> line.job().toString() + "=" + line.auto()).toList()
                .equals(List.of(ModJobs.CLERK.get() + "=false", ModJobs.DELIVERYMAN.get() + "=true")), "Saved per Job: " + JobPlaces.lines(saved));
        helper.succeed();
    }

    /**
     * Switching a toggle on assigns a jobless settler at once; a settler that already has a Job is never assigned, even
     * with the higher skill.
     */
    private static void toggleOn(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(MINER, ModBlocks.MINER_BLOCK.get());
        Settler idle = settler(helper, settlement, "Idle", new BlockPos(7, 1, 7));
        Settler miner = settler(helper, settlement, "Miner", new BlockPos(1, 1, 2));
        miner.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(10, 0));
        hire(helper, miner, MINER, ModJobs.MINER.get());

        toggle(helper, LUMBERJACK, ModJobs.LUMBERJACK.get(), true);
        helper.assertTrue(holds(helper, idle, LUMBERJACK, ModJobs.LUMBERJACK.get()), "The jobless settler takes the place: " + idle.getJobs());
        helper.assertTrue(miner.getJobs().size() == 1 && miner.getJobs().get(0).jobType() == ModJobs.MINER.get(),
                "The settler with a Job keeps only its Job: " + miner.getJobs());
        helper.assertTrue(block(helper, LUMBERJACK).assignee(ModJobs.LUMBERJACK.get()).filter(a -> a.id().equals(idle.getUUID())).isPresent(),
                "The block records the jobless settler");
        discard(idle, miner);
        helper.succeed();
    }

    /**
     * The highest skill in the Job wins, ties go to the settler nearest to the JobBlock; places are filled until none is
     * left.
     */
    private static void highestSkill(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, CLERK, ModBlocks.CLERK_BLOCK.get());
        level(helper, CLERK, 2);
        helper.setBlock(LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK.get());
        Job deliveryman = ModJobs.DELIVERYMAN.get();
        Job lumberjack = ModJobs.LUMBERJACK.get();
        Settler weak = settler(helper, settlement, "Weak", new BlockPos(1, 1, 6));
        Settler farStrong = settler(helper, settlement, "FarStrong", new BlockPos(8, 1, 0));
        Settler nearStrong = settler(helper, settlement, "NearStrong", new BlockPos(3, 1, 5));
        weak.getSkills().set(deliveryman, new SettlerSkills.Skill(3, 0));
        farStrong.getSkills().set(deliveryman, new SettlerSkills.Skill(5, 0));
        nearStrong.getSkills().set(deliveryman, new SettlerSkills.Skill(5, 0));

        toggle(helper, CLERK, deliveryman, true);
        helper.assertTrue(holds(helper, farStrong, CLERK, deliveryman) && holds(helper, nearStrong, CLERK, deliveryman),
                "Both Deliveryman places go to the higher skills: " + block(helper, CLERK).assignees());
        helper.assertTrue(weak.getJobs().isEmpty(), "No place left for the weaker settler");
        helper.assertTrue(block(helper, CLERK).assignee(ModJobs.CLERK.get()).isEmpty(), "The Clerk place stays free: its toggle is off");

        Settler farTie = settler(helper, settlement, "FarTie", new BlockPos(8, 1, 8));
        Settler nearTie = settler(helper, settlement, "NearTie", new BlockPos(2, 1, 1));
        farTie.getSkills().set(lumberjack, new SettlerSkills.Skill(2, 0));
        nearTie.getSkills().set(lumberjack, new SettlerSkills.Skill(2, 0));
        toggle(helper, LUMBERJACK, lumberjack, true);
        helper.assertTrue(holds(helper, nearTie, LUMBERJACK, lumberjack), "Equal skills: the settler nearest to the JobBlock wins: "
                + block(helper, LUMBERJACK).assignees());
        helper.assertTrue(farTie.getJobs().isEmpty() && weak.getJobs().isEmpty(), "The others stay jobless");
        discard(weak, farStrong, nearStrong, farTie, nearTie);
        helper.succeed();
    }

    /** A place freed while the toggle is on stays free until the next morning, which fills it again. */
    private static void freedPlace(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK.get());
        Settler settler = settler(helper, settlement, "Settler", new BlockPos(4, 1, 4));
        Job lumberjack = ModJobs.LUMBERJACK.get();
        toggle(helper, LUMBERJACK, lumberjack, true);
        helper.assertTrue(holds(helper, settler, LUMBERJACK, lumberjack), "Assigned when switched on");
        SettlementWorld.unassign(helper.getLevel(), helper.absolutePos(LUMBERJACK));
        helper.assertTrue(settler.getJobs().isEmpty(), "Place freed");
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(settler.getJobs().isEmpty() && block(helper, LUMBERJACK).assignees().isEmpty(),
                    "The freed place stays free until the morning: " + settler.getJobs());
            AutoAssignment.morning(helper.getLevel(), settlement);
            helper.assertTrue(holds(helper, settler, LUMBERJACK, lumberjack), "The morning fills it again");
            settler.discard();
            helper.succeed();
        });
    }

    /**
     * A settler that lost its Job is not assigned then; a catch-up over 06:00 assigns it like the live morning.
     */
    private static void catchUp(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(MINER, ModBlocks.MINER_BLOCK.get());
        Settler settler = settler(helper, settlement, "Settler", new BlockPos(4, 1, 4));
        Job lumberjack = ModJobs.LUMBERJACK.get();
        hire(helper, settler, MINER, ModJobs.MINER.get());
        toggle(helper, LUMBERJACK, lumberjack, true);
        helper.assertTrue(block(helper, LUMBERJACK).assignees().isEmpty(), "No jobless settler when switched on");
        SettlementWorld.unassign(helper.getLevel(), helper.absolutePos(MINER));
        helper.assertTrue(settler.getJobs().isEmpty() && block(helper, LUMBERJACK).assignees().isEmpty(),
                "Losing its Job does not assign the settler");

        ServerLevel level = helper.getLevel();
        long clock = level.getOverworldClockTime();
        settler.getWork().setCatchUp(clock - DAY, clock);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(holds(helper, settler, LUMBERJACK, lumberjack), "The missed 06:00 assigns it: " + settler.getJobs());
        helper.assertTrue(settler.getJobs().stream().map(SettlerJob::jobType).toList().equals(List.of(lumberjack)), "Only that Job");
        settler.discard();
        helper.succeed();
    }
}
