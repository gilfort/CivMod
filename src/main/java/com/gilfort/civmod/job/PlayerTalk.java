package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.chronicle.Chronicle;
import com.gilfort.civmod.chronicle.Diary;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.inn.VisitorOffer;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.origin.OriginData;
import com.gilfort.civmod.origin.OriginLine;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * A player talks to a settler (vault {@code Systems/Settler Origin.md}, {@code Systems/Entertainment.md}): Shift + right-click with an
 * empty hand. The settler turns its head, greets, shows the speech bubble and says one line in chat to the player who talked.
 * A settler that shows the story bubble ({@link StoryShare}) tells one story aspect it has not told yet, picked at random: a part of
 * its origin or its favourite meal, each with a diary entry. Every other talk is small talk: one topic family picked at random among
 * the families that apply to the settler (the weather, the settlement from its own needs, the time of day, its job and the job's
 * status). The first talk of a day gives a little Entertainment.
 * A visitor of an inn starts every talk with its price and its trade and gets no Entertainment (vault {@code Systems/NPC Population.md}).
 * <p>
 * The told lines belong to the settler, not to a player: every player hears the lines that are left. The class lives in this
 * package because the greeting and the needs are package-private; the settler does not stop what it is doing.
 */
public final class PlayerTalk {

    /**
     * What a talk said.
     *
     * @param line the words, without the settler's name
     * @param told the part of the origin the line told, empty for small talk
     */
    public record Result(Component line, Optional<OriginLine.Slot> told) {
    }

    /** Entertainment points of the first talk with a player per settler and day. */
    static final double TALK_POINTS = 0.5;

    private static final int SMALL_TALK_VARIANTS = 3;
    /** The day time the afternoon starts: 12:00 (0 = 06:00). The evening starts when work ends, at 17:30. */
    private static final long AFTERNOON_START = 6 * DayClock.HOUR_TICKS;

    /** The topic families of small talk, in the order of the random draw that picks one. */
    private enum Family {
        WEATHER, SETTLEMENT, TIME_OF_DAY, JOB
    }

    private static @Nullable RandomSource scripted;

    private PlayerTalk() {
    }

    /**
     * Replaces the random source of the talks (tests): the order of the origin lines and the choice of the small talk.
     *
     * @param random the scripted source, or {@code null} for the settler's own
     */
    public static void script(@Nullable RandomSource random) {
        scripted = random;
    }

    /**
     * Lets a player talk to a settler. A settler that is asleep does not talk; the caller then treats the click like a plain
     * right-click.
     *
     * @param settler the settler
     * @param player  the player who talks to it
     * @return what the settler said, empty if it did not talk
     */
    public static Optional<Result> talk(Settler settler, ServerPlayer player) {
        if (!(settler.level() instanceof ServerLevel level) || settler.isSleeping() || settler.getWork().activity() == Schedule.Activity.SLEEP) {
            return Optional.empty();
        }
        SettlerWork work = settler.getWork();
        Result result = choose(level, settler);
        work.social().greet(player, level.getGameTime());
        work.conversationBubble();
        if (settler.isVisitor()) {
            // A visitor gets no Entertainment and starts every talk with its price; the line of the origin comes after it.
            if (result.told().isPresent()) {
                say(player, settler, VisitorOffer.of(settler).greeting());
            }
        } else {
            work.setEntertainment(work.entertainment().talkedToPlayer(TALK_POINTS, DayClock.day(level)));
        }
        say(player, settler, result.line());
        return Optional.of(result);
    }

    private static void say(ServerPlayer player, Settler settler, Component line) {
        player.sendSystemMessage(Component.translatable("civmod.talk.line", settler.getName(), line));
    }

    /**
     * Decides what the settler says and writes what it tells into the diary. This is the one place that picks the words. A settler
     * tells one story aspect when it shows the story bubble and makes small talk otherwise. A visitor has no needs and no settlement
     * to talk about and no daily share: every talk gives its price and its trade and one part of its origin, and once the origin is
     * told it repeats the price.
     */
    private static Result choose(ServerLevel level, Settler settler) {
        RandomSource random = scripted != null ? scripted : settler.getRandom();
        if (settler.isVisitor()) {
            return tellOriginPart(settler, random).orElseGet(() -> new Result(VisitorOffer.of(settler).greeting(), Optional.empty()));
        }
        return tellStory(level, settler, random).orElseGet(() -> new Result(smallTalk(level, settler, random), Optional.empty()));
    }

    /**
     * The story aspect of the day, picked at random among the untold ones; written into the diary. It uses up the settler's chance of
     * the day and takes the story bubble down. A settler without the bubble tells nothing.
     */
    private static Optional<Result> tellStory(ServerLevel level, Settler settler, RandomSource random) {
        SettlerWork work = settler.getWork();
        if (!work.sharesStory(level)) {
            return Optional.empty();
        }
        List<StoryShare.Aspect> untold = StoryShare.untold(settler);
        StoryShare.Aspect aspect = untold.get(random.nextInt(untold.size()));
        Optional<Result> told = aspect.slot().map(slot -> tellOrigin(settler, slot)).or(() -> tellFavouriteMeal(settler));
        work.spendStoryToday(level);
        work.observe();
        return told;
    }

