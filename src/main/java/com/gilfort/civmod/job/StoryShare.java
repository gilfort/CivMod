package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.chronicle.Chronicle;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerDay;
import com.gilfort.civmod.origin.OriginLine;
import com.gilfort.civmod.origin.SettlerOrigin;

/**
 * The daily story share of a settler (vault {@code Systems/Settler Origin.md}): on 1 day in N (the server config
 * {@code settlerStoryShareOneIn}) a settler has a story aspect to tell, from its waking time to its bedtime. Then it shows the story
 * bubble, and a talk with the bubble tells one aspect. The roll comes from the settler and the day and is never saved, so a reload
 * cannot reroll it; the only thing saved is the day the chance was used up ({@link Chronicle#storySpentDay}).
 */
public final class StoryShare {

    /**
     * A part of a settler's story that a talk can tell: the lines that give real information, as against small talk.
     */
    public enum Aspect {
        /** Where the settler comes from. */
        PLACE(Optional.of(OriginLine.Slot.PLACE)),
        /** How it grew up. */
        UPBRINGING(Optional.of(OriginLine.Slot.UPBRINGING)),
        /** What turned its life around. */
        TURNING_POINT(Optional.of(OriginLine.Slot.TURNING_POINT)),
        /** The meal it likes best. */
        FAVOURITE_MEAL(Optional.empty());

        private final Optional<OriginLine.Slot> slot;

        Aspect(Optional<OriginLine.Slot> slot) {
            this.slot = slot;
        }

        /**
         * @return the origin line this aspect tells, empty for the favourite meal
         */
        public Optional<OriginLine.Slot> slot() {
            return slot;
        }

        private static Aspect of(OriginLine.Slot slot) {
            return switch (slot) {
                case PLACE -> PLACE;
                case UPBRINGING -> UPBRINGING;
                case TURNING_POINT -> TURNING_POINT;
            };
        }
    }

    /** Tells the story roll apart from the other values drawn from a settler and a day. */
    private static final long SALT_STORY = 4L;

    private static @Nullable Boolean scripted;

    private StoryShare() {
    }

    /**
     * Forces the daily roll (tests).
     *
     * @param hit {@code true} for a hit every day, {@code false} for none, {@code null} for the real roll
     */
    public static void script(@Nullable Boolean hit) {
        scripted = hit;
    }

    /**
     * Rolls the story share of a settler for a day: the same answer every time it is asked.
     *
     * @param settler the settler's UUID
     * @param day     the day
     * @return whether the settler has something to tell that day
     */
    public static boolean hit(UUID settler, long day) {
        if (scripted != null) {
            return scripted;
        }
        return SettlerDay.pick(settler, day, SALT_STORY, CivModConfig.SETTLER_STORY_SHARE_ONE_IN.getAsInt()) == 0;
    }

    /**
     * Whether a settler has a story aspect to tell now: it is awake between its waking time and its bedtime on a day that hit, has not
     * used up the day's chance, is not away and still has an aspect left. A visitor of an inn never shares this way.
     *
     * @param settler the settler
     * @param moment  its day and day time now
     * @param times   its waking, work end and bed times on that day
     * @param away    whether it is away on a mission
     * @return whether it shows the story bubble and tells an aspect when talked to
     */
    static boolean shares(Settler settler, Schedule.Moment moment, Schedule.DayTimes times, boolean away) {
        if (settler.isVisitor() || away || moment.dayTime() < times.wake() || moment.dayTime() >= times.bed()) {
            return false;
        }
        return settler.getPast().chronicle().storySpentDay() < moment.day() && hit(settler.getUUID(), moment.day()) && !untold(settler).isEmpty();
    }

    /**
     * @param settler the settler
     * @return the aspects of its story it has not told yet: the origin lines it has and the favourite meal while it is hidden
     */
    static List<Aspect> untold(Settler settler) {
        List<Aspect> untold = new ArrayList<>();
        Optional<SettlerOrigin> origin = settler.getPast().origin();
        if (origin.isPresent()) {
            Chronicle chronicle = settler.getPast().chronicle();
            for (OriginLine.Slot slot : OriginLine.tellable(origin.get())) {
                if (!chronicle.hasTold(slot)) {
                    untold.add(Aspect.of(slot));
                }
            }
        }
        SettlerFood food = settler.getFood();
        if (food.favourite().isPresent() && !food.isFavouriteKnown()) {
            untold.add(Aspect.FAVOURITE_MEAL);
        }
        return untold;
    }
}
