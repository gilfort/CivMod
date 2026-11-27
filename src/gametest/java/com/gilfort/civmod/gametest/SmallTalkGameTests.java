package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.PlayerTalk;
import com.gilfort.civmod.job.TalkJobStatus;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the topics of small talk (vault {@code Systems/Settler Origin.md}, story #343): which family a talk picks, the three
 * parts of the day, the job topic with and without a waiting status, and the settlers that do not have every family. The seam is
 * {@link PlayerTalk#talk} with a scripted random source: the first draw picks the family, the next ones its words. The story roll is
 * quiet in the GameTests ({@link QuietStories}), so every talk here is small talk.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SmallTalkGameTests {

    private static final Vec3 PLAYER_AT = new Vec3(3.5, 1.0, 2.5);
    private static final BlockPos JOB_BLOCK = new BlockPos(2, 1, 2);
    private static final BlockPos OTHER_JOB_BLOCK = new BlockPos(5, 1, 2);
    /** Day times of the parts of the day, as the spec states them (0 = 06:00): noon is 6000, 17:30 is 11500. */
    private static final long NOON = 6000L;
    private static final long END_OF_WORK = 11500L;

    private SmallTalkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "small_talk_family_each", SmallTalkGameTests::eachFamily);
            register(helper, "small_talk_family_equal_chance", SmallTalkGameTests::equalChance);
            register(helper, "small_talk_time_of_day_parts", SmallTalkGameTests::timeOfDayParts);
            register(helper, "small_talk_time_of_day_wordings", SmallTalkGameTests::timeOfDayWordings);
            register(helper, "small_talk_job_running_fine", SmallTalkGameTests::jobRunningFine);
            register(helper, "small_talk_job_waiting_status", SmallTalkGameTests::jobWaitingStatus);
            register(helper, "small_talk_job_top_priority", SmallTalkGameTests::jobTopPriority);
            register(helper, "small_talk_job_while_delivering", SmallTalkGameTests::jobWhileDelivering);
            register(helper, "small_talk_homeless_weather_and_time_only", SmallTalkGameTests::homelessTopics);
            register(helper, "small_talk_jobless_has_no_job_topic", SmallTalkGameTests::joblessTopics);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** A settler of a settlement with a Lumberjack at one JobBlock, the Miner at another one if asked, and the clock it was made at. */
    private record Scene(GameTestHelper helper, Settler settler, ServerPlayer player, long clock, BlockPos jobBlock, BlockPos otherJobBlock) {

        void cleanup() {
            PlayerTalk.script(null);
            StoryTalks.setClock(helper.getLevel(), clock);
            TestPlayers.remove(player);
            settler.discard();
            SettlementData data = SettlementData.get(helper.getLevel());
            SettlementManager.remove(data, jobBlock, 0L, SettlementManager.Listener.NONE);
        }

        /** Talks with the scripted picks and returns the line. */
        Component say(int... picks) {
            PlayerTalk.script(OriginRolls.picks(picks));
            return PlayerTalk.talk(settler, player).orElseThrow(() -> helper.assertionException("The settler did not talk")).line();
        }

        /** Moves the clock to a time of the day on the day it is now. */
        void at(long dayTime) {
            StoryTalks.setClock(helper.getLevel(), DayClock.day(clock) * DayClock.DAY_TICKS + dayTime);
        }
    }

    /** A settler that lives in a settlement and has no job. */
    private static Scene jobless(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = helper.absolutePos(JOB_BLOCK);
        TestSettlements.place(helper, jobBlock, ModJobs.LUMBERJACK.get(), 5);
        TestSettlements.place(helper, helper.absolutePos(OTHER_JOB_BLOCK), ModJobs.MINER.get(), 5);
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 1.0, 2.5));
        settler.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        settler.setRace(Races.HUMAN);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        ServerPlayer player = TestPlayers.add(helper, helper.absoluteVec(PLAYER_AT));
        long clock = level.getOverworldClockTime();
        return new Scene(helper, settler, player, clock, jobBlock, helper.absolutePos(OTHER_JOB_BLOCK));
    }

    /** A settler with the Lumberjack job first and, if asked, the Miner job second. */
    private static Scene working(GameTestHelper helper, boolean withSecondJob) {
        Scene scene = jobless(helper);
        scene.settler().addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), scene.jobBlock()));
        if (withSecondJob) {
            scene.settler().addJob(new SettlerJob(ModJobs.MINER.get(), scene.otherJobBlock()));
        }
        return scene;
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "not translatable: " + text;
    }

    private static Object[] args(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getArgs() : new Object[0];
    }

    private static Component work(Job job) {
        return Component.translatable("civmod.talk.small.job.work", job.indefiniteName());
    }

    // ---------------------------------------------------------------- the families

    /** With a settlement and a job every family can be picked: the first draw 0 is the weather, 1 the settlement, 2 the time of day, 3 the job. */
    private static void eachFamily(GameTestHelper helper) {
        Scene scene = working(helper, false);
        try {
            scene.at(0L);
            helper.assertTrue(key(scene.say(0, 1)).startsWith("civmod.talk.small.weather."), "Pick 0 is the weather");
            helper.assertTrue(key(scene.say(1, 0, 1)).startsWith("civmod.talk.small.settlement."), "Pick 1 is the settlement");
            String time = key(scene.say(2, 1));
            helper.assertTrue(time.equals("civmod.talk.small.time.morning.1"), "Pick 2 is the time of day: " + time);
            String job = key(scene.say(3, 1));
            helper.assertTrue(job.equals("civmod.talk.small.job"), "Pick 3 is the job: " + job);
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    /** The draw that picks the family has one slot per family that applies, so each has the same chance (no 1 in 2 weather roll). */
    private static void equalChance(GameTestHelper helper) {
        Scene scene = working(helper, false);
        try {
            helper.assertTrue(firstBound(scene) == 4, "Weather, settlement, time of day and job: " + firstBound(scene));
            scene.settler().becomeHomeless();
            helper.assertTrue(firstBound(scene) == 2, "A homeless settler: weather and time of day: " + firstBound(scene));
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    /** @return the bound of the first draw of a talk, which picks the family */
    private static int firstBound(Scene scene) {
        List<Integer> bounds = new ArrayList<>();
        PlayerTalk.script(new LegacyRandomSource(0L) {
            @Override
            public int nextInt(int bound) {
                bounds.add(bound);
                return 0;
            }
        });
        PlayerTalk.talk(scene.settler(), scene.player());
        return bounds.getFirst();
    }

    // ---------------------------------------------------------------- the time of day

    /** Morning runs from waking to 12:00, the afternoon to 17:30 and the evening to bedtime. */
    private static void timeOfDayParts(GameTestHelper helper) {
        Scene scene = working(helper, false);
        try {
            long[] times = {0L, 1000L, NOON - 1, NOON, 9000L, END_OF_WORK - 1, END_OF_WORK, 13000L};
            String[] parts = {"morning", "morning", "morning", "afternoon", "afternoon", "afternoon", "evening", "evening"};
            for (int i = 0; i < times.length; i++) {
                scene.at(times[i]);
                String key = key(scene.say(2, 0));
                helper.assertTrue(key.equals("civmod.talk.small.time." + parts[i] + ".0"), "At day time " + times[i] + " it is the " + parts[i] + ": " + key);
            }
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    /** Each part of the day has three wordings, picked by the draw after the family. */
    private static void timeOfDayWordings(GameTestHelper helper) {
        Scene scene = working(helper, false);
        try {
            for (String part : List.of("morning", "afternoon", "evening")) {
                scene.at(part.equals("morning") ? 1000L : part.equals("afternoon") ? 9000L : 13000L);
                for (int variant = 0; variant < 3; variant++) {
                    String key = key(scene.say(2, variant));
                    helper.assertTrue(key.equals("civmod.talk.small.time." + part + "." + variant), "Wording " + variant + " of the " + part + ": " + key);
                }
            }
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    // ---------------------------------------------------------------- the job

    /** "I work as a Lumberjack." and, while the job does not wait, one of three lines that things are fine. */
    private static void jobRunningFine(GameTestHelper helper) {
        Scene scene = working(helper, false);
        try {
            // Statuses that are no waiting status: the job reads fine, also for the ones that look like trouble.
            for (JobStatus status : List.of(JobStatus.IDLE, JobStatus.NO_REQUEST, JobStatus.NOTHING_TO_PRODUCE, JobStatus.PAUSING)) {
                TalkJobStatus.set(scene.settler(), scene.jobBlock(), status);
                for (int variant = 0; variant < 3; variant++) {
                    Component line = scene.say(3, variant);
                    Object[] args = args(line);
                    helper.assertTrue(key(line).equals("civmod.talk.small.job") && args.length == 2, status + ": the job line: " + line);
                    helper.assertTrue(args[0].equals(work(ModJobs.LUMBERJACK.get())), status + ": it names the job: " + args[0]);
                    helper.assertTrue(args[1] instanceof Component second && key(second).equals("civmod.talk.small.job.fine." + variant),
                            status + ": things are running fine, wording " + variant + ": " + args[1]);
                }
            }
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    /** A waiting status of the "Try again" list is named in a second sentence; every other status reads fine. */
    private static void jobWaitingStatus(GameTestHelper helper) {
        Scene scene = working(helper, false);
        try {
            // The list of the ticket, written out on purpose: the test checks it, not the code.
            List<JobStatus> waiting = List.of(JobStatus.NO_TOOL, JobStatus.NO_FOOD, JobStatus.NO_WORK_PLACE, JobStatus.NO_FREE_WORK_PLACE,
                    JobStatus.DROP_OFF_FULL, JobStatus.STORAGE_FULL, JobStatus.BLOCKED, JobStatus.MISSING_INPUTS, JobStatus.MISSING_FUEL,
                    JobStatus.NO_MISSION);
            for (JobStatus status : JobStatus.values()) {
                TalkJobStatus.set(scene.settler(), scene.jobBlock(), status);
                Object second = args(scene.say(3, 1))[1];
                if (waiting.contains(status)) {
                    helper.assertTrue(second.equals(Component.translatable("civmod.talk.small.job.waiting", status.displayName())),
                            status + ": the second sentence names the status: " + second);
                } else {
                    helper.assertTrue(second instanceof Component text && key(text).equals("civmod.talk.small.job.fine.1"),
                            status + ": no waiting status, so things are fine: " + second);
                }
            }
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    /** A settler that is not at work (it rests or walks) talks about its top-priority job and that job's status. */
    private static void jobTopPriority(GameTestHelper helper) {
        Scene scene = working(helper, true);
        try {
            TalkJobStatus.set(scene.settler(), scene.otherJobBlock(), JobStatus.NO_TOOL);
            Component line = scene.say(3, 0);
            helper.assertTrue(args(line)[0].equals(work(ModJobs.LUMBERJACK.get())), "It names the job first in its list: " + args(line)[0]);
            helper.assertTrue(args(line)[1] instanceof Component second && key(second).equals("civmod.talk.small.job.fine.0"),
                    "The status of the second job does not count: " + args(line)[1]);
            TalkJobStatus.set(scene.settler(), scene.jobBlock(), JobStatus.NO_TOOL);
            helper.assertTrue(args(scene.say(3, 0))[1].equals(Component.translatable("civmod.talk.small.job.waiting", JobStatus.NO_TOOL.displayName())),
                    "The status of the first job counts");
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    /** A settler that delivers talks about the job it delivers for, also when that is its second job, and about that job's status. */
    private static void jobWhileDelivering(GameTestHelper helper) {
        Scene scene = working(helper, true);
        try {
            TalkJobStatus.startDelivery(helper.getLevel(), scene.settler(), scene.otherJobBlock());
            TalkJobStatus.set(scene.settler(), scene.jobBlock(), JobStatus.NO_FOOD);
            Component line = scene.say(3, 0);
            helper.assertTrue(args(line)[0].equals(work(ModJobs.MINER.get())), "It names the job it delivers for: " + args(line)[0]);
            helper.assertTrue(args(line)[1] instanceof Component second && key(second).equals("civmod.talk.small.job.fine.0"),
                    "The status of the first job does not count: " + args(line)[1]);
            TalkJobStatus.set(scene.settler(), scene.otherJobBlock(), JobStatus.NO_TOOL);
            helper.assertTrue(args(scene.say(3, 0))[1].equals(Component.translatable("civmod.talk.small.job.waiting", JobStatus.NO_TOOL.displayName())),
                    "The status of the job it delivers for counts");
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    // ---------------------------------------------------------------- the settlers that miss a family

    /** A homeless settler talks about the weather and the time of day only. */
    private static void homelessTopics(GameTestHelper helper) {
        Scene scene = working(helper, false);
        try {
            scene.settler().becomeHomeless();
            scene.at(1000L);
            // Two families apply, so the draw has two slots; every pick lands on one of them.
            for (int pick = 0; pick < 8; pick++) {
                String key = key(scene.say(pick, 0));
                helper.assertTrue(key.startsWith("civmod.talk.small.weather.") || key.startsWith("civmod.talk.small.time."), "Pick " + pick + ": " + key);
            }
            helper.assertTrue(key(scene.say(0, 0)).startsWith("civmod.talk.small.weather."), "Pick 0 is the weather");
            helper.assertTrue(key(scene.say(1, 0)).startsWith("civmod.talk.small.time."), "Pick 1 is the time of day");
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }

    /** A settler of a settlement without a job has no job topic: three families, and the fourth pick wraps to the first. */
    private static void joblessTopics(GameTestHelper helper) {
        Scene scene = jobless(helper);
        try {
            scene.at(1000L);
            for (int pick = 0; pick < 8; pick++) {
                String key = key(scene.say(pick, 0, 0));
                helper.assertFalse(key.startsWith("civmod.talk.small.job"), "Pick " + pick + " is no job topic: " + key);
            }
            helper.assertTrue(key(scene.say(2, 0)).startsWith("civmod.talk.small.time."), "Pick 2 is the time of day");
            helper.assertTrue(key(scene.say(3, 0)).startsWith("civmod.talk.small.weather."), "Pick 3 wraps: three families apply");
            helper.succeed();
        } finally {
            scene.cleanup();
        }
    }
}