    /** The favourite meal, once; written into the diary. A settler without a favourite meal tells nothing. */
    private static Optional<Result> tellFavouriteMeal(Settler settler) {
        SettlerFood food = settler.getFood();
        Optional<Identifier> favourite = food.favourite();
        if (favourite.isEmpty() || !food.revealFavourite()) {
            return Optional.empty();
        }
        return Optional.of(new Result(Diary.favouriteMeal(settler, favourite.get(), Diary.Reveal.TOLD), Optional.empty()));
    }

    /** The next part of the origin a visitor has not told yet, picked at random; written into the diary. */
    private static Optional<Result> tellOriginPart(Settler settler, RandomSource random) {
        Optional<SettlerOrigin> origin = settler.getPast().origin();
        if (origin.isEmpty()) {
            return Optional.empty();
        }
        Chronicle chronicle = settler.getPast().chronicle();
        List<OriginLine.Slot> untold = OriginLine.tellable(origin.get()).stream().filter(slot -> !chronicle.hasTold(slot)).toList();
        if (untold.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(tellOrigin(settler, untold.get(random.nextInt(untold.size()))));
    }

    /** One part of the settler's origin; marked as told and written into the diary. */
    private static Result tellOrigin(Settler settler, OriginLine.Slot slot) {
        SettlerOrigin origin = settler.getPast().origin().orElseThrow();
        List<String> params = OriginLine.params(slot, origin, OriginData.race(settler.getRace()));
        settler.getPast().setChronicle(settler.getPast().chronicle().withTold(slot));
        Diary.add(settler, ModDiaryEntryTypes.ORIGIN_LINE.get(), params);
        return new Result(OriginLine.text(params), Optional.of(slot));
    }

    /**
     * One line of small talk. The settler picks one topic family at random with the same chance for every family that applies to it:
     * a homeless settler has no settlement to talk about and no job, a settler without a job has no job topic.
     */
    private static Component smallTalk(ServerLevel level, Settler settler, RandomSource random) {
        List<Family> families = families(settler);
        return switch (families.get(random.nextInt(families.size()))) {
            case WEATHER -> weather(level, random);
            case SETTLEMENT -> settlement(level, settler, random);
            case TIME_OF_DAY -> timeOfDay(level, settler, random);
            case JOB -> job(settler, random);
        };
    }

    private static List<Family> families(Settler settler) {
        List<Family> families = new ArrayList<>();
        families.add(Family.WEATHER);
        if (!settler.isHomeless()) {
            families.add(Family.SETTLEMENT);
        }
        families.add(Family.TIME_OF_DAY);
        if (!settler.isHomeless() && !settler.getJobs().isEmpty()) {
            families.add(Family.JOB);
        }
        return families;
    }

    private static Component weather(ServerLevel level, RandomSource random) {
        String mood = level.isRaining() || level.isThundering() ? "bad" : "good";
        return variant("weather." + mood, random);
    }

    /** The settlement, told from the settler's own needs. */
    private static Component settlement(ServerLevel level, Settler settler, RandomSource random) {
        List<String> unmet = unmetNeeds(level, settler);
        return unmet.isEmpty() ? variant("settlement.fine", random) : variant("settlement." + unmet.get(random.nextInt(unmet.size())), random);
    }

    /** The part of the day: the morning until 12:00, the afternoon until work ends at 17:30, then the evening. */
    private static Component timeOfDay(ServerLevel level, Settler settler, RandomSource random) {
        long dayTime = settler.getWork().moment(level.getOverworldClockTime()).dayTime();
        String part = dayTime < AFTERNOON_START ? "morning" : dayTime < SettlerWork.WORK_END ? "afternoon" : "evening";
        return variant("time." + part, random);
    }

    /**
     * "I work as a Lumberjack." for the job the settler works at now, or its top-priority job while it rests or walks, and a second
     * sentence: the status of that job if it waits on something the player can fix, else that things are running fine.
     */
    private static Component job(Settler settler, RandomSource random) {
        SettlerWork work = settler.getWork();
        SettlerJob job = work.atWork() ? work.activeJob().orElseThrow() : work.jobs().getFirst();
        Component second = work.status(job.jobBlock()).isWaiting()
                ? Component.translatable("civmod.talk.small.job.waiting", work.statusText(job.jobBlock()))
                : variant("job.fine", random);
        return Component.translatable("civmod.talk.small.job", Component.translatable("civmod.talk.small.job.work", job.jobType().indefiniteName()),
                second);
    }

    /** The basic needs the settler complains about now: its bed, a fire near it and food, as the keys of the small talk. */
    private static List<String> unmetNeeds(ServerLevel level, Settler settler) {
        List<String> unmet = new ArrayList<>();
        for (NeedsReport.Row row : settler.getWork().needsReport(level).rows()) {
            if (row.met()) {
                continue;
            }
            if (row.name().equals(Needs.BED.name())) {
                unmet.add("bed");
            } else if (row.name().equals(Needs.FIRE.name())) {
                unmet.add("fire");
            } else if (row.name().equals(Needs.FOOD.name())) {
                unmet.add("food");
            }
        }
        return unmet;
    }

    private static Component variant(String topic, RandomSource random) {
        return Component.translatable("civmod.talk.small." + topic + "." + random.nextInt(SMALL_TALK_VARIANTS));
    }
}
