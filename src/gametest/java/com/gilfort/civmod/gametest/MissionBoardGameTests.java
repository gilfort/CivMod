package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Mission Board and the Adventurer assignment (vault {@code Systems/Mission Jobs.md}).
 * <p>
 * Registers {@code civmod:gametest_mission_specialization}, a Mission Specialization of the Adventurer, to test which
 * Mission Jobs a board level unlocks.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionBoardGameTests {

    private static final BlockPos BOARD = new BlockPos(0, 64, 0);
    private static final UUID[] SETTLERS = new UUID[11];

    static {
        for (int i = 0; i < SETTLERS.length; i++) {
            SETTLERS[i] = new UUID(0L, 0x100L + i);
        }
    }

    private MissionBoardGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(ModJobs.KEY, helper -> helper.register(CivMod.id("gametest_mission_specialization"), Job.builder(JobCategory.MISSION)
                .parent(ModJobs.ADVENTURER).icon(ModItems.MISSION_BOARD).build()));
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_board_joins_settlement", MissionBoardGameTests::joinsSettlement);
            register(helper, "mission_board_capacity", MissionBoardGameTests::capacity);
            register(helper, "mission_board_one_mission_job", MissionBoardGameTests::oneMissionJob);
            register(helper, "mission_board_unassign_and_removal", MissionBoardGameTests::unassignAndRemoval);
            register(helper, "mission_board_persistence", MissionBoardGameTests::persistence);
            register(helper, "mission_board_unlocked_jobs", MissionBoardGameTests::unlockedJobs);
            register(helper, "mission_board_falls_back_to_next_priority", MissionBoardGameTests::fallsBackToNextPriority);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Settlement placeBoard(SettlementData data, int level) {
        return SettlementManager.place(data, BOARD, ModJobs.ADVENTURER.get(), level, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE)
                .settlement();
    }

    private static SettlementBlock block(SettlementData data, BlockPos pos) {
        return data.settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow();
    }

    private static AssignResult assign(SettlementData data, Settlement settlement, int settler, List<Job> currentJobs) {
        return JobAssignments.assign(data, BOARD, SETTLERS[settler], "Settler " + settler, settlement.id(), currentJobs);
    }

    /** A placed Mission Board founds or joins a settlement like any JobBlock and offers the Adventurer. */
    private static void joinsSettlement(GameTestHelper helper) {
        BlockPos lumberjack = new BlockPos(1, 1, 1);
        BlockPos board = new BlockPos(5, 1, 1);
        helper.setBlock(lumberjack, ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(board, ModBlocks.MISSION_BOARD.get());
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(helper.absolutePos(lumberjack)).orElseThrow();
        helper.assertTrue(data.settlementAt(helper.absolutePos(board)).filter(settlement::equals).isPresent(), "Board joins the settlement");
        SettlementBlock entry = block(data, helper.absolutePos(board));
        helper.assertTrue(entry.connected() && entry.jobType() == ModJobs.ADVENTURER.get(), "Connected board offering the Adventurer");
        helper.assertTrue(ModJobs.ADVENTURER.get().category() == JobCategory.MISSION, "The Adventurer is a Mission Job");
        helper.succeed();
    }

    /** Capacity 1 / 2 / 4 / 6 / 10 by level; a full board refuses further settlers; an upgrade makes room. */
    private static void capacity(GameTestHelper helper) {
        int[] expected = {1, 2, 4, 6, 10};
        for (int level = SettlementRules.MIN_LEVEL; level <= SettlementRules.MAX_LEVEL; level++) {
            SettlementData data = new SettlementData();
            Settlement settlement = placeBoard(data, level);
            helper.assertTrue(block(data, BOARD).capacity() == expected[level - 1], "Capacity at level " + level);
            for (int i = 0; i < expected[level - 1]; i++) {
                helper.assertTrue(assign(data, settlement, i, List.of()) == AssignResult.OK, "Settler " + i + " at level " + level);
            }
            helper.assertTrue(assign(data, settlement, expected[level - 1], List.of()) == AssignResult.FULL, "Full at level " + level);
            helper.assertTrue(block(data, BOARD).assignees().size() == expected[level - 1], "Assignees at level " + level);
        }
        SettlementData data = new SettlementData();
        Settlement settlement = placeBoard(data, 1);
        assign(data, settlement, 0, List.of());
        helper.assertTrue(assign(data, settlement, 1, List.of()) == AssignResult.FULL, "Level 1 holds one settler");
        SettlementManager.setLevel(data, BOARD, 2, SettlementManager.Listener.NONE);
        helper.assertTrue(assign(data, settlement, 1, List.of()) == AssignResult.OK, "The upgrade makes room for a second settler");
        helper.succeed();
    }

    /** A settler has at most one Mission Job: a second board refuses it, the same board too. */
    private static void oneMissionJob(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = placeBoard(data, 5);
        BlockPos second = new BlockPos(5, 64, 0);
        SettlementManager.place(data, second, ModJobs.ADVENTURER.get(), 5, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        helper.assertTrue(assign(data, settlement, 0, List.of(ModJobs.LUMBERJACK.get())) == AssignResult.OK, "A Lumberjack may join");
        helper.assertTrue(assign(data, settlement, 0, List.of(ModJobs.LUMBERJACK.get(), ModJobs.ADVENTURER.get())) == AssignResult.SAME_BLOCK,
                "Already on this board");
        helper.assertTrue(JobAssignments.assign(data, second, SETTLERS[0], "Settler 0", settlement.id(),
                List.of(ModJobs.LUMBERJACK.get(), ModJobs.ADVENTURER.get())) == AssignResult.MISSION_JOB, "An Adventurer cannot join a second board");
        Job specialization = ModJobs.REGISTRY.getValue(CivMod.id("gametest_mission_specialization"));
        helper.assertTrue(JobAssignments.assign(data, second, SETTLERS[1], "Settler 1", settlement.id(), List.of(specialization))
                == AssignResult.MISSION_JOB, "Any Mission Job blocks a second board");
        helper.succeed();
    }

    /** Unassigning one settler keeps the others; removing the board reports every settler that loses the job. */
    private static void unassignAndRemoval(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = placeBoard(data, 3);
        BlockPos anchor = new BlockPos(5, 64, 0);
        SettlementManager.place(data, anchor, ModJobs.LUMBERJACK.get(), 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        for (int i = 0; i < 3; i++) {
            assign(data, settlement, i, List.of());
        }
        helper.assertTrue(JobAssignments.unassign(data, BOARD, SETTLERS[1]), "Settler 1 leaves");
        helper.assertFalse(JobAssignments.isAssignee(data, BOARD, SETTLERS[1]), "Settler 1 is gone");
        helper.assertTrue(JobAssignments.isAssignee(data, BOARD, SETTLERS[0]) && JobAssignments.isAssignee(data, BOARD, SETTLERS[2]),
                "The others stay");
        List<UUID> lostJob = new java.util.ArrayList<>();
        SettlementManager.remove(data, BOARD, new SettlementManager.Listener() {
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
        helper.assertTrue(lostJob.equals(List.of(SETTLERS[0], SETTLERS[2])), "Both remaining settlers lose the job: " + lostJob);
        helper.succeed();
    }

    /** Several assignees survive a save / load round trip; a save with the earlier single assignee still loads. */
    private static void persistence(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = placeBoard(data, 2);
        assign(data, settlement, 0, List.of());
        assign(data, settlement, 1, List.of());
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        List<UUID> ids = block(loaded, BOARD).assignees().stream().map(SettlementBlock.Assignee::id).toList();
        helper.assertTrue(ids.equals(List.of(SETTLERS[0], SETTLERS[1])), "Assignees lost: " + ids);
        helper.assertTrue(block(loaded, BOARD).assignees().get(1).name().equals("Settler 1"), "Assignee name lost");

        com.google.gson.JsonObject legacy = SettlementBlock.CODEC.encodeStart(ops, new SettlementBlock(BOARD, ModJobs.LUMBERJACK.get(), 1, true))
                .getOrThrow().getAsJsonObject();
        legacy.add("assignee", UUIDUtil.CODEC.encodeStart(ops, SETTLERS[2]).getOrThrow());
        legacy.addProperty("assignee_name", "Settler 2");
        SettlementBlock old = SettlementBlock.CODEC.parse(ops, legacy).getOrThrow();
        helper.assertTrue(old.assignees().equals(List.of(new SettlementBlock.Assignee(SETTLERS[2], "Settler 2", ModJobs.LUMBERJACK.get()))), "Legacy assignee: " + old.assignees());
        helper.succeed();
    }

    /** Level 1 offers only the Adventurer; Specializations from level 2. */
    private static void unlockedJobs(GameTestHelper helper) {
        Job adventurer = ModJobs.ADVENTURER.get();
        Job specialization = ModJobs.REGISTRY.getValue(CivMod.id("gametest_mission_specialization"));
        helper.assertTrue(adventurer.offeredAt(1).equals(List.of(adventurer)), "Level 1: " + adventurer.offeredAt(1));
        List<Job> level2 = adventurer.offeredAt(2);
        helper.assertTrue(level2.get(0) == adventurer && level2.contains(specialization)
                && level2.stream().skip(1).allMatch(job -> job.parent().orElse(null) == adventurer), "Level 2: " + level2);
        helper.assertTrue(ModJobs.LUMBERJACK.get().offeredAt(5).equals(List.of(ModJobs.LUMBERJACK.get())), "No Lumberjack Specializations");

        helper.assertTrue(SettlementRules.skillCap(1) == 20 && SettlementRules.skillCap(5) == 100, "Skill cap 20 / 100");
        helper.succeed();
    }

    /** A settler whose top job is the Mission Job (nothing to do yet) works its next priority. */
    private static void fallsBackToNextPriority(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(new BlockPos(1, 1, 3), ModBlocks.MISSION_BOARD.get());
        for (int x = 4; x < 8; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
            }
        }
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos lumberjackBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos board = helper.absolutePos(new BlockPos(1, 1, 3));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(lumberjackBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Tester"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        JobAssignments.cyclePoint(data, lumberjackBlock, helper.absolutePos(new BlockPos(4, 1, 1)), true, false);
        helper.assertTrue(JobAssignments.assign(data, board, settler.getUUID(), "Tester", settlement.id(), List.of()) == AssignResult.OK,
                "Board assignment failed");
        settler.addJob(new SettlerJob(ModJobs.ADVENTURER.get(), board));
        helper.assertTrue(JobAssignments.assign(data, lumberjackBlock, settler.getUUID(), "Tester", settlement.id(),
                List.of(ModJobs.ADVENTURER.get())) == AssignResult.OK, "Lumberjack assignment failed");
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), lumberjackBlock));
        helper.assertTrue(settler.getJobs().get(0).jobType() == ModJobs.ADVENTURER.get(), "The Mission Job is the top priority");
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().activeJob().filter(job -> job.jobType() == ModJobs.LUMBERJACK.get()).isPresent(),
                    "The settler should work as Lumberjack (active " + settler.getWork().activeJob() + ")");
            helper.assertTrue(settler.getJobs().size() == 2, "Both jobs kept: " + settler.getJobs());
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
