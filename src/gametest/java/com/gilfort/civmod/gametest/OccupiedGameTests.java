package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.job.WorkPlaceLock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for occupied work places (vault {@code Systems/Jobs.md}): the work place of the day is occupied towards
 * other settlers for the whole day, and a player cannot open its GUI while the settler works there.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class OccupiedGameTests {

    private OccupiedGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "occupied_two_at_one_job_block", OccupiedGameTests::twoAtOneJobBlock);
            register(helper, "occupied_scroll_points", OccupiedGameTests::scrollPointsSkipOccupied);
            register(helper, "occupied_no_free_work_place", OccupiedGameTests::noFreeWorkPlace);
            register(helper, "occupied_broken_work_place", OccupiedGameTests::brokenWorkPlaceMoves);
            register(helper, "occupied_released", OccupiedGameTests::releasedOnLeaveAndDeath);
            register(helper, "occupied_player_lock", OccupiedGameTests::playerLock);
            register(helper, "occupied_broken_tree", OccupiedGameTests::brokenTree);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * Two Craftspersons at one JobBlock use different crafting tables: the first takes the nearest, the auto-search of
     * the second skips it and finds the next one.
     */
    private static void twoAtOneJobBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ClerkGameTests.clerkWithStorage(helper);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        SettlementManager.setLevel(SettlementData.get(level), craft, 3, SettlementManager.Listener.NONE);
        BlockPos near = CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        BlockPos far = CraftspersonGameTests.placeTable(helper, new BlockPos(6, 1, 5));
        Settler cora = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        Settler cid = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(3, 1, 7), "Cid");
        cora.getWork().decideNow(level);
        cid.getWork().decideNow(level);
        helper.assertTrue(workPlace(cora, craft).equals(Optional.of(near)), "Cora takes the nearest table: " + workPlace(cora, craft));
        helper.assertTrue(workPlace(cid, craft).equals(Optional.of(far)), "Cid skips Cora's table: " + workPlace(cid, craft));
        cora.discard();
        cid.discard();
        helper.succeed();
    }

    /**
     * Scroll work points: two settlers take the two points, one each, and never the nearer table that is no point. A
     * third settler at the block finds both points occupied: "No free work place" (no auto-search past the points).
     */
    private static void scrollPointsSkipOccupied(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        ClerkGameTests.clerkWithStorage(helper);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        SettlementManager.setLevel(data, craft, 5, SettlementManager.Listener.NONE);
        CraftspersonGameTests.placeTable(helper, new BlockPos(2, 1, 6));
        BlockPos first = CraftspersonGameTests.placeTable(helper, new BlockPos(5, 1, 5));
        BlockPos second = CraftspersonGameTests.placeTable(helper, new BlockPos(7, 1, 5));
        JobAssignments.cyclePoint(data, craft, first, true, false);
        JobAssignments.cyclePoint(data, craft, second, true, false);
        Settler cora = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 8), "Cora");
        Settler cid = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(3, 1, 8), "Cid");
        Settler cyd = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(4, 1, 8), "Cyd");
        cora.getWork().decideNow(level);
        cid.getWork().decideNow(level);
        cyd.getWork().decideNow(level);
        Set<Optional<BlockPos>> taken = Set.of(workPlace(cora, craft), workPlace(cid, craft));
        helper.assertTrue(taken.equals(Set.of(Optional.of(first), Optional.of(second))),
                "One scroll point each: " + workPlace(cora, craft) + ", " + workPlace(cid, craft));
        helper.assertTrue(workPlace(cyd, craft).isEmpty(), "Cyd takes no table: " + workPlace(cyd, craft));
        helper.assertTrue(cyd.getWork().status(craft) == JobStatus.NO_FREE_WORK_PLACE, "Cyd: " + cyd.getWork().status(craft));
        List.of(cora, cid, cyd).forEach(Settler::discard);
        helper.succeed();
    }

    /**
     * Only one table for two settlers: the second shows "No free work place" with the "no work place" thought bubble
     * and works its next job priority (a Lumberjack job at another JobBlock).
     */
    private static void noFreeWorkPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ClerkGameTests.clerkWithStorage(helper);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        SettlementManager.setLevel(SettlementData.get(level), craft, 3, SettlementManager.Listener.NONE);
        BlockPos table = CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        helper.setBlock(new BlockPos(1, 1, 12), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos other = helper.absolutePos(new BlockPos(1, 1, 12));
        helper.setBlock(new BlockPos(1, 1, 14), Blocks.OAK_LOG);
        BlockPos otherLog = helper.absolutePos(new BlockPos(1, 1, 14));
        Settler cora = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        Settler cid = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(3, 1, 7), "Cid");
        SettlementData data = SettlementData.get(level);
        helper.assertTrue(TestAssignments.assign(data, other, cid.getUUID(), "Cid", data.settlementAt(other).orElseThrow().id(),
                List.of(ModJobs.CRAFTSPERSON.get())) == JobAssignments.AssignResult.OK, "Second job assigned");
        cid.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), other));
        cid.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        cora.getWork().decideNow(level);
        cid.getWork().decideNow(level);
        helper.assertTrue(workPlace(cora, craft).equals(Optional.of(table)), "Cora takes the table: " + workPlace(cora, craft));
        helper.assertTrue(cid.getWork().status(craft) == JobStatus.NO_FREE_WORK_PLACE, "Cid: " + cid.getWork().status(craft));
        helper.assertTrue(JobStatus.NO_FREE_WORK_PLACE.isBlocked(), "No free work place blocks the job");
        helper.assertTrue(JobStatus.NO_FREE_WORK_PLACE.displayName().getString().equals("No free work place"), "Status text");
        helper.assertTrue(cid.getWork().recentThoughts().stream().anyMatch(show -> show.bubble() == ThoughtBubble.NO_WORK_PLACE && show.on()),
                "Thought bubble: " + cid.getWork().recentThoughts());
        helper.assertTrue(workPlace(cid, other).equals(Optional.of(otherLog)), "Next priority works: " + workPlace(cid, other));
        cora.discard();
        cid.discard();
        helper.succeed();
    }

    /**
     * A broken work place: the settler picks a new free one at once, the occupation moves with it, and the old place
     * (rebuilt) is free for the next settler.
     */
    private static void brokenWorkPlaceMoves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ClerkGameTests.clerkWithStorage(helper);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        SettlementManager.setLevel(SettlementData.get(level), craft, 3, SettlementManager.Listener.NONE);
        BlockPos near = CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        BlockPos far = CraftspersonGameTests.placeTable(helper, new BlockPos(6, 1, 5));
        Settler cora = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        Settler cid = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(3, 1, 7), "Cid");
        cora.getWork().decideNow(level);
        helper.assertTrue(workPlace(cora, craft).equals(Optional.of(near)), "Cora takes the nearest table: " + workPlace(cora, craft));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.AIR);
        cora.getWork().decideNow(level);
        helper.assertTrue(workPlace(cora, craft).equals(Optional.of(far)), "Cora moves to the other table: " + workPlace(cora, craft));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        cid.getWork().decideNow(level);
        helper.assertTrue(workPlace(cid, craft).equals(Optional.of(near)), "The rebuilt table is free: " + workPlace(cid, craft));
        cora.discard();
        cid.discard();
        helper.succeed();
    }

    /** Leaving the job and dying free the occupied work place for the other settlers. */
    private static void releasedOnLeaveAndDeath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        ClerkGameTests.clerkWithStorage(helper);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        SettlementManager.setLevel(data, craft, 3, SettlementManager.Listener.NONE);
        BlockPos table = CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        Settler cora = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        Settler cid = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(3, 1, 7), "Cid");
        cora.getWork().decideNow(level);
        cora.removeJob(craft);
        TestAssignments.unassign(data, craft, cora.getUUID());
        cid.getWork().decideNow(level);
        helper.assertTrue(workPlace(cid, craft).equals(Optional.of(table)), "Cora left the job: Cid takes the table " + workPlace(cid, craft));
        Settler cyd = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(4, 1, 7), "Cyd");
        cyd.getWork().decideNow(level);
        helper.assertTrue(cyd.getWork().status(craft) == JobStatus.NO_FREE_WORK_PLACE, "Cid holds the table: " + cyd.getWork().status(craft));
        // Entities only tick (and finish dying) in forced chunks on the GameTest server.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        cid.kill(level);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(cid.isRemoved(), "Cid still dying"))
                .thenExecute(() -> {
                    // A new start of the job: the blocked wait of "No free work place" ends.
                    cyd.removeJob(craft);
                    cyd.addJob(new SettlerJob(ModJobs.CRAFTSPERSON.get(), craft));
                    cyd.getWork().decideNow(level);
                    helper.assertTrue(workPlace(cyd, craft).equals(Optional.of(table)), "Cid died: Cyd takes the table " + workPlace(cyd, craft));
                    cora.discard();
                    cyd.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * Live play: while the Craftsperson fetches its inputs, a player may open its crafting table; while it crafts there
     * the table is refused with "In use by Cora"; once it delivers, the table opens again.
     */
    private static void playerLock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        BlockPos table = CraftspersonGameTests.placeTable(helper, new BlockPos(4, 1, 6));
        CraftspersonGameTests.define(helper, craft, 0, Items.STICK);
        ((Container) level.getBlockEntity(craft)).setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        Requests.post(data, data.settlementAt(clerk).orElseThrow(), Items.STICK, 4, Optional.of(clerk), Optional.empty());
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.FETCHING_INPUTS, "Fetching: " + settler.getWork().status(craft));
        helper.assertTrue(workPlace(settler, craft).equals(Optional.of(table)), "The table is occupied: " + workPlace(settler, craft));
        helper.assertTrue(WorkPlaceLock.refusal(level, table).isEmpty(), "Away fetching: the player may open the table");
        settler.setNoAi(false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(WorkPlaceLock.refusal(level, table).isPresent(), "Not crafting at the table yet: "
                        + settler.getWork().status(craft) + " at " + helper.relativePos(settler.blockPosition())))
                .thenExecute(() -> {
                    String text = WorkPlaceLock.refusal(level, table).orElseThrow().getString();
                    helper.assertTrue(text.equals("In use by Cora"), "Action bar text: " + text);
                })
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(craft) == JobStatus.DELIVERING, "Not delivering yet: "
                        + settler.getWork().status(craft)))
                .thenExecute(() -> helper.assertTrue(WorkPlaceLock.refusal(level, table).isEmpty(), "Delivering: the table opens again"))
                .thenExecute(() -> {
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * Live play: a Lumberjack's log has no player lock; breaking it makes the settler work at a new log at once, the
     * buffer is kept.
     */
    private static void brokenTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        BlockPos log = helper.absolutePos(new BlockPos(4, 1, 1));
        int[] buffered = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.WORKING
                        && settler.getWork().buffer(jobBlock).count() > 0, "Not working yet: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> {
                    helper.assertTrue(workPlace(settler, jobBlock).equals(Optional.of(log)), "Works at the scroll point: " + workPlace(settler, jobBlock));
                    helper.assertTrue(WorkPlaceLock.refusal(level, log).isEmpty(), "A tree has no player lock");
                    buffered[0] = settler.getWork().buffer(jobBlock).count();
                    level.destroyBlock(log, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(workPlace(settler, jobBlock).filter(pos -> !pos.equals(log)).isPresent()
                        && settler.getWork().status(jobBlock) == JobStatus.WORKING, "No new log yet: " + workPlace(settler, jobBlock)
                        + " " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> helper.assertTrue(settler.getWork().buffer(jobBlock).count() >= buffered[0],
                        "Output kept: " + settler.getWork().buffer(jobBlock).count() + " < " + buffered[0]))
                .thenExecute(() -> {
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** The work place of the day a settler chose for a job, as saved with it. */
    static Optional<BlockPos> workPlace(Settler settler, BlockPos jobBlock) {
        return settler.getWork().snapshot().workPlaces().stream().filter(entry -> entry.jobBlock().equals(jobBlock))
                .map(SettlerWork.WorkPlaceEntry::poi).findFirst();
    }
}
