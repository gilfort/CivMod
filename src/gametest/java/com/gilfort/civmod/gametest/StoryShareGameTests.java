package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.chronicle.Chronicle;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.inn.InnRules;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.job.PlayerTalk;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.job.StoryShare;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.origin.OriginLine;
import com.gilfort.civmod.origin.Origins;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.DayClock;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
            register(helper, "story_share_talk_with_bubble_tells_one", StoryShareGameTests::talkWithBubble);
            register(helper, "story_share_talk_without_bubble_is_small_talk", StoryShareGameTests::talkWithoutBubble);
            register(helper, "story_share_talk_picks_among_untold", StoryShareGameTests::picksAmongUntold);
            register(helper, "story_share_one_aspect_a_day_all_four", StoryShareGameTests::oneAspectADay);
            register(helper, "story_share_talk_first_player_gets_it", StoryShareGameTests::firstPlayerGetsIt);
            register(helper, "story_share_talk_no_origin", StoryShareGameTests::talkWithoutOrigin);
            register(helper, "story_share_visitor_unchanged", StoryShareGameTests::visitorUnchanged);
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
            StoryTalks.setClock(level, day * DayClock.DAY_TICKS + times.wake() - 1L);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).isEmpty(), "No bubble before the waking time: " + story(settler));
            StoryTalks.setClock(level, day * DayClock.DAY_TICKS + times.wake() + 100L);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true))), "The bubble shows after waking: " + story(settler));
            StoryTalks.setClock(level, day * DayClock.DAY_TICKS + times.bed() + 1L);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true), storyShow(settler, false))),
                    "The bubble is gone at bedtime: " + story(settler));
            helper.succeed();
        } finally {
            QuietStories.restore();
            StoryTalks.setClock(level, clock);
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

    /** A settler away on a mission shows no bubble; back before bedtime it shows it again. */
    private static void missionHidesBubble(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper, UUID.randomUUID());
        try {
            StoryShare.script(true);
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true))), "The bubble shows before it leaves: " + story(settler));
            BlockPos board = helper.absolutePos(new BlockPos(1, 1, 1));
            MissionTrip away = new MissionTrip(board, CivMod.id("dungeon_raid"), level.getOverworldClockTime() + DayClock.HOUR_TICKS)
                    .withExit(board.east(20));
            settler.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE, Optional.of(away)));
            helper.assertTrue(settler.getWork().isAway(), "The settler is away");
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true), storyShow(settler, false))),
                    "The bubble is gone while it is away: " + story(settler));
            settler.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE, Optional.empty()));
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).size() == 3 && story(settler).get(2).on(), "Back before bedtime, the chance is still there: " + story(settler));
            helper.succeed();
        } finally {
            QuietStories.restore();
            settler.discard();
        }
    }

    // ---------------------------------------------------------------- the talk

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.add(helper, helper.absoluteVec(new Vec3(3.5, 1.0, 2.5)));
        player.setShiftKeyDown(true);
        return player;
    }

    private static PlayerTalk.Result talk(GameTestHelper helper, Settler settler, ServerPlayer player) {
        return PlayerTalk.talk(settler, player).orElseThrow(() -> helper.assertionException("The settler did not talk"));
    }

    /** A settler with an origin (place, upbringing, turning point) and a favourite meal: four aspects to tell. */
    private static Settler withStory(GameTestHelper helper) {
        Settler settler = handBuilt(helper, UUID.randomUUID());
        Origins.assignEgg(settler, RandomSource.create(4L));
        helper.assertTrue(StoryShare.untold(settler).size() == 4, "Four aspects to tell: " + StoryShare.untold(settler));
        return settler;
    }

    /** How many aspects the settler has told: the origin lines and the favourite meal. */
    private static int told(Settler settler) {
        return settler.getPast().chronicle().told().size() + (settler.getFood().isFavouriteKnown() ? 1 : 0);
    }

    private static boolean isSmallTalk(PlayerTalk.Result result) {
        return result.told().isEmpty() && result.line().getContents() instanceof TranslatableContents contents
                && contents.getKey().startsWith("civmod.talk.small.");
    }

    private static double entertainment(Settler settler) {
        return settler.getWork().entertainment().value();
    }

    /**
     * A talk with the bubble tells one aspect and writes the diary entry, the bubble goes away at once and the first talk of the day gives
     * 0.5 Entertainment. A second talk on the same day is small talk: it tells nothing and writes nothing.
     */
    private static void talkWithBubble(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = withStory(helper);
        ServerPlayer player = player(helper);
        try (StoryTalks unused = StoryTalks.begin(helper)) {
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true))), "The bubble shows: " + story(settler));
            int entries = settler.getPast().chronicle().entries().size();
            double before = entertainment(settler);

            PlayerTalk.Result first = talk(helper, settler, player);
            helper.assertTrue(told(settler) == 1, "One aspect told: " + told(settler));
            helper.assertTrue(first.told().isPresent() || settler.getFood().isFavouriteKnown(), "The line is an origin part or the favourite meal: " + first);
            helper.assertTrue(settler.getPast().chronicle().entries().size() == entries + 1, "One diary entry: " + settler.getPast().chronicle().entries());
            helper.assertTrue(story(settler).equals(List.of(storyShow(settler, true), storyShow(settler, false))), "The bubble is gone: " + story(settler));
            helper.assertTrue(entertainment(settler) == before + 0.5, "The first talk of the day gives 0.5: " + entertainment(settler));

            PlayerTalk.Result second = talk(helper, settler, player);
            helper.assertTrue(isSmallTalk(second), "The second talk of the day is small talk: " + second);
            helper.assertTrue(told(settler) == 1 && settler.getPast().chronicle().entries().size() == entries + 1, "Nothing more told or written");
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).size() == 2, "The bubble stays off for the rest of the day: " + story(settler));
            helper.assertTrue(entertainment(settler) == before + 0.5, "No second gift of Entertainment: " + entertainment(settler));
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** A talk without the bubble is small talk: no origin part, no favourite meal, no diary entry; it still gives the day's Entertainment. */
    private static void talkWithoutBubble(GameTestHelper helper) {
        Settler settler = withStory(helper);
        ServerPlayer player = player(helper);
        try {
            int entries = settler.getPast().chronicle().entries().size();
            PlayerTalk.Result result = talk(helper, settler, player);
            helper.assertTrue(isSmallTalk(result), "Small talk: " + result);
            helper.assertTrue(told(settler) == 0 && !settler.getFood().isFavouriteKnown(), "Nothing told, the favourite stays hidden");
            helper.assertTrue(settler.getPast().chronicle().entries().size() == entries, "Nothing written: " + settler.getPast().chronicle().entries());
            helper.assertTrue(entertainment(settler) == 0.5, "The first talk of the day still gives 0.5: " + entertainment(settler));
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** The aspect is picked at random among the untold ones, the favourite meal included. */
    private static void picksAmongUntold(GameTestHelper helper) {
        Settler first = withStory(helper);
        Settler second = withStory(helper);
        ServerPlayer player = player(helper);
        try (StoryTalks story = StoryTalks.begin(helper)) {
            // The untold aspects are place, upbringing, turning point and the favourite meal, in that order.
            PlayerTalk.script(OriginRolls.picks(3));
            PlayerTalk.Result favourite = story.talkNextDay(helper, first, player);
            helper.assertTrue(first.getFood().isFavouriteKnown() && favourite.told().isEmpty() && first.getPast().chronicle().told().isEmpty(),
                    "The fourth pick is the favourite meal: " + favourite);
            PlayerTalk.script(OriginRolls.picks(0));
            PlayerTalk.Result place = story.talkNextDay(helper, second, player);
            helper.assertTrue(place.told().equals(Optional.of(OriginLine.Slot.PLACE)) && !second.getFood().isFavouriteKnown(), "The first pick is the place: " + place);
            helper.succeed();
        } finally {
            PlayerTalk.script(null);
            TestPlayers.remove(player);
            first.discard();
            second.discard();
        }
    }

    /** One aspect a day: four days tell all four, and the fifth day has nothing to tell, so no bubble and small talk. */
    private static void oneAspectADay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = withStory(helper);
        ServerPlayer player = player(helper);
        try (StoryTalks story = StoryTalks.begin(helper)) {
            for (int day = 1; day <= 4; day++) {
                story.nextDay();
                settler.getWork().decideNow(level);
                PlayerTalk.Result result = talk(helper, settler, player);
                helper.assertTrue(told(settler) == day && !isSmallTalk(result), "Day " + day + " tells the aspect number " + day + ": " + told(settler));
            }
            helper.assertTrue(StoryShare.untold(settler).isEmpty(), "All four are told");
            int shown = story(settler).size();
            story.nextDay();
            settler.getWork().decideNow(level);
            helper.assertTrue(story(settler).size() == shown, "A settler that told everything never rolls again: " + story(settler));
            helper.assertTrue(isSmallTalk(talk(helper, settler, player)), "Small talk on the fifth day");
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** The told aspects belong to the settler: the first player who talks gets the aspect, the second one the same day gets small talk. */
    private static void firstPlayerGetsIt(GameTestHelper helper) {
        Settler settler = withStory(helper);
        ServerPlayer first = player(helper);
        ServerPlayer second = player(helper);
        try (StoryTalks story = StoryTalks.begin(helper)) {
            story.nextDay();
            helper.assertTrue(!isSmallTalk(talk(helper, settler, first)), "The first player gets the aspect");
            helper.assertTrue(isSmallTalk(talk(helper, settler, second)), "The second player gets small talk");
            helper.assertTrue(told(settler) == 1, "One aspect told: " + told(settler));
            helper.assertTrue(entertainment(settler) == 0.5, "One gift of Entertainment per settler and day: " + entertainment(settler));
            helper.succeed();
        } finally {
            TestPlayers.remove(first);
            TestPlayers.remove(second);
            settler.discard();
        }
    }

    /** A settler without an origin tells the favourite meal on a story day, then has nothing left. */
    private static void talkWithoutOrigin(GameTestHelper helper) {
        Settler settler = handBuilt(helper, UUID.randomUUID());
        ServerPlayer player = player(helper);
        try (StoryTalks story = StoryTalks.begin(helper)) {
            PlayerTalk.Result favourite = story.talkNextDay(helper, settler, player);
            helper.assertTrue(favourite.line().getContents() instanceof TranslatableContents contents && contents.getKey().equals("diary.civmod.favourite_meal.told")
                    && settler.getFood().isFavouriteKnown(), "The favourite meal is told: " + favourite);
            helper.assertTrue(isSmallTalk(story.talkNextDay(helper, settler, player)), "Nothing left: small talk on the next story day");
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** A visitor of an inn talks as before on a story day: price and trade, and one origin part per talk, also a second talk the same day. */
    private static void visitorUnchanged(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        InnGameTests.Inn inn = InnGameTests.inn(helper, 1, 1, true);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = InnGameTests.listener(helper, chat);
        InnGameTests.play(helper, inn, InnGameTests.today(helper), InnGameTests.NOON);
        List<Settler> visitors = InnGameTests.visitors(helper);
        helper.assertTrue(visitors.size() == 1, "One visitor came: " + visitors.size());
        Settler visitor = visitors.get(0);
        try (StoryTalks unused = StoryTalks.begin(helper)) {
            for (int talk = 1; talk <= 2; talk++) {
                chat.clear();
                PlayerTalk.Result result = talk(helper, visitor, player);
                helper.assertTrue(chat.size() == 2 && result.told().isPresent(), "Talk " + talk + " on a story day says the price and one origin part: " + chat);
            }
            helper.assertTrue(visitor.getPast().chronicle().told().size() == 2, "Two parts told: " + visitor.getPast().chronicle().told());
            helper.assertTrue(visitor.getPast().chronicle().storySpentDay() == Chronicle.NO_DAY, "A talk does not use up a visitor's story chance");
            helper.assertTrue(!visitor.getFood().isFavouriteKnown(), "A visitor never tells its favourite meal");
            TestPlayers.remove(player);
            InnGameTests.end(helper, inn);
        }
    }
}
