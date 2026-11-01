package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementManager.Outcome;
import com.gilfort.civmod.settlement.SettlementManager.Placement;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for a settlement's connection following its center (vault {@code Systems/Settlement.md}): the recheck after a
 * JobBlock removal, settlers at unconnected blocks, and a settlement without a Center Block.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ConnectionGameTests {

    private static final long DAY = 24000L;

    private ConnectionGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "connection_chain_example", ConnectionGameTests::chainExample);
            register(helper, "connection_reconnects_with_feedback", ConnectionGameTests::reconnects);
            register(helper, "connection_recheck_stays_quiet", ConnectionGameTests::staysQuiet);
            register(helper, "connection_settler_pauses_live", ConnectionGameTests::pausesLive);
            register(helper, "connection_settler_pauses_in_catch_up", ConnectionGameTests::pausesInCatchUp);
            register(helper, "connection_center_removed", ConnectionGameTests::centerRemoved);
            register(helper, "connection_exclusion_without_center", ConnectionGameTests::exclusionWithoutCenter);
            register(helper, "connection_revival", ConnectionGameTests::revival);
            register(helper, "connection_earlier_version_revived", ConnectionGameTests::earlierVersionRevived);
            register(helper, "connection_earlier_version_loses_block", ConnectionGameTests::earlierVersionLosesBlock);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Records what players would be shown. */
    private static final class Recorder implements SettlementManager.Listener {

        final List<BlockPos> disconnected = new ArrayList<>();
        final List<BlockPos> connected = new ArrayList<>();
        final List<BlockPos> announced = new ArrayList<>();

        @Override
        public void blockChanged(BlockPos pos, int settlementId, boolean isConnected) {
            (isConnected ? connected : disconnected).add(pos);
        }

        @Override
        public void autoConnected(BlockPos pos) {
            announced.add(pos);
        }

        void clear() {
            disconnected.clear();
            connected.clear();
            announced.clear();
        }
    }

    private static BlockPos at(int x) {
        return new BlockPos(x, 64, 0);
    }

    private static Placement center(SettlementData data, int x, SettlementManager.Listener listener) {
        return SettlementManager.placeCenter(data, at(x), ModJobs.ADMINISTRATOR.get(), 1, Races.HUMAN, 0L, RandomSource.create(0L), listener);
    }

    private static Placement jobBlock(SettlementData data, int x, int level) {
        return SettlementManager.place(data, at(x), ModJobs.LUMBERJACK.get(), level, SettlementManager.Listener.NONE);
    }

    private static void remove(SettlementData data, int x, SettlementManager.Listener listener) {
        SettlementManager.remove(data, at(x), 0L, listener);
    }

    private static boolean connected(Settlement settlement, int x) {
        return settlement.block(at(x)).orElseThrow().connected();
    }

    /** The chain 1 (center) - 2 - 3 - 4 - 5 at x = 0, 20, 30, 40, 50, with level 1 JobBlocks reaching 10 blocks. */
    private static Settlement chain(GameTestHelper helper, SettlementData data) {
        Settlement settlement = center(data, 0, SettlementManager.Listener.NONE).settlement().orElseThrow();
        for (int x : new int[] {20, 30, 40, 50}) {
            helper.assertTrue(jobBlock(data, x, 1).outcome() == Outcome.JOINED, "Part of the chain: " + x);
        }
        return settlement;
    }

    /** Removing 2 and 3 leaves 1 connected and makes 4 and 5 unconnected, with the usual state on the blocks. */
    private static void chainExample(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = chain(helper, data);
        Recorder recorder = new Recorder();
        remove(data, 20, recorder);
        remove(data, 30, recorder);
        helper.assertTrue(connected(settlement, 0), "The center stays connected");
        helper.assertFalse(connected(settlement, 40) || connected(settlement, 50), "4 and 5 lost the chain");
        helper.assertTrue(recorder.disconnected.containsAll(List.of(at(40), at(50))), "They show as unconnected: " + recorder.disconnected);
        SettlementManager.Status status = SettlementManager.status(data, at(40));
        helper.assertTrue(status.distance() == 40 && status.required() == 25, "Hint: distance to the center: " + status);
        helper.assertTrue(settlement.centerGrace().isEmpty(), "No timer: the Center Block stands");
        helper.succeed();
    }

    /** A block closing the gap connects the waiting ones with the existing feedback. */
    private static void reconnects(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = chain(helper, data);
        remove(data, 20, SettlementManager.Listener.NONE);
        remove(data, 30, SettlementManager.Listener.NONE);
        Recorder recorder = new Recorder();
        SettlementManager.place(data, at(20), ModJobs.LUMBERJACK.get(), 2, recorder);
        helper.assertTrue(connected(settlement, 40) && connected(settlement, 50), "The chain stands again");
        helper.assertTrue(recorder.announced.equals(List.of(at(40), at(50))), "Announced: " + recorder.announced);
        helper.succeed();
    }

    /** Blocks that stay connected are neither announced nor changed. */
    private static void staysQuiet(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        chain(helper, data);
        Recorder recorder = new Recorder();
        remove(data, 50, recorder);
        helper.assertTrue(recorder.disconnected.isEmpty() && recorder.connected.isEmpty() && recorder.announced.isEmpty(),
                "Nothing to tell: " + recorder.announced + recorder.connected + recorder.disconnected);
        helper.succeed();
    }

    private static BlockPos centerOf(Settlement settlement) {
        return settlement.blocks().stream().filter(SettlementBlock::isCenter).map(SettlementBlock::pos).findFirst().orElseThrow();
    }

    /** At work when its block loses the center: the settler stops but keeps the job, and works again after the revival. */
    private static void pausesLive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        BlockPos center = centerOf(settlement);
        boolean[] removed = new boolean[1];
        boolean[] revived = new boolean[1];
        helper.onEachTick(() -> {
            if (!removed[0]) {
                if (settler.getWork().activeJob().isPresent()) {
                    removed[0] = true;
                    level.destroyBlock(center, false);
                    helper.assertTrue(helper.getBlockEntity(new BlockPos(1, 1, 1), JobBlockEntity.class).isUnconnected(), "The block shows as unconnected");
                }
                return;
            }
            if (!revived[0]) {
                if (settler.getWork().status(jobBlock) == JobStatus.UNCONNECTED && settler.getWork().activeJob().isEmpty()) {
                    helper.assertTrue(settler.getJobs().size() == 1, "The job stays");
                    helper.assertTrue(settlement.block(jobBlock).orElseThrow().isAssignee(settler.getUUID()), "The assignment stays");
                    revived[0] = true;
                    level.setBlock(center, ModBlocks.HUMAN_CENTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
                }
                return;
            }
            if (settler.getWork().activeJob().isPresent()) {
                helper.assertTrue(settlement.block(jobBlock).orElseThrow().connected(), "Connected again");
                settler.discard();
                forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                helper.succeed();
            }
        });
    }

    private static int count(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    /** A missed day at an unconnected block gives nothing; once connected the same missed day works. */
    private static void pausesInCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        Settlement settlement = SettlementData.get(level).settlementAt(jobBlock).orElseThrow();
        BlockPos center = centerOf(settlement);
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || LumberjackGameTests.foundNothing(settler, settler.getJobs().get(0))) {
                return;
            }
            done[0] = true;
            level.destroyBlock(center, false);
            Container container = (Container) level.getBlockEntity(jobBlock);
            int before = count(container) + settler.getWork().buffer(jobBlock).count();
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(count(container) + settler.getWork().buffer(jobBlock).count() == before, "No output at an unconnected block");
            helper.assertFalse(settler.getWork().status(jobBlock) == JobStatus.DROP_OFF_FULL, "The job did not end, it pauses");
            helper.assertTrue(settler.getJobs().size() == 1, "The job stays");

            level.setBlock(center, ModBlocks.HUMAN_CENTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            settler.getWork().setCatchUp(clock - DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(count(container) + settler.getWork().buffer(jobBlock).count() > before, "Connected again it works");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** Without its only Center Block every block is unconnected; the settlement stays what it was. */
    private static void centerRemoved(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = chain(helper, data);
        UUID settler = UUID.randomUUID();
        data.addMember(settlement, settler);
        int color = settlement.color();
        Recorder recorder = new Recorder();
        remove(data, 0, recorder);
        helper.assertTrue(settlement.connectedCount() == 0, "All blocks unconnected: " + settlement.connectedCount());
        helper.assertTrue(recorder.disconnected.size() == 4 && recorder.announced.isEmpty(), "Each shows it: " + recorder.disconnected);
        helper.assertTrue(data.settlements().contains(settlement) && settlement.color() == color && settlement.members().contains(settler),
                "Colour and settlers stay");
        helper.assertTrue(settlement.blocks().size() == 4, "The blocks stay in the settlement");
        SettlementManager.Status status = SettlementManager.status(data, at(20));
        helper.assertTrue(status.settlement().isPresent() && !status.connected() && status.required() == 0, "Nothing to reach: " + status);
        helper.succeed();
    }

    /** The zones of all its blocks still count, connected or not, also beyond the place of the last Center Block. */
    private static void exclusionWithoutCenter(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = center(data, 0, SettlementManager.Listener.NONE).settlement().orElseThrow();
        jobBlock(data, 20, 1);
        jobBlock(data, 30, 2);
        remove(data, 0, SettlementManager.Listener.NONE);
        // Block at 30, level 2: exclusion 60, so 90 is in the zone; the last center's zone ends at 75.
        helper.assertFalse(SettlementManager.wouldFound(data, at(90)), "Exclusion over all blocks");
        helper.assertTrue(SettlementManager.wouldFound(data, at(91)), "Outside every zone");
        Placement waiting = jobBlock(data, 90, 1);
        helper.assertTrue(waiting.outcome() == Outcome.UNCONNECTED && waiting.settlement().orElseThrow() == settlement, "A JobBlock there waits: " + waiting);
        helper.assertTrue(waiting.nothingToReach(), "With nothing to reach: " + waiting);
        helper.assertTrue(data.settlements().size() == 1, "No second settlement");
        helper.succeed();
    }

    /** A new Center Block revives the settlement: blocks in reach connect with the feedback, the others keep waiting. */
    private static void revival(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = chain(helper, data);
        remove(data, 0, SettlementManager.Listener.NONE);
        remove(data, 30, SettlementManager.Listener.NONE);
        Recorder recorder = new Recorder();
        // Reaches 20 (and from it nothing), not 40 and 50.
        Placement again = center(data, 0, recorder);
        helper.assertTrue(again.outcome() == Outcome.REVIVED && again.settlement().orElseThrow() == settlement && data.settlements().size() == 1, "Revived: " + again);
        helper.assertTrue(connected(settlement, 20) && !connected(settlement, 40) && !connected(settlement, 50), "Only what is in reach connects");
        helper.assertTrue(recorder.announced.equals(List.of(at(20))), "Announced: " + recorder.announced);
        helper.assertTrue(settlement.centerGrace().isEmpty(), "The timer stopped");
        helper.succeed();
    }

    /** A settlement from an earlier version has no center: a Center Block revives it and only its chain stays connected. */
    private static void earlierVersionRevived(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = TestSettlements.earlierVersion(data, at(20), at(60));
        helper.assertFalse(SettlementManager.wouldFound(data, at(0)), "It keeps its exclusion zone");
        Placement revived = center(data, 0, SettlementManager.Listener.NONE);
        helper.assertTrue(revived.outcome() == Outcome.REVIVED && revived.settlement().orElseThrow() == settlement, "Revived: " + revived);
        helper.assertTrue(connected(settlement, 20), "Chained to the new center");
        helper.assertFalse(connected(settlement, 60), "Out of its reach");
        helper.succeed();
    }

    /** The first removal treats it like any settlement without a center. */
    private static void earlierVersionLosesBlock(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = TestSettlements.earlierVersion(data, at(20), at(25));
        remove(data, 25, SettlementManager.Listener.NONE);
        helper.assertTrue(settlement.connectedCount() == 0, "Nothing is chained to a center");
        helper.assertTrue(settlement.centerGrace().filter(grace -> grace.dissolveDay() == 4L).isPresent(), "The timer runs");
        helper.succeed();
    }
}
