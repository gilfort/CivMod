package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.SettlerDay;

/**
 * The daily schedule of a settler (vault {@code Systems/NPC Daily Schedule.md}, {@code Systems/Needs.md}): a
 * {@link Snapshot} of plain values goes in, an {@link Intent} comes out. It does not touch the world, navigation or
 * the inventory; the Settler Shell builds the snapshot and acts on the intent. Live play and catch-up ask the same
 * rules.
 * <p>
 * A decision has two steps. {@link #wakeUp} settles the body first (stay asleep, wake, return to the rest place after a
 * skipped night, keep foraging, come back from foraging); the shell acts on it and asks again until it is empty. Then
 * {@link #next} decides what the awake settler does.
 */
public final class Schedule {

    /** Length of every Schedule window in day ticks (30 minutes). */
    public static final long WINDOW_TICKS = 500L;
    /** Start of the waking window (06:00; day time 0 = 06:00). */
    public static final long WAKE_WINDOW_START = 0L;

    private static final long SALT_WAKE = 1L;
    private static final long SALT_WORK_END = 2L;
    private static final long SALT_BED = 3L;

    private Schedule() {
    }

    /**
     * One settler's points of the daily schedule on one day, each at its daily offset inside its Schedule window.
     *
     * @param wake    the day time the settler wakes and starts work (06:00–06:30)
     * @param workEnd the day time its work ends (17:30–18:00; a Clerk at the end of its shift, 18:30)
     * @param bed     the day time it goes to bed (20:00–20:30)
     */
    public record DayTimes(long wake, long workEnd, long bed) {
    }

    /**
     * The settler's points of the daily schedule on a day: deterministic from the settler and the day, never saved, the
     * same in live play and catch-up.
     *
     * @param settler the settler
     * @param day     the day
     * @param clerk   whether the settler is a Clerk (its work ends with its shift at 18:30)
     * @return the times
     */
    public static DayTimes dayTimes(UUID settler, long day, boolean clerk) {
        long wake = WAKE_WINDOW_START + offset(settler, day, SALT_WAKE);
        long workEnd = clerk ? ClerkStorage.SHIFT_END : SettlerWork.WORK_END + offset(settler, day, SALT_WORK_END);
        long bed = SettlerWork.SLEEP_TIME + offset(settler, day, SALT_BED);
        return new DayTimes(wake, workEnd, bed);
    }

    private static long offset(UUID settler, long day, long salt) {
        return SettlerDay.pick(settler, day, salt, (int) WINDOW_TICKS);
    }

    /** What a settler is doing right now. {@code JOB} is a step of the active job's category loop. */
    public enum Activity { IDLE, JOB, DELIVER, GO_TO_BED, SLEEP, FETCH_FOOD, EATING, FORAGE }

    /** Why a settler walks to a food point. */
    public enum MealPurpose { MORNING, HUNGRY, EVENING }

    /** What the settler does next. */
    public enum Intent {
        /** Stay asleep (night). */
        SLEEP,
        /** Wake up (the night is over). */
        WAKE,
        /** The night was skipped while not in bed: appear at the rest place as if the settler slept there. */
        RETURN_TO_REST_PLACE,
        /** Go on foraging (walk out, stay away). */
        FORAGE,
        /**
         * Foraging is over (end of work, or a later day): come back with the day's food
         * ({@code foragingFood()}) and decide as usual.
         */
        END_FORAGING,
        /** Keep doing the current activity (a meal in progress, or the job step holds the decision). */
        CONTINUE,
        /** Stand still (homeless, or starving). */
        IDLE,
        /** Eat one item from the pockets on the spot. */
        EAT_FROM_POCKETS,
        /** Eat from the pockets on the spot until full (evening meal without food at a food point). */
        EAT_UNTIL_FULL,
        /** Walk to the nearest food point (Clerk JobBlock) with food, for a {@link MealPurpose}. */
        FETCH_FOOD,
        /** Start foraging for the whole day. */
        START_FORAGING,
        /** Deliver what is left in a job's buffer after work. */
        DELIVER,
        /** Rest near the rest place in the evening. */
        REST,
        /** Go to the rest place and sleep (from the settler's bedtime, 20:00–20:30). */
        GO_TO_BED,
        /** Work the first job of the priority list that can work (Laborer fallback: idle). */
        WORK
    }

