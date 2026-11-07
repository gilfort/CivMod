package com.gilfort.civmod.gametest;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Entertain;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ServiceAct;
import com.gilfort.civmod.api.ServiceProfile;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.BardShow;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.ServiceRound;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Bard's day round (spec "Service and Bard", story #211; vault {@code Systems/Entertainment.md}, Bard): the
 * stops at working settlers, their number, the hours, the daily cap and the look of a performance. The tests run in the
 * environment {@code civmod:bard_round} (12:00, the clock stands still; the test that moves the clock has its own batch, {@code civmod:bard_round_clock}); stops are timed in game ticks, so a test that waits
 * for stops takes up to a few thousand ticks.
 * <p>
 * A "working settler" is a settler of the settlement on a job step. The tests register a Service Job
 * ({@code civmod:gametest_round_worker}) whose only act is open all day, so its settler stands at its JobBlock and works.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BardRoundGameTests {

    private static final Identifier WORKER = CivMod.id("gametest_round_worker");
    private static final BlockPos STAGE = ServiceGameTests.JOB_BLOCK;
    /** Where the worker's JobBlock and the worker stand: on the far side of the floor, 10 blocks from the Stage. */
    private static final BlockPos WORKER_BLOCK = new BlockPos(12, 1, 4);
    private static final BlockPos WORKER_AT = new BlockPos(11, 1, 4);
    private static final int BARD_SKILL = 10;
    private static final int STOP_TICKS = 600;

    private BardRoundGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(CivModApi.JOB_REGISTRY, helper -> helper.register(WORKER,
                ServiceGameTests.service(ServiceProfile.of(ServiceAct.stationary("work", 0, ServiceAct.DAY_TICKS, 8.0, new Entertain(0.0))))));
        event.register(Registries.BLOCK, helper -> ServiceGameTests.block(helper, WORKER));
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "bard_round_table", BardRoundGameTests::table);
            register(helper, "bard_round_stop", BardRoundGameTests::stop);
            register(helper, "bard_round_nobody_there", BardRoundGameTests::nobodyThere);
            register(helper, "bard_round_no_worker", BardRoundGameTests::noWorker);
            register(helper, "bard_round_ends", BardRoundGameTests::ends);
            register(helper, "bard_round_stops_per_level", BardRoundGameTests::stopsPerLevel);
            register(helper, "bard_round_two_bards", BardRoundGameTests::twoBards);
            register(helper, "bard_round_look", BardRoundGameTests::look);
            register(helper, "bard_round_catch_up", BardRoundGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** A Stage of the given level at (1, 1, 1) with its Bard (skill 10). */
    private static ServiceGameTests.Scene scene(GameTestHelper helper, int stageLevel) {
        ServiceGameTests.Scene scene = ServiceGameTests.scene(helper, CivMod.id("stage_block"), CivMod.id("bard"));
        raise(helper, STAGE, stageLevel);
        scene.performer().getSkills().set(scene.job(), new SettlerSkills.Skill(BARD_SKILL, 0));
        return scene;
    }

    private static void raise(GameTestHelper helper, BlockPos relative, int stageLevel) {
        helper.getBlockEntity(relative, JobBlockEntity.class).setJobLevel(stageLevel);
        SettlementManager.setLevel(SettlementData.get(helper.getLevel()), helper.absolutePos(relative), stageLevel, SettlementManager.Listener.NONE);
    }

    /** A settler working the all-day Service Job at a JobBlock next to it. */
    private static Settler worker(GameTestHelper helper, ServiceGameTests.Scene scene, BlockPos block, BlockPos at, String name) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, block, BuiltInRegistries.BLOCK.getValue(WORKER));
        Settler worker = ServiceGameTests.settler(helper, scene.settlement(), Vec3.atBottomCenterOf(helper.absolutePos(at)), name, true);
        helper.assertTrue(SettlementWorld.assign(level, helper.absolutePos(block), worker, ModJobs.REGISTRY.getValue(WORKER)) == JobAssignments.AssignResult.OK,
                "The worker is assigned");
        worker.getWork().decideNow(level);
        helper.assertTrue(worker.getWork().atWork(), "The worker is at work: " + worker.getWork().activeJob());
        return worker;
    }

    private static Settler idle(GameTestHelper helper, ServiceGameTests.Scene scene, BlockPos at, String name) {
        return ServiceGameTests.settler(helper, scene.settlement(), Vec3.atBottomCenterOf(helper.absolutePos(at)), name, true);
    }

    private static JobStatus status(Settler bard) {
        return bard.getWork().status(bard.getJobs().get(0).jobBlock());
    }

    private static long playingBubbles(Settler bard) {
        return bard.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.PLAYING).count();
    }

    private static boolean holdsHorn(Settler bard) {
        return bard.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.GOAT_HORN);
    }

    private static boolean carriesHorn(Settler bard) {
        for (int slot = 0; slot < bard.getInventory().getContainerSize(); slot++) {
            if (bard.getInventory().getItem(slot).is(Items.GOAT_HORN)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- tests

    /**
     * The pure numbers of the round: the state of one day (stops, tries, the wait of a minute), and the melody.
     */
    private static void table(GameTestHelper helper) {
        ServiceRound round = new ServiceRound();
        helper.assertTrue(round.due(1) && !round.due(0) && !round.playing(0) && !round.waiting(0) && !round.busy(), "A fresh day: nothing done, nothing going on");

        // A stop that finds nobody is not used up; the third try in a row sends the Bard back to the Stage for a minute.
        round.head(UUID.nameUUIDFromBytes(new byte[] {1}));
        helper.assertTrue(round.busy() && round.target().isPresent(), "Heading for a settler");
        round.arrivedAlone(100);
        round.arrivedAlone(200);
        helper.assertTrue(round.stops() == 0 && !round.waiting(200) && !round.busy(), "Two tries: the stop is not used up, the Bard looks again at once");
        round.arrivedAlone(300);
        helper.assertTrue(round.stops() == 0 && round.waiting(300) && round.waiting(1499) && !round.waiting(1500),
                "Three tries: waits about a minute (1200 ticks), the stop is still not used up");
        round.arrivedAlone(1500);
        helper.assertTrue(!round.waiting(1500), "After the wait the count of tries starts again");

        // No settler works: the same wait.
        ServiceRound idle = new ServiceRound();
        idle.noOneWorks(1000);
        helper.assertTrue(idle.waiting(1000) && idle.waiting(2199) && !idle.waiting(2200) && idle.stops() == 0, "Nobody works: look again after 1200 ticks");

        // A stop: counted when it starts, lasts 600 ticks (30 seconds).
        ServiceRound stop = new ServiceRound();
        BlockPos spot = new BlockPos(3, 4, 5);
        stop.start(50, spot);
        helper.assertTrue(stop.stops() == 1 && stop.playing(50) && stop.playing(649) && !stop.playing(650) && stop.spot().equals(Optional.of(spot)),
                "A stop lasts 30 seconds from its start");
        helper.assertTrue(!stop.due(1) && stop.due(2) && stop.due(5), "Stops per day: the Stage level");
        helper.assertTrue(stop.busy(), "Playing is busy");
        stop.reset();
        helper.assertTrue(!stop.busy() && !stop.playing(60) && stop.stops() == 1, "The end of the window clears the stop, not the count");

        // The melody: a phrase of 8 notes every 8 ticks, repeated every 300 ticks; note 12 is the pitch 1, note 24 the pitch 2.
        int[][] notes = {{0, 12}, {8, 16}, {16, 19}, {24, 16}, {32, 21}, {40, 19}, {48, 16}, {56, 12}, {300, 12}, {308, 16}, {-1, -1}};
        for (int[] row : notes) {
            if (row[0] >= 0) {
                helper.assertTrue(BardShow.note(row[0]) == row[1], "Melody at tick " + row[0] + ": " + BardShow.note(row[0]));
            }
        }
        helper.assertTrue(BardShow.note(3) == -1 && BardShow.note(64) == -1 && BardShow.note(299) == -1, "Silence between the notes and phrases");
        helper.assertTrue(BardShow.pitch(12) == 1.0F && BardShow.pitch(24) == 2.0F && Math.abs(BardShow.pitch(0) - 0.5F) < 1e-6, "Pitches of note blocks");
        helper.assertTrue(!ThoughtBubble.PLAYING.problem(), "The stop bubble is an activity bubble, hidden by 'Problems only'");
        helper.succeed();
    }

    /**
     * A stop at a worker: the Bard walks to it and stops once the worker is there; settlers within 8 blocks of the Bard hear it
     * and keep working, those further away do not; the Bard gets the EXP of a level-3 Stage.
     */
    private static void stop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper, 3);
        Settler worker = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
        Settler near = idle(helper, scene, new BlockPos(9, 1, 6), "Near");
        Settler far = idle(helper, scene, new BlockPos(0, 1, 8), "Far");
        Settler bard = scene.performer();
        helper.startSequence()
                .thenExecute(() -> bard.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) > 0,
                        "The Bard stops at the worker (status " + status(bard) + ", at " + bard.position() + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(bard.distanceTo(worker) < 4.0, "The Bard walked to the worker: " + bard.distanceTo(worker));
                    helper.assertTrue(bard.distanceTo(near) <= 8.0 && bard.distanceTo(far) > 8.0, "Setup: near " + bard.distanceTo(near) + ", far " + bard.distanceTo(far));
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(worker), 1.1), "The worker hears it: " + ServiceGameTests.entertainment(worker));
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(near), 1.1), "Within 8 blocks of the Bard: " + ServiceGameTests.entertainment(near));
                    helper.assertTrue(ServiceGameTests.entertainment(far) == 0.0, "Beyond 8 blocks: nothing");
                    helper.assertTrue(ServiceGameTests.entertainment(bard) == 0.0, "The Bard does not hear itself");
                    helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 30, "EXP: 10 times Stage level 3: " + ServiceGameTests.exp(bard, scene.job()));
                    helper.assertTrue(worker.getWork().atWork(), "The listener keeps working");
                    ServiceGameTests.cleanUp(level, scene, worker, near, far);
                })
                .thenSucceed();
    }

    /**
     * The worker leaves before the Bard arrives (it stands somewhere else): arriving at an empty spot plays nothing and does
     * not use up the stop; the Bard heads for the worker again and plays one stop (a level-1 Stage has only one).
     */
    private static void nobodyThere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper, 1);
        Settler worker = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
        Settler bard = scene.performer();
        Vec3 away = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(0, 1, 8)));
        helper.startSequence()
                .thenExecute(() -> {
                    bard.getWork().decideNow(level);
                    worker.teleportTo(away.x, away.y, away.z);
                    worker.setNoAi(true);
                })
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) > 0, "The stop is played in the end (status " + status(bard) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 10, "One stop, not used up by the empty spot: " + ServiceGameTests.exp(bard, scene.job()));
                    helper.assertTrue(bard.distanceTo(worker) < 4.0 && ServiceGameTests.near(ServiceGameTests.entertainment(worker), 1.1),
                            "The Bard plays where the worker is now: " + bard.distanceTo(worker));
                    ServiceGameTests.cleanUp(level, scene, worker);
                })
                .thenSucceed();
    }

    /**
     * No settler works: the Bard goes to the Stage, plays nothing and looks again after about a minute, then plays for the
     * worker that has come to work in the meantime.
     */
    private static void noWorker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper, 3);
        Settler bard = scene.performer();
        Settler[] worker = new Settler[1];
        long[] started = new long[1];
        helper.startSequence()
                .thenExecute(() -> {
                    started[0] = level.getGameTime();
                    bard.getWork().decideNow(level);
                })
                .thenWaitUntil(() -> {
                    Vec3 top = Vec3.atBottomCenterOf(helper.absolutePos(STAGE.above()));
                    helper.assertTrue(Math.abs(bard.getX() - top.x) < 0.7 && Math.abs(bard.getZ() - top.z) < 0.7, "No one works: the Bard waits on the Stage: " + bard.position() + " instead of " + top);
                })
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.works(bard, scene.job()) && status(bard) == JobStatus.SERVING, "Serving: " + status(bard));
                    helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 0 && playingBubbles(bard) == 0, "Nothing played");
                    worker[0] = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
                })
                .thenIdle(900)
                .thenExecute(() -> helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 0, "Not before a minute is over: " + (level.getGameTime() - started[0]) + " ticks"))
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) > 0, "The Bard looks again and plays (status " + status(bard) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getGameTime() - started[0] >= 1100, "After about a minute (the wait began with the Bard's first decision, a little before the test): " + (level.getGameTime() - started[0]));
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(worker[0]), 1.1), "The worker hears it");
                    ServiceGameTests.cleanUp(level, scene, worker[0]);
                })
                .thenSucceed();
    }

    /**
     * A stop in progress ends at 17:30: the Bard leaves the worker, goes to the Stage and plays the stage act for the settler
     * there.
     */
    private static void ends(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.setDayTime(level, ServiceAct.clock(17, 24));
        ServiceGameTests.Scene scene = scene(helper, 3);
        Settler worker = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
        Settler hearer = idle(helper, scene, new BlockPos(0, 1, 0), "Hearer");
        Settler bard = scene.performer();
        helper.startSequence()
                .thenExecute(() -> bard.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(status(bard) == JobStatus.PLAYING && ServiceGameTests.exp(bard, scene.job()) == 30,
                        "The stop is playing (status " + status(bard) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.entertainment(hearer) == 0.0, "The settler at the Stage is out of reach of the stop: " + hearer.position() + " bard " + bard.position());
                    helper.assertTrue(bard.distanceTo(hearer) > 8.0, "Setup: " + bard.distanceTo(hearer));
                    ServiceGameTests.setDayTime(level, ServiceAct.clock(17, 31));
                })
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.entertainment(hearer) > 0.0,
                        "The round is over: the Bard plays the stage act at the Stage (status " + status(bard) + ", at " + bard.position() + ")"))
                .thenExecute(() -> {
                    Vec3 top = Vec3.atBottomCenterOf(helper.absolutePos(STAGE.above()));
                    helper.assertTrue(Math.abs(bard.getX() - top.x) < 0.7 && Math.abs(bard.getZ() - top.z) < 0.7, "On the Stage: " + bard.position() + " instead of " + top);
                    helper.assertTrue(playingBubbles(bard) == 2, "A bubble for the stop and one for the stage act: " + playingBubbles(bard));
                    ServiceGameTests.cleanUp(level, scene, worker, hearer);
                })
                .thenSucceed();
    }

    /** A level-2 Stage: the Bard stops twice, however long the window stays open. The worker is paid once a day. */
    private static void stopsPerLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper, 2);
        Settler worker = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
        Settler bard = scene.performer();
        helper.startSequence()
                .thenExecute(() -> bard.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(playingBubbles(bard) == 2, "Two stops (bubbles " + playingBubbles(bard) + ", status " + status(bard) + ")"))
                .thenIdle(STOP_TICKS)
                .thenWaitUntil(() -> helper.assertTrue(status(bard) != JobStatus.PLAYING, "The stops are over (status " + status(bard) + ")"))
                .thenIdle(400)
                .thenExecute(() -> {
                    helper.assertTrue(playingBubbles(bard) == 2, "No third stop: " + playingBubbles(bard));
                    helper.assertTrue(status(bard) != JobStatus.PLAYING && !holdsHorn(bard), "Nothing is played any more: " + status(bard));
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(worker), 1.1),
                            "+1 once a day however many stops: " + ServiceGameTests.entertainment(worker));
                    ServiceGameTests.cleanUp(level, scene, worker);
                })
                .thenSucceed();
    }

    /** Two Bards on two Stages reach one worker: it gets the points of a round stop once. */
    private static void twoBards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper, 1);
        BlockPos second = new BlockPos(1, 1, 7);
        TestSettlements.setBlock(helper, second, ModBlocks.STAGE_BLOCK.get());
        raise(helper, second, 1);
        Settler other = ServiceGameTests.settler(helper, scene.settlement(), Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 7))), "Second", true);
        other.getSkills().set(scene.job(), new SettlerSkills.Skill(BARD_SKILL, 0));
        helper.assertTrue(SettlementWorld.assign(level, helper.absolutePos(second), other, scene.job()) == JobAssignments.AssignResult.OK, "A second Bard");
        Settler worker = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
        Settler bard = scene.performer();
        helper.startSequence()
                .thenExecute(() -> {
                    bard.getWork().decideNow(level);
                    other.getWork().decideNow(level);
                })
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) > 0 && ServiceGameTests.exp(other, scene.job()) > 0,
                        "Both Bards stop at the worker (" + status(bard) + ", " + other.getWork().status(other.getJobs().get(0).jobBlock()) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(worker), 1.1),
                            "Two Bards, one +1 for the day: " + ServiceGameTests.entertainment(worker));
                    ServiceGameTests.cleanUp(level, scene, other, worker);
                })
                .thenSucceed();
    }

    /**
     * Look of a performance: the goat horn is in the hand only while the Bard plays (never in its inventory), the job bubble
     * with the note block shows when the stop starts (an activity bubble, so "Problems only" hides it), and the horn is
     * gone when the stop is over.
     */
    private static void look(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper, 1);
        Settler worker = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
        Settler bard = scene.performer();
        helper.startSequence()
                .thenExecute(() -> bard.getWork().decideNow(level))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(!holdsHorn(bard) && playingBubbles(bard) == 0, "Walking to the worker: no horn, no stop bubble (status " + status(bard) + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(status(bard) == JobStatus.PLAYING, "The stop starts (status " + status(bard) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(holdsHorn(bard), "Playing: the goat horn in the hand");
                    helper.assertTrue(!carriesHorn(bard), "The horn is a display item, never in the inventory");
                    helper.assertTrue(playingBubbles(bard) == 1, "The bubble shows once when the stop starts: " + playingBubbles(bard));
                    helper.assertTrue(bard.getWork().recentThoughts().stream().anyMatch(show -> show.bubble() == ThoughtBubble.PLAYING && show.icon() == Items.NOTE_BLOCK && show.on()),
                            "With the note block: " + bard.getWork().recentThoughts());
                })
                .thenIdle(STOP_TICKS)
                .thenWaitUntil(() -> helper.assertTrue(status(bard) != JobStatus.PLAYING, "The stop is over (status " + status(bard) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(!holdsHorn(bard), "No horn after the stop");
                    helper.assertTrue(playingBubbles(bard) == 1, "No second bubble: " + playingBubbles(bard));
                    ServiceGameTests.cleanUp(level, scene, worker);
                })
                .thenSucceed();
    }

    /** Catch-up walks and plays nothing: the flat +1 of a missed day is all there is. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper, 3);
        Settler worker = worker(helper, scene, WORKER_BLOCK, WORKER_AT, "Worker");
        Settler bard = scene.performer();
        TavernGameTests.catchUpYesterday(helper, bard, worker);
        helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 0 && playingBubbles(bard) == 0, "The Bard played nothing: " + ServiceGameTests.exp(bard, scene.job()));
        helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(worker), 1.0), "Only the flat +1: " + ServiceGameTests.entertainment(worker));
        ServiceGameTests.cleanUp(level, scene, worker);
        helper.succeed();
    }
}
