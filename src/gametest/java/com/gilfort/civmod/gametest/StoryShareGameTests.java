package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.chronicle.Chronicle;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.StoryShare;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.origin.OriginLine;
import com.gilfort.civmod.origin.Origins;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.DayClock;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the daily story share (vault {@code Systems/Settler Origin.md}, {@code Systems/Thought Bubbles.md}): the roll and its
 * config, the story bubble a settler with something to tell shows and the talk that tells one story aspect. The roll is quiet in the
 * GameTests ({@link QuietStories}); a test forces it with {@code StoryShare.script} and gives the quiet roll back at the end.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StoryShareGameTests {

    private static final int ROLLED_DAYS = 3000;
    private static final Vec3 SETTLER_AT = new Vec3(2.5, 1.0, 2.5);

    private StoryShareGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "story_share_roll_per_day", StoryShareGameTests::rollPerDay);
            register(helper, "story_share_config", StoryShareGameTests::config);
            register(helper, "story_share_spent_day_saved", StoryShareGameTests::spentDaySaved);
            register(helper, "story_share_bubble_kind", StoryShareGameTests::bubbleKind);
            register(helper, "story_share_bubble_on_and_off", StoryShareGameTests::bubbleOnAndOff);
            register(helper, "story_share_bubble_window", StoryShareGameTests::bubbleWindow);
            register(helper, "story_share_all_told_never_rolls", StoryShareGameTests::allTold);
            register(helper, "story_share_no_origin_favourite_only", StoryShareGameTests::noOrigin);
            register(helper, "story_share_mission_hides_bubble", StoryShareGameTests::missionHidesBubble);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** A hand-built Human settler that does not think, with a favourite meal and no origin. */
    private static Settler handBuilt(GameTestHelper helper, UUID id) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 at = helper.absoluteVec(SETTLER_AT);
        settler.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        settler.setUUID(id);
        settler.setRace(Races.HUMAN);
        settler.setNoAi(true);
        settler.getFood().setFavourite(BuiltInRegistries.ITEM.getKey(Items.BREAD));
        level.addFreshEntity(settler);
        return settler;
    }

    /** The first settler UUID whose waking time lies after 06:00, so that the time before waking exists. */
    private static UUID lateRiser(long day) {
        long i = 1;
        while (Schedule.dayTimes(new UUID(0L, i), day).wake() <= 0L) {
            i++;
        }
        return new UUID(0L, i);
    }

    private static void setClock(ServerLevel level, long clock) {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), clock);
    }

    /** The story bubbles the settler sent so far, oldest first. */
    private static List<ThoughtBubble.Show> story(Settler settler) {
        return settler.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.STORY).toList();
    }

    private static ThoughtBubble.Show storyShow(Settler settler, boolean on) {
        return new ThoughtBubble.Show(settler.getId(), ThoughtBubble.STORY, Items.WRITABLE_BOOK, on);
    }

    // ---------------------------------------------------------------- the roll

    private static int hits(UUID settler, int days) {
        int hits = 0;
        for (int day = 0; day < days; day++) {
            if (StoryShare.hit(settler, day)) {
                hits++;
            }
        }
        return hits;
    }

    /** The roll is the same for a settler and a day however often it is asked (a reload cannot reroll), and a settler hits about 1 day in 3. */
    private static void rollPerDay(GameTestHelper helper) {
        StoryShare.script(null);
        try {
            UUID settler = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
            for (int day = 0; day < 50; day++) {
                boolean asked = StoryShare.hit(settler, day);
                boolean askedAgain = StoryShare.hit(settler, day);
                helper.assertTrue(asked == askedAgain, "Day " + day + " rolls the same every time");
            }
            int hits = hits(settler, ROLLED_DAYS);
            helper.assertTrue(hits > ROLLED_DAYS * 0.30 && hits < ROLLED_DAYS * 0.37, "About 1 day in 3 hits: " + hits + " of " + ROLLED_DAYS);
            boolean differ = false;
            for (int day = 0; day < 50 && !differ; day++) {
                differ = StoryShare.hit(settler, day) != StoryShare.hit(other, day);
            }
            helper.assertTrue(differ, "Two settlers do not share their days");
            helper.succeed();
        } finally {
            QuietStories.restore();
        }
    }

    /** The server config sets N: 1 hits every day, 5 about 1 day in 5. */
    private static void config(GameTestHelper helper) {
        StoryShare.script(null);
        int oneIn = CivModConfig.SETTLER_STORY_SHARE_ONE_IN.getAsInt();
        try {
            helper.assertTrue(oneIn == 3, "The default is 1 day in 3: " + oneIn);
            UUID settler = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
            CivModConfig.SETTLER_STORY_SHARE_ONE_IN.set(1);
            helper.assertTrue(hits(settler, 200) == 200, "1 means every day: " + hits(settler, 200));
            CivModConfig.SETTLER_STORY_SHARE_ONE_IN.set(5);
            int hits = hits(settler, ROLLED_DAYS);
            helper.assertTrue(hits > ROLLED_DAYS * 0.17 && hits < ROLLED_DAYS * 0.23, "About 1 day in 5 hits: " + hits + " of " + ROLLED_DAYS);
            helper.succeed();
        } finally {
            CivModConfig.SETTLER_STORY_SHARE_ONE_IN.set(oneIn);
            QuietStories.restore();
        }
    }

    /** The day a settler used up its chance is saved with its chronicle; a chronicle from an older save has none. */
    private static void spentDaySaved(GameTestHelper helper) {
        Chronicle spent = Chronicle.EMPTY.withTold(OriginLine.Slot.PLACE).withStorySpent(42L);
        var encoded = Chronicle.CODEC.encodeStart(JsonOps.INSTANCE, spent).getOrThrow();
        Chronicle decoded = Chronicle.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        helper.assertTrue(decoded.equals(spent) && decoded.storySpentDay() == 42L, "Saved and loaded: " + decoded);
        helper.assertTrue(Chronicle.EMPTY.storySpentDay() == Chronicle.NO_DAY, "A new chronicle has no spent day");
        var old = Chronicle.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow();
        helper.assertTrue(old.equals(Chronicle.EMPTY), "A chronicle without the field loads as empty: " + old);
        helper.succeed();
    }

    // ---------------------------------------------------------------- the story bubble

    /** The story bubble is a speech bubble without the red mark that repeats like a problem; "Problems only" hides it as it hides the talent bubble. */
    private static void bubbleKind(GameTestHelper helper) {
        ThoughtBubble story = ThoughtBubble.STORY;
        helper.assertTrue(story.speech() && story.repeats() && !story.problem(), "A speech bubble that repeats and is no problem");
        helper.assertTrue(ThoughtBubble.NO_TOOL.repeats() && ThoughtBubble.NO_TOOL.problem() && !ThoughtBubble.NO_TOOL.speech(), "A problem still repeats");
        helper.assertFalse(ThoughtBubble.CONVERSATION.repeats() || ThoughtBubble.TALENT.repeats(), "Activity bubbles show once");
        helper.assertTrue(story.showsThisTime(RandomSource.create(1L)), "It shows every time");
        helper.succeed();
    }

    /** A hit turns the bubble on once, with the book and quill; no hit sends nothing. */
    private static void bubbleOnAndOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper, UUID.randomUUID());
        Settler quiet = handBuilt(helper, UUID.randomUUID());
        try {
            StoryShare.script(true);
            settler.getWork().decideNow(level);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true))), "One packet \"on\", not one per look: " + story(settler));
            StoryShare.script(false);
            quiet.getWork().decideNow(level);
            helper.assertTrue(story(quiet).isEmpty(), "No hit, no bubble: " + story(quiet));
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true), storyShow(settler, false))),
                    "The bubble is turned off when the chance is gone: " + story(settler));
            helper.succeed();
        } finally {
            QuietStories.restore();
            settler.discard();
            quiet.discard();
        }
    }

    /** The chance lasts from the waking time to the bedtime: no bubble before waking, one by day, off at bedtime. */
    private static void bubbleWindow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long clock = level.getOverworldClockTime();
        long day = DayClock.day(clock);
        UUID id = lateRiser(day);
        Settler settler = handBuilt(helper, id);
        try {
            StoryShare.script(true);
            Schedule.DayTimes times = settler.getWork().dayTimes(day);
            setClock(level, day * DayClock.DAY_TICKS + times.wake() - 1L);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).isEmpty(), "No bubble before the waking time: " + story(settler));
            setClock(level, day * DayClock.DAY_TICKS + times.wake() + 100L);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true))), "The bubble shows after waking: " + story(settler));
            setClock(level, day * DayClock.DAY_TICKS + times.bed() + 1L);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true), storyShow(settler, false))),
                    "The bubble is gone at bedtime: " + story(settler));
            helper.succeed();
        } finally {
            QuietStories.restore();
            setClock(level, clock);
            settler.discard();
        }
    }

    /** A settler that has told all four aspects never rolls: no bubble even on a hit. */
    private static void allTold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper, UUID.randomUUID());
        try {
            Origins.assignEgg(settler, RandomSource.create(4L));
            Chronicle chronicle = settler.getPast().chronicle();
            for (OriginLine.Slot slot : OriginLine.Slot.values()) {
                chronicle = chronicle.withTold(slot);
            }
            settler.getPast().setChronicle(chronicle);
            settler.getFood().revealFavourite();
            StoryShare.script(true);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).isEmpty(), "Nothing left to tell, no bubble: " + story(settler));
            helper.succeed();
        } finally {
            QuietStories.restore();
            settler.discard();
        }
    }

    /** A settler without an origin has the favourite meal as its only aspect: the bubble shows while it is hidden and goes when it is known. */
    private static void noOrigin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper, UUID.randomUUID());
        try {
            helper.assertTrue(settler.getPast().origin().isEmpty(), "No origin");
            StoryShare.script(true);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true))), "The favourite meal is an aspect to tell: " + story(settler));
            settler.getFood().revealFavourite();
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true), storyShow(settler, false))),
                    "Known now, nothing left to tell: " + story(settler));
            helper.succeed();
        } finally {
            QuietStories.restore();
            settler.discard();
        }
    }

    /** A settler away on a mission shows no bubble. */
    private static void missionHidesBubble(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = MissionRunGameTests.adventurer(helper);
        settler.getFood().setFavourite(BuiltInRegistries.ITEM.getKey(Items.BREAD));
        StoryShare.script(true);
        long clock = level.getOverworldClockTime();
        com.gilfort.civmod.job.MissionTrip trip = new com.gilfort.civmod.job.MissionTrip(helper.absolutePos(MissionRunGameTests.BOARD),
                CivMod.id("dungeon_raid"), clock + 6000L);
        settler.getWork().restore(new com.gilfort.civmod.job.SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(),
                com.gilfort.civmod.job.SettlerWork.CatchUpState.NONE, Optional.of(trip)));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().isAway() && settler.isInvisible(), "The settler is away");
            List<ThoughtBubble.Show> shown = story(settler);
            helper.assertTrue(shown.isEmpty() || shown.get(shown.size() - 1).equals(storyShow(settler, false)), "No bubble while away: " + shown);
            QuietStories.restore();
            settler.discard();
        });
    }
}