    /**
     * The settler's situation at a decision.
     *
     * @param day              the day
     * @param dayTime          the day time (0 = 06:00)
     * @param food             the food level
     * @param provisions       nutrition carried in the pockets
     * @param provisionsWanted nutrition the settler wants to carry
     * @param pocketMeal       whether the pockets hold something the settler may eat now
     * @param foodPoint        whether a food point (Clerk JobBlock) of the settlement has food for the settler; a container
     *                         lookup, so it is asked only
     *                         when a rule needs it
     * @param homeless         whether the settler has no settlement
     * @param sleeping         whether the settler is asleep
     * @param forageDay        the day the settler forages on, {@link SettlerFood#NO_DAY} if it is not foraging
     * @param nightSkipped     whether the night was skipped since the last decision
     * @param activity         the current activity
     * @param jobStepHolds     whether the current job step holds the decision (fatigue break, walking out for a
     *                         mission); asked only during a job step, it may act (the category loop's own rules)
     * @param morningHandled   whether the morning plan was made today
     * @param eveningMealEaten whether the evening meal was handled today
     * @param deliveryPending  whether a job has items to deliver to a drop-off that is not full
     * @param times            the settler's waking, end of work and bed times today ({@link #dayTimes})
     */
    public record Snapshot(long day, long dayTime, int food, int provisions, int provisionsWanted, boolean pocketMeal,
            BooleanSupplier foodPoint, boolean homeless, boolean sleeping, long forageDay, boolean nightSkipped,
            Activity activity, BooleanSupplier jobStepHolds, boolean morningHandled, boolean eveningMealEaten,
            boolean deliveryPending, DayTimes times) {

        /**
         * @return whether the settler is foraging (today, or still from an earlier day)
         */
        public boolean foraging() {
            return forageDay != SettlerFood.NO_DAY;
        }
    }

    /**
     * What the awake settler does next.
     *
     * @param intent             the intent
     * @param purpose            why it fetches food ({@link Intent#FETCH_FOOD} only)
     * @param complain           whether it shows that it is hungry without food
     * @param morningHandled     whether the morning plan was made now (mark it for today)
     * @param eveningMealHandled whether the evening meal was handled now (mark it for today)
     * @param afterWork          whether work is over for today (jobs show idle, except one being delivered)
     */
    public record Decision(Intent intent, @Nullable MealPurpose purpose, boolean complain, boolean morningHandled,
            boolean eveningMealHandled, boolean afterWork) {
    }

    /**
     * Settles the body before anything else.
     *
     * @param snapshot the situation
     * @return {@link Intent#SLEEP}, {@link Intent#WAKE}, {@link Intent#RETURN_TO_REST_PLACE}, {@link Intent#FORAGE} or
     *         {@link Intent#END_FORAGING}; empty when the settler is awake and free to decide with {@link #next}
     */
    public static Optional<Intent> wakeUp(Snapshot snapshot) {
        boolean night = isNight(snapshot);
        if (snapshot.sleeping()) {
            // Asleep from its bedtime until its waking time of the next morning.
            boolean beforeWaking = snapshot.dayTime() < snapshot.times().wake();
            return Optional.of(night || beforeWaking ? Intent.SLEEP : Intent.WAKE);
        }
        // A skipped night lands in the morning, maybe before the waking time: the settler appears at its rest place.
        if (snapshot.nightSkipped() && !night && !snapshot.homeless() && !snapshot.foraging()) {
            return Optional.of(Intent.RETURN_TO_REST_PLACE);
        }
        if (!snapshot.foraging()) {
            return Optional.empty();
        }
        // The forager comes back at its end of work; foraging of an earlier day is over as well.
        boolean over = snapshot.dayTime() >= snapshot.times().workEnd() || snapshot.day() > snapshot.forageDay();
        return Optional.of(over ? Intent.END_FORAGING : Intent.FORAGE);
    }

