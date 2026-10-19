package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for JobBlocks with several Jobs and the job picker (vault {@code Systems/JobBlock.md},
 * {@code Systems/Scroll of Assignment.md}, {@code Jobs/Deliveryman.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobPickerGameTests {

    private static final BlockPos CLERK = new BlockPos(0, 64, 0);
    private static final BlockPos LUMBERJACK = new BlockPos(5, 64, 0);
    private static final BlockPos BOARD = new BlockPos(0, 64, 5);
    private static final UUID[] SETTLERS = new UUID[12];

    static {
        for (int i = 0; i < SETTLERS.length; i++) {
            SETTLERS[i] = new UUID(0L, 0x300L + i);
        }
    }

    private JobPickerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "job_picker_places_per_level", JobPickerGameTests::placesPerLevel);
            register(helper, "job_picker_full_job", JobPickerGameTests::fullJob);
            register(helper, "job_picker_one_job_per_block", JobPickerGameTests::oneJobPerBlock);
            register(helper, "job_picker_mission_board", JobPickerGameTests::missionBoard);
            register(helper, "job_picker_clerk_assignee", JobPickerGameTests::clerkAssignee);
            register(helper, "job_picker_persistence", JobPickerGameTests::persistence);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Settlement place(SettlementData data, BlockPos pos, Job jobType, int level) {
        return SettlementManager.place(data, pos, jobType, level, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE).settlement();
    }

    private static SettlementBlock block(SettlementData data, BlockPos pos) {
        return data.settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow();
    }

    private static AssignResult assign(SettlementData data, Settlement settlement, BlockPos pos, int settler, Job job) {
        return JobAssignments.assign(data, pos, SETTLERS[settler], "Settler " + settler, settlement.id(), List.of(), job);
    }

    private static Job specialization() {
        return ModJobs.REGISTRY.getValue(CivMod.id("gametest_mission_specialization"));
    }

    /** The Clerk JobBlock offers 1 Clerk and 1 / 2 / 4 / 6 / 8 Deliverymen; other blocks their Job with one place. */
    private static void placesPerLevel(GameTestHelper helper) {
        Job clerk = ModJobs.CLERK.get();
        Job deliveryman = ModJobs.DELIVERYMAN.get();
        int[] deliverymen = {1, 2, 4, 6, 8};
        for (int level = SettlementRules.MIN_LEVEL; level <= SettlementRules.MAX_LEVEL; level++) {
            SettlementData data = new SettlementData();
            place(data, CLERK, clerk, level);
            List<JobPlaces.Line> lines = JobPlaces.lines(block(data, CLERK));
            List<JobPlaces.Line> expected = List.of(new JobPlaces.Line(clerk, 0, 1), new JobPlaces.Line(deliveryman, 0, deliverymen[level - 1]));
            helper.assertTrue(lines.equals(expected), "Clerk JobBlock at level " + level + ": " + lines);
        }
        SettlementData data = new SettlementData();
        place(data, LUMBERJACK, ModJobs.LUMBERJACK.get(), 5);
        helper.assertTrue(JobPlaces.lines(block(data, LUMBERJACK)).equals(List.of(new JobPlaces.Line(ModJobs.LUMBERJACK.get(), 0, 1))),
                "Lumberjack Block: " + JobPlaces.lines(block(data, LUMBERJACK)));
        helper.succeed();
    }

    /**
     * A full Job refuses further settlers while the other Job of the block still takes them; extra Deliverymen after a
     * downgrade are kept and the Job stays full until enough leave.
     */
    private static void fullJob(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Job clerk = ModJobs.CLERK.get();
        Job deliveryman = ModJobs.DELIVERYMAN.get();
        Settlement settlement = place(data, CLERK, clerk, 2);
        helper.assertTrue(assign(data, settlement, CLERK, 0, deliveryman) == AssignResult.OK, "First Deliveryman");
        helper.assertTrue(assign(data, settlement, CLERK, 1, deliveryman) == AssignResult.OK, "Second Deliveryman");
        helper.assertTrue(assign(data, settlement, CLERK, 2, deliveryman) == AssignResult.FULL, "Level 2 holds two Deliverymen");
        helper.assertTrue(assign(data, settlement, CLERK, 2, clerk) == AssignResult.OK, "The Clerk place is still free");
        helper.assertTrue(assign(data, settlement, CLERK, 3, clerk) == AssignResult.OCCUPIED, "One Clerk per block");
        helper.assertTrue(JobPlaces.lines(block(data, CLERK)).equals(List.of(new JobPlaces.Line(clerk, 1, 1), new JobPlaces.Line(deliveryman, 2, 2))),
                "Taken / places: " + JobPlaces.lines(block(data, CLERK)));
        helper.assertTrue(JobPlaces.lines(block(data, CLERK)).get(1).full(), "Deliveryman 2/2 is full");

        SettlementManager.setLevel(data, CLERK, 1, SettlementManager.Listener.NONE);
        helper.assertTrue(block(data, CLERK).assignees().size() == 3, "A downgrade keeps every assignee");
        helper.assertTrue(JobPlaces.lines(block(data, CLERK)).get(1).equals(new JobPlaces.Line(deliveryman, 2, 1)), "Deliveryman 2/1 after the downgrade");
        JobAssignments.unassign(data, CLERK, SETTLERS[0]);
        helper.assertTrue(assign(data, settlement, CLERK, 4, deliveryman) == AssignResult.OCCUPIED, "1/1 is still taken");
        JobAssignments.unassign(data, CLERK, SETTLERS[1]);
        helper.assertTrue(assign(data, settlement, CLERK, 4, deliveryman) == AssignResult.OK, "A place is free again");
        helper.succeed();
    }

    /** A settler holds one Job per block; a Job the block does not offer (at its level) is refused. */
    private static void oneJobPerBlock(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = place(data, CLERK, ModJobs.CLERK.get(), 3);
        place(data, LUMBERJACK, ModJobs.LUMBERJACK.get(), 1);
        helper.assertTrue(assign(data, settlement, CLERK, 0, ModJobs.CLERK.get()) == AssignResult.OK, "Clerk assigned");
        helper.assertTrue(assign(data, settlement, CLERK, 0, ModJobs.DELIVERYMAN.get()) == AssignResult.SAME_BLOCK,
                "The Clerk cannot also be a Deliveryman of its own block");
        helper.assertTrue(block(data, CLERK).assignees().size() == 1, "Nothing added");
        helper.assertTrue(assign(data, settlement, LUMBERJACK, 1, ModJobs.DELIVERYMAN.get()) == AssignResult.NOT_OFFERED,
                "A Lumberjack Block offers no Deliveryman");
        helper.assertTrue(JobAssignments.assign(data, CLERK, SETTLERS[2], "Settler 2", settlement.id(), List.of(ModJobs.DELIVERYMAN.get()),
                ModJobs.DELIVERYMAN.get()) == AssignResult.DUPLICATE, "A settler holds a Job once");
        helper.succeed();
    }

    /** The Mission Job is chosen when joining the board; all Mission Jobs share the board's places. */
    private static void missionBoard(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Job adventurer = ModJobs.ADVENTURER.get();
        Job specialization = specialization();
        Settlement settlement = place(data, BOARD, adventurer, 1);
        helper.assertTrue(assign(data, settlement, BOARD, 0, specialization) == AssignResult.NOT_OFFERED, "Specializations from level 2");
        SettlementManager.setLevel(data, BOARD, 2, SettlementManager.Listener.NONE);
        helper.assertTrue(assign(data, settlement, BOARD, 0, specialization) == AssignResult.OK, "Joins as the Specialization");
        helper.assertTrue(block(data, BOARD).assignees().get(0).job() == specialization, "The assignee records its Mission Job");
        // Other Mission Jobs (the example addon's Scout) share the places too; only the two used here are compared.
        helper.assertTrue(JobPlaces.lines(block(data, BOARD)).stream().filter(line -> line.job() == adventurer || line.job() == specialization).toList()
                .equals(List.of(new JobPlaces.Line(adventurer, 1, 2), new JobPlaces.Line(specialization, 1, 2))),
                "Shared places: " + JobPlaces.lines(block(data, BOARD)));
        helper.assertTrue(assign(data, settlement, BOARD, 1, adventurer) == AssignResult.OK, "Second settler as Adventurer");
        helper.assertTrue(assign(data, settlement, BOARD, 2, specialization) == AssignResult.FULL, "The board is full for every Mission Job");
        helper.succeed();
    }

    /** The Deliveryman is registered; code that needs the Clerk finds the Clerk assignee, not the first one. */
    private static void clerkAssignee(GameTestHelper helper) {
        Job deliveryman = ModJobs.DELIVERYMAN.get();
        helper.assertTrue(deliveryman.category() == JobCategory.LOGISTICS && deliveryman.parent().isEmpty(), "Deliveryman is a Logistics Base Job");
        helper.assertTrue(deliveryman.defaultHungerFactor() == 1.0 && deliveryman.defaultBaseExp() == 1, "Hunger factor 1.0, base EXP 1");

        SettlementData data = new SettlementData();
        Settlement settlement = place(data, CLERK, ModJobs.CLERK.get(), 1);
        assign(data, settlement, CLERK, 0, deliveryman);
        long noon = 6000L;
        helper.assertTrue(block(data, CLERK).assignee(ModJobs.CLERK.get()).isEmpty(), "No Clerk yet");
        helper.assertFalse(ClerkStorage.working(block(data, CLERK), 0L, noon), "A Deliveryman does not open the counter");
        assign(data, settlement, CLERK, 1, ModJobs.CLERK.get());
        helper.assertTrue(block(data, CLERK).assignee(ModJobs.CLERK.get()).map(SettlementBlock.Assignee::id).filter(SETTLERS[1]::equals).isPresent(),
                "The Clerk assignee is the second one");
        helper.assertTrue(ClerkStorage.working(block(data, CLERK), 0L, noon), "The Clerk works at noon");
        helper.succeed();
    }

    /** Assignees keep their Job through a save; saves without a Job read the block's Base Job. */
    private static void persistence(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = place(data, CLERK, ModJobs.CLERK.get(), 2);
        assign(data, settlement, CLERK, 0, ModJobs.DELIVERYMAN.get());
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        helper.assertTrue(block(loaded, CLERK).assignees().equals(List.of(new SettlementBlock.Assignee(SETTLERS[0], "Settler 0", ModJobs.DELIVERYMAN.get()))),
                "Assignee job lost: " + block(loaded, CLERK).assignees());

        JsonObject old = SettlementBlock.CODEC.encodeStart(ops, new SettlementBlock(CLERK, ModJobs.CLERK.get(), 1, true)).getOrThrow().getAsJsonObject();
        JsonObject assignee = new JsonObject();
        assignee.add("id", UUIDUtil.CODEC.encodeStart(ops, SETTLERS[1]).getOrThrow());
        assignee.addProperty("name", "Settler 1");
        com.google.gson.JsonArray assignees = new com.google.gson.JsonArray();
        assignees.add(assignee);
        old.add("assignees", assignees);
        SettlementBlock legacy = SettlementBlock.CODEC.parse(ops, old).getOrThrow();
        helper.assertTrue(legacy.assignees().equals(List.of(new SettlementBlock.Assignee(SETTLERS[1], "Settler 1", ModJobs.CLERK.get()))),
                "Old assignee reads the Base Job: " + legacy.assignees());
        helper.succeed();
    }
}
