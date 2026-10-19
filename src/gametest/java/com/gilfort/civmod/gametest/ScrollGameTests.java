package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.JobAssignments.PointResult;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Story 4 (Scroll of Assignment: work points and job assignment).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ScrollGameTests {

    private static final BlockPos JOB_BLOCK = new BlockPos(0, 64, 0);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private ScrollGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "scroll_point_cycling", ScrollGameTests::pointCycling);
            register(helper, "scroll_work_place_validity", ScrollGameTests::workPlaceValidity);
            register(helper, "scroll_assignment_rules", ScrollGameTests::assignmentRules);
            register(helper, "scroll_unassign_and_removal", ScrollGameTests::unassignAndRemoval);
            register(helper, "scroll_points_persistence", ScrollGameTests::pointsPersistence);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static SettlementManager.Placement place(SettlementData data, BlockPos pos, Job jobType) {
        return SettlementManager.place(data, pos, jobType, 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
    }

    private static SettlementBlock block(SettlementData data, BlockPos pos) {
        return data.settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow();
    }

    /** Cycling: work → removed (no container); collect → drop-off → removed; limits, border and own JobBlock. */
    private static void pointCycling(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        place(data, JOB_BLOCK, ModJobs.LUMBERJACK.get());
        BlockPos log = new BlockPos(3, 64, 0);
        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, log, true, false) == PointResult.WORK, "Log should become a work place");
        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, log, true, false) == PointResult.REMOVED, "Non-container work place should be removed next");

        BlockPos chest = new BlockPos(2, 64, 2);
        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, chest, false, true) == PointResult.COLLECT, "Chest should become collect");
        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, chest, false, true) == PointResult.DROP_OFF, "Then drop-off");
        helper.assertTrue(block(data, JOB_BLOCK).collect().isEmpty(), "Collect must move to drop-off, not stay");
        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, chest, false, true) == PointResult.REMOVED, "Then removed");

        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, JOB_BLOCK, true, true) == PointResult.COLLECT,
                "Own JobBlock may be collect but never a work place");
        BlockPos otherChest = new BlockPos(-2, 64, 2);
        JobAssignments.cyclePoint(data, JOB_BLOCK, otherChest, false, true);
        helper.assertTrue(block(data, JOB_BLOCK).collect().filter(otherChest::equals).isPresent(), "A new collect replaces the previous one");

        for (int i = 0; i < SettlementBlock.MAX_WORK_POINTS; i++) {
            helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(1, 64 + i, 4), true, false) == PointResult.WORK, "Work " + i);
        }
        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(1, 80, 4), true, false) == PointResult.TOO_MANY, "Limit of 8");
        helper.assertTrue(block(data, JOB_BLOCK).workPoints().get(0).equals(new BlockPos(1, 64, 4)), "First work place is primary");
        JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(1, 64, 4), true, false);
        helper.assertTrue(block(data, JOB_BLOCK).workPoints().get(0).equals(new BlockPos(1, 65, 4)), "Next work place becomes primary");

        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(40, 64, 0), true, true) == PointResult.OUTSIDE, "Outside border");
        helper.assertTrue(JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(0, 64, 5), false, false) == PointResult.INVALID, "Invalid target");
        helper.succeed();
    }

    /** Work place validity per job and container detection in the world. */
    private static void workPlaceValidity(GameTestHelper helper) {
        BlockPos log = new BlockPos(0, 1, 0);
        BlockPos stone = new BlockPos(1, 1, 0);
        BlockPos ore = new BlockPos(2, 1, 0);
        BlockPos water = new BlockPos(3, 1, 0);
        BlockPos chest = new BlockPos(4, 1, 0);
        helper.setBlock(log, Blocks.OAK_LOG);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(ore, Blocks.IRON_ORE);
        helper.setBlock(water, Blocks.WATER);
        helper.setBlock(chest, Blocks.CHEST);
        var level = helper.getLevel();
        helper.assertTrue(ScrollOfAssignmentItem.isValidWorkPlace(level, helper.absolutePos(log), ModJobs.LUMBERJACK.get()), "Log for Lumberjack");
        helper.assertFalse(ScrollOfAssignmentItem.isValidWorkPlace(level, helper.absolutePos(stone), ModJobs.LUMBERJACK.get()), "Stone not for Lumberjack");
        helper.assertTrue(ScrollOfAssignmentItem.isValidWorkPlace(level, helper.absolutePos(stone), ModJobs.MINER.get()), "Stone for Miner");
        helper.assertTrue(ScrollOfAssignmentItem.isValidWorkPlace(level, helper.absolutePos(ore), ModJobs.MINER.get()), "Ore for Miner");
        helper.assertTrue(ScrollOfAssignmentItem.isValidWorkPlace(level, helper.absolutePos(water), ModJobs.FISHERMAN.get()), "Water for Fisherman");
        helper.assertFalse(ScrollOfAssignmentItem.isValidWorkPlace(level, helper.absolutePos(log), ModJobs.FISHERMAN.get()), "Log not for Fisherman");
        helper.assertTrue(ScrollOfAssignmentItem.isContainer(level, helper.absolutePos(chest)), "Chest is a container");
        helper.assertFalse(ScrollOfAssignmentItem.isContainer(level, helper.absolutePos(stone)), "Stone is no container");
        helper.succeed();
    }

    /** Assignment rules: ok, occupied, duplicate, unconnected, other settlement. */
    private static void assignmentRules(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = place(data, JOB_BLOCK, ModJobs.LUMBERJACK.get()).settlement();
        BlockPos second = new BlockPos(5, 64, 0);
        place(data, second, ModJobs.LUMBERJACK.get());
        BlockPos unconnected = new BlockPos(20, 64, 0);
        place(data, unconnected, ModJobs.MINER.get());

        helper.assertTrue(JobAssignments.assign(data, JOB_BLOCK, ALICE, "Alice", settlement.id(), List.of()) == AssignResult.OK, "Alice assigned");
        helper.assertTrue(JobAssignments.assign(data, JOB_BLOCK, BOB, "Bob", settlement.id(), List.of()) == AssignResult.OCCUPIED, "Occupied");
        helper.assertTrue(block(data, JOB_BLOCK).assignees().get(0).name().equals("Alice"), "Assignee name stored");
        helper.assertTrue(JobAssignments.assign(data, second, ALICE, "Alice", settlement.id(), List.of(ModJobs.LUMBERJACK.get())) == AssignResult.DUPLICATE,
                "Alice already is a Lumberjack");
        helper.assertTrue(JobAssignments.assign(data, unconnected, BOB, "Bob", settlement.id(), List.of()) == AssignResult.UNCONNECTED, "Unconnected");
        helper.assertTrue(JobAssignments.assign(data, second, BOB, "Bob", -1, List.of()) == AssignResult.OTHER_SETTLEMENT, "Homeless settler");
        helper.assertTrue(JobAssignments.assign(data, second, BOB, "Bob", settlement.id(), List.of()) == AssignResult.OK, "Bob takes the second block");
        helper.succeed();
    }

    /** Unassign frees the block; removing an assigned JobBlock reports the settler that loses the job. */
    private static void unassignAndRemoval(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = place(data, JOB_BLOCK, ModJobs.LUMBERJACK.get()).settlement();
        BlockPos second = new BlockPos(5, 64, 0);
        place(data, second, ModJobs.MINER.get());
        JobAssignments.assign(data, JOB_BLOCK, ALICE, "Alice", settlement.id(), List.of());
        helper.assertTrue(JobAssignments.unassign(data, JOB_BLOCK).equals(List.of(ALICE)), "Unassign returns the settler");
        helper.assertTrue(block(data, JOB_BLOCK).assignees().isEmpty(), "Block is free again");

        JobAssignments.assign(data, second, BOB, "Bob", settlement.id(), List.of());
        List<UUID> lostJob = new ArrayList<>();
        SettlementManager.remove(data, second, new SettlementManager.Listener() {
            @Override
            public void blockChanged(BlockPos pos, int settlementId, boolean connected) {
            }

            @Override
            public void autoConnected(BlockPos pos) {
            }

            @Override
            public void jobRemoved(UUID settler, BlockPos jobBlock) {
                lostJob.add(settler);
            }
        });
        helper.assertTrue(lostJob.equals(List.of(BOB)), "Bob should lose the job of the removed JobBlock: " + lostJob);
        helper.assertFalse(JobAssignments.isAssignee(data, second, BOB), "No assignment left at the removed block");
        helper.succeed();
    }

    /** Points and assignment survive a save / load round trip. */
    private static void pointsPersistence(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = place(data, JOB_BLOCK, ModJobs.LUMBERJACK.get()).settlement();
        JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(3, 64, 0), true, false);
        JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(2, 64, 2), false, true);
        JobAssignments.cyclePoint(data, JOB_BLOCK, new BlockPos(2, 64, 2), false, true);
        JobAssignments.assign(data, JOB_BLOCK, ALICE, "Alice", settlement.id(), List.of());
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        SettlementBlock copy = block(loaded, JOB_BLOCK);
        helper.assertTrue(copy.workPoints().equals(List.of(new BlockPos(3, 64, 0))), "Work points lost");
        helper.assertTrue(copy.dropOff().filter(new BlockPos(2, 64, 2)::equals).isPresent() && copy.collect().isEmpty(), "Drop-off lost");
        helper.assertTrue(copy.isAssignee(ALICE), "Assignee lost");
        helper.succeed();
    }
}