    /**
     * Decides what the awake settler does next.
     *
     * @param snapshot the situation (after {@link #wakeUp} returned empty)
     * @return the decision
     */
    public static Decision next(Snapshot snapshot) {
        if (snapshot.activity() == Activity.EATING || snapshot.activity() == Activity.FETCH_FOOD
                || (snapshot.activity() == Activity.JOB && snapshot.jobStepHolds().getAsBoolean())) {
            // A meal in progress is finished first (also after 20:00); a job step may hold the decision.
            return day(Intent.CONTINUE, null, false, false);
        }
        if (snapshot.homeless()) {
            boolean hungry = snapshot.food() < SettlerFood.HUNGRY;
            return day(hungry && snapshot.pocketMeal() ? Intent.EAT_FROM_POCKETS : Intent.IDLE, null, hungry && !snapshot.pocketMeal(), false);
        }
        if (snapshot.dayTime() >= snapshot.times().workEnd()) {
            return afterWork(snapshot);
        }
        boolean morning = !snapshot.morningHandled();
        if (morning) {
            MorningPlan plan = morningPlan(snapshot.food(), snapshot.provisions(), snapshot.provisionsWanted(),
                    snapshot.foodPoint().getAsBoolean());
            if (plan == MorningPlan.FORAGE) {
                return day(Intent.START_FORAGING, null, false, true);
            }
            if (plan == MorningPlan.FETCH_PROVISIONS) {
                return day(Intent.FETCH_FOOD, MealPurpose.MORNING, false, true);
            }
        }
        if (snapshot.food() < SettlerFood.HUNGRY) {
            if (snapshot.pocketMeal()) {
                return day(Intent.EAT_FROM_POCKETS, null, false, morning);
            }
            if (snapshot.foodPoint().getAsBoolean()) {
                return day(Intent.FETCH_FOOD, MealPurpose.HUNGRY, false, morning);
            }
            // Starving: no work until there is food again; forages the next day.
            return day(snapshot.food() <= 0 ? Intent.IDLE : Intent.WORK, null, true, morning);
        }
        return day(Intent.WORK, null, false, morning);
    }

    /** After work: deliver what is left, eat, then rest near the rest place and sleep from its bedtime. */
    private static Decision afterWork(Snapshot snapshot) {
        if (snapshot.deliveryPending()) {
            return new Decision(Intent.DELIVER, null, false, false, false, true);
        }
        boolean night = isNight(snapshot);
        boolean meal = !night && !snapshot.eveningMealEaten();
        if (meal && snapshot.food() < SettlerFood.MAX) {
            if (snapshot.foodPoint().getAsBoolean()) {
                return new Decision(Intent.FETCH_FOOD, MealPurpose.EVENING, false, false, true, true);
            }
            // Provisions are only eaten in the evening if no food point has food.
            if (snapshot.pocketMeal()) {
                return new Decision(Intent.EAT_UNTIL_FULL, null, false, false, true, true);
            }
        }
        boolean complain = snapshot.food() < SettlerFood.HUNGRY && snapshot.provisions() == 0 && !snapshot.foodPoint().getAsBoolean();
        return new Decision(night ? Intent.GO_TO_BED : Intent.REST, null, complain, false, meal, true);
    }

    /**
     * Whether the settler eats at the food point it walked to (until full), or only takes provisions: the morning trip for
     * provisions is not a meal unless the settler is hungry.
     *
     * @param purpose why the settler came to the food point (null: unknown, it eats)
     * @param food    the food level
     * @return whether it eats there
     */
    public static boolean eatsAtFoodPoint(@Nullable MealPurpose purpose, int food) {
        return purpose != MealPurpose.MORNING || food < SettlerFood.HUNGRY;
    }

    /**
     * @return the food points a forager brings back ({@link Intent#END_FORAGING})
     */
    static int foragingFood() {
        return CivModConfig.FORAGING_FOOD.get();
    }

    /** From the settler's bedtime until the day clock wraps at 06:00; the morning before waking is checked apart. */
    private static boolean isNight(Snapshot snapshot) {
        return snapshot.dayTime() >= snapshot.times().bed();
    }

    private static Decision day(Intent intent, @Nullable MealPurpose purpose, boolean complain, boolean morningHandled) {
        return new Decision(intent, purpose, complain, morningHandled, false, false);
    }

    /** What a settler does at the start of a workday regarding food. */
    public enum MorningPlan {
        /** Nothing to do, go to work. */
        WORK,
        /** Fetch provisions from a food point before work. */
        FETCH_PROVISIONS,
        /** Hungry and no food anywhere: forage all day. */
        FORAGE
    }

    /**
     * Morning decision (vault {@code Systems/Needs.md}).
     *
     * @param level      the food level
     * @param provisions nutrition carried in the pockets
     * @param wanted     nutrition the settler wants to carry
     * @param foodPoint  whether a food point has food
     * @return the plan
     */
    public static MorningPlan morningPlan(int level, int provisions, int wanted, boolean foodPoint) {
        if (level < SettlerFood.HUNGRY && provisions == 0 && !foodPoint) {
            return MorningPlan.FORAGE;
        }
        return provisions < wanted && foodPoint ? MorningPlan.FETCH_PROVISIONS : MorningPlan.WORK;
    }
}
