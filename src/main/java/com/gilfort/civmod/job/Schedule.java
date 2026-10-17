package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.npc.SettlerDay;
import com.gilfort.civmod.settlement.DayClock;

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
    private static final long WAKE_WINDOW_START = 0L;
    /**
     * Start of the early waking window (05:30, day time -500): an early riser (the Clerk, its stall opens first) is up
     * before 06:00, before every other settler wakes.
     */
    private static final long EARLY_WAKE_WINDOW_START = WAKE_WINDOW_START - WINDOW_TICKS;

    /**
     * How long a hungry settler without food waits at a Clerk's queue before it goes foraging (2 hours): food may still
     * arrive (deliveries, a Craftsperson, the player).
     */
    public static final long FOOD_WAIT_TICKS = 2000L;
    /** {@link Snapshot#waitStart} when the settler has not started waiting for food today. */
    public static final long NOT_WAITING = Long.MIN_VALUE;

    private static final long SALT_WAKE = 1L;
    private static final long SALT_WORK_END = 2L;
    private static final long SALT_BED = 3L;

    private Schedule() {
    }

    /**
     * One settler's points of the daily schedule on one day, each at its daily offset inside its Schedule window.
     *
     * @param wake    the day time the settler wakes and starts work (06:00–06:30; an early riser 05:30–06:00, a
     *                negative day time: its day begins before the clock's day does, see {@link #moment})
     * @param workEnd the day time its work ends (17:30–18:00; the Settler Shell may replace it, e.g. for a Clerk)
     * @param bed     the day time it goes to bed (20:00–20:30)
     */
    public record DayTimes(long wake, long workEnd, long bed) {

        /**
         * @param end the day time work ends instead
         * @return these times with another end of work
         */
        public DayTimes withWorkEnd(long end) {
            return new DayTimes(wake, end, bed);
        }
    }

    /**
     * The settler's points of the daily schedule on a day: deterministic from the settler and the day, never saved, the
     * same in live play and catch-up.
     *
     * @param settler the settler
     * @param day     the day
     * @return the times
     */
    public static DayTimes dayTimes(UUID settler, long day) {
        return dayTimes(settler, day, false);
    }

    /**
     * {@link #dayTimes(UUID, long)}, for an early riser with its waking time in the early waking window (05:30–06:00)
     * instead. The Settler Shell decides who rises early (the Clerk).
     *
     * @param settler    the settler
     * @param day        the day
     * @param earlyRiser whether the settler wakes in the early waking window
     * @return the times
     */
    public static DayTimes dayTimes(UUID settler, long day, boolean earlyRiser) {
        long wake = (earlyRiser ? EARLY_WAKE_WINDOW_START : WAKE_WINDOW_START) + offset(settler, day, SALT_WAKE);
        long workEnd = SettlerWork.WORK_END + offset(settler, day, SALT_WORK_END);
        long bed = SettlerWork.SLEEP_TIME + offset(settler, day, SALT_BED);
        return new DayTimes(wake, workEnd, bed);
    }

    /**
     * A settler's day and day time.
     *
     * @param day     the day
     * @param dayTime the day time (0 = 06:00; negative before 06:00 for an early riser that is already up)
     */
    public record Moment(long day, long dayTime) {
    }

    /**
     * The settler's day and day time at a clock value. The clock's day begins at 06:00; an early riser's day begins at
     * its waking time before that, so from then on the clock counts as the next day with a negative day time.
     *
     * @param clock   the clock value
     * @param nextDay the settler's times on the day after the clock's day
     * @return the moment
     */
    public static Moment moment(long clock, DayTimes nextDay) {
        long day = DayClock.day(clock);
        long dayTime = DayClock.dayTime(clock);
        long early = dayTime - DayClock.DAY_TICKS;
        return nextDay.wake() < 0L && early >= nextDay.wake() ? new Moment(day + 1, early) : new Moment(day, dayTime);
    }

    private static long offset(UUID settler, long day, long salt) {
        return SettlerDay.pick(settler, day, salt, (int) WINDOW_TICKS);
    }

    /** What a settler is doing right now. {@code JOB} is a step of the active job's category loop. */
    public enum Activity { IDLE, JOB, DELIVER, GO_TO_BED, SLEEP, FETCH_FOOD, EATING, FORAGE, WAIT_FOR_FOOD }

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
        /** Stand still (homeless, starving, or awake before the waking time). */
        IDLE,
        /** Eat one item from the pockets on the spot. */
        EAT_FROM_POCKETS,
        /** Eat from the pockets on the spot until full (evening meal without food at a food point). */
        EAT_UNTIL_FULL,
        /** Walk to the nearest food point (Clerk JobBlock) with food, for a {@link MealPurpose}. */
        FETCH_FOOD,
        /**
         * Wait at the queue of the nearest Clerk JobBlock: hungry with nothing to eat, for up to {@link #FOOD_WAIT_TICKS}
         * before foraging (a Clerk waits at its own counter, at work).
         */
        WAIT_FOR_FOOD,
        /** Start foraging for the rest of the day. */
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
     * @param waitPoint        whether the settlement has a Clerk JobBlock to wait at for food (with food or not); asked
     *                         only when the settler would go foraging
     * @param homeless        whether the settler has no settlement
     * @param sleeping         whether the settler is asleep
     * @param forageDay        the day the settler forages on, {@link SettlerFood#NO_DAY} if it is not foraging
     * @param nightSkipped     whether the night was skipped since the last decision
     * @param activity         the current activity
     * @param jobStepHolds     whether the current job step holds the decision (fatigue break, walking out for a
     *                         mission); asked only during a job step, it may act (the category loop's own rules)
     * @param morningHandled   whether the morning plan was made today
     * @param eveningMealEaten whether the evening meal was handled today
     * @param waitStart        the day time the settler began waiting for food today, {@link #NOT_WAITING} if it did not
     * @param deliveryPending  whether a job has items to deliver to a drop-off that is not full
     * @param times            the settler's waking, end of work and bed times today ({@link #dayTimes})
     */
    public record Snapshot(long day, long dayTime, int food, int provisions, int provisionsWanted, boolean pocketMeal,
            BooleanSupplier foodPoint, BooleanSupplier waitPoint, boolean homeless, boolean sleeping, long forageDay,
            boolean nightSkipped, Activity activity, BooleanSupplier jobStepHolds, boolean morningHandled,
            boolean eveningMealEaten, long waitStart, boolean deliveryPending, DayTimes times) {

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
     * @param waitStarted        whether waiting for food began now (mark the day time for today)
     */
    public record Decision(Intent intent, @Nullable MealPurpose purpose, boolean complain, boolean morningHandled,
            boolean eveningMealHandled, boolean afterWork, boolean waitStarted) {
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
        if (snapshot.dayTime() < snapshot.times().wake()) {
            // Awake before its waking time (skipped night, woken by vanilla): the day has not started yet.
            return day(Intent.IDLE, null, false, false);
        }
        boolean morning = !snapshot.morningHandled();
        if (morning) {
            MorningPlan plan = morningPlan(snapshot.food(), snapshot.provisions(), snapshot.provisionsWanted(),
                    snapshot.foodPoint().getAsBoolean());
            if (plan == MorningPlan.FORAGE) {
                return forageOrWait(snapshot);
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

    /**
     * Hungry with no food anywhere in the morning: with a Clerk JobBlock in the settlement the settler first waits at its
     * queue for {@link #FOOD_WAIT_TICKS} (the morning stays open, so food that arrives is fetched as usual), then it
     * forages; without one it forages right away.
     */
    private static Decision forageOrWait(Snapshot snapshot) {
        if (!snapshot.waitPoint().getAsBoolean()) {
            return day(Intent.START_FORAGING, null, false, true);
        }
        if (snapshot.waitStart() == NOT_WAITING) {
            return new Decision(Intent.WAIT_FOR_FOOD, null, false, false, false, false, true);
        }
        if (snapshot.dayTime() < snapshot.waitStart() + FOOD_WAIT_TICKS) {
            return day(Intent.WAIT_FOR_FOOD, null, false, false);
        }
        return day(Intent.START_FORAGING, null, false, true);
    }

    /** After work: deliver what is left, eat, then rest near the rest place and sleep from its bedtime. */
    private static Decision afterWork(Snapshot snapshot) {
        if (snapshot.deliveryPending()) {
            return new Decision(Intent.DELIVER, null, false, false, false, true, false);
        }
        boolean night = isNight(snapshot);
        boolean meal = !night && !snapshot.eveningMealEaten();
        if (meal && snapshot.food() < SettlerFood.MAX) {
            if (snapshot.foodPoint().getAsBoolean()) {
                return new Decision(Intent.FETCH_FOOD, MealPurpose.EVENING, false, false, true, true, false);
            }
            // Provisions are only eaten in the evening if no food point has food.
            if (snapshot.pocketMeal()) {
                return new Decision(Intent.EAT_UNTIL_FULL, null, false, false, true, true, false);
            }
        }
        boolean complain = snapshot.food() < SettlerFood.HUNGRY && snapshot.provisions() == 0 && !snapshot.foodPoint().getAsBoolean();
        return new Decision(night ? Intent.GO_TO_BED : Intent.REST, null, complain, false, meal, true, false);
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
        return new Decision(intent, purpose, complain, morningHandled, false, false, false);
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
