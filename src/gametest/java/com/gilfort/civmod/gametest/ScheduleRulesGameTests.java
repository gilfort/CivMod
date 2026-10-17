package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.job.Schedule.Decision;
import com.gilfort.civmod.job.Schedule.Intent;
import com.gilfort.civmod.job.Schedule.MealPurpose;
import com.gilfort.civmod.job.SettlerWork;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Table tests of the {@link Schedule} (ticket #51): snapshot in, intent out, no settler and no clock.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ScheduleRulesGameTests {

    private static final long MORNING = 1000L;
    private static final long EVENING = 12000L;
    private static final long NIGHT = 14500L;

    private ScheduleRulesGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "schedule_rules_morning", ScheduleRulesGameTests::morning);
            register(helper, "schedule_rules_hunger", ScheduleRulesGameTests::hunger);
            register(helper, "schedule_rules_evening", ScheduleRulesGameTests::evening);
            register(helper, "schedule_rules_homeless", ScheduleRulesGameTests::homeless);
            register(helper, "schedule_rules_sleeping", ScheduleRulesGameTests::sleeping);
            register(helper, "schedule_rules_activity_in_progress", ScheduleRulesGameTests::activityInProgress);
            register(helper, "schedule_rules_foraging", ScheduleRulesGameTests::foraging);
            register(helper, "schedule_rules_daily_offset", ScheduleRulesGameTests::dailyOffset);
            register(helper, "schedule_rules_windows", ScheduleRulesGameTests::windows);
            register(helper, "schedule_rules_no_work_before_waking", ScheduleRulesGameTests::noWorkBeforeWaking);
            register(helper, "schedule_rules_early_waking", ScheduleRulesGameTests::earlyWaking);
            register(helper, "schedule_rules_wait_for_food", ScheduleRulesGameTests::waitForFood);
        });
    }

    /**
     * Hungry settlers wait at the Clerk's queue for 2 hours before they go foraging: food may still arrive. Without a
     * Clerk JobBlock in the settlement they forage right away, as before.
     */
    private static void waitForFood(GameTestHelper helper) {
        Situation start = hungryWithoutFood();
        start.waitPoint = true;
        Decision decision = start.next();
        expect(helper, decision, Intent.WAIT_FOR_FOOD, "Hungry, a Clerk without food: waits at its queue");
        helper.assertTrue(decision.waitStarted() && !decision.morningHandled(), "The wait starts, the morning stays open: " + decision);

        Situation waiting = hungryWithoutFood();
        waiting.waitPoint = true;
        waiting.waitStart = 1000L;
        waiting.activity = Activity.WAIT_FOR_FOOD;
        waiting.dayTime = 2999L;
        decision = waiting.next();
        expect(helper, decision, Intent.WAIT_FOR_FOOD, "Still waiting 1 h 59 min after it began");
        helper.assertTrue(!decision.waitStarted() && !decision.morningHandled(), "The wait goes on: " + decision);

        Situation forage = hungryWithoutFood();
        forage.waitPoint = true;
        forage.waitStart = 1000L;
        forage.activity = Activity.WAIT_FOR_FOOD;
        forage.dayTime = 3000L;
        decision = forage.next();
        expect(helper, decision, Intent.START_FORAGING, "After 2 hours without food: foraging");
        helper.assertTrue(decision.morningHandled(), "Foraging marks the morning: " + decision);

        Situation arrived = hungryWithoutFood();
        arrived.waitPoint = true;
        arrived.waitStart = 1000L;
        arrived.activity = Activity.WAIT_FOR_FOOD;
        arrived.dayTime = 1500L;
        arrived.foodPoint = true;
        decision = arrived.next();
        expect(helper, decision, Intent.FETCH_FOOD, "Food arrived while waiting: fetches it");
        helper.assertTrue(decision.purpose() == MealPurpose.MORNING && Schedule.eatsAtFoodPoint(decision.purpose(), arrived.food),
                "Eats there as usual: " + decision);

        Situation noClerk = hungryWithoutFood();
        expect(helper, noClerk.next(), Intent.START_FORAGING, "No Clerk JobBlock: forages right away");

        Situation evening = hungryWithoutFood();
        evening.waitPoint = true;
        evening.waitStart = 11000L;
        evening.activity = Activity.WAIT_FOR_FOOD;
        evening.dayTime = EVENING;
        helper.assertTrue(evening.next().afterWork(), "The wait ends with the work day");

        Situation fed = new Situation();
        fed.morningHandled = false;
        fed.waitPoint = true;
        expect(helper, fed.next(), Intent.WORK, "Not hungry: no waiting");
        helper.assertTrue(fed.waitPointAsked == 0, "Only a settler that would forage looks for a Clerk to wait at");
        helper.succeed();
    }

    /** Morning not handled, hungry, empty pockets, no food point food. */
    private static Situation hungryWithoutFood() {
        Situation situation = new Situation();
        situation.morningHandled = false;
        situation.food = 5;
        situation.provisions = 0;
        situation.pocketMeal = false;
        situation.foodPoint = false;
        return situation;
    }

    /**
     * The early waking window (05:30–06:00, for the Clerk): the waking time lies before 06:00, so it falls on the clock's
     * previous day. From its waking time on, the settler's day has begun: the clock maps to the new day with a negative
     * day time, and it wakes and works as usual; every other time stays as it is.
     */
    private static void earlyWaking(GameTestHelper helper) {
        Set<Long> wakes = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            UUID settler = new UUID(0xEA21L * i, 0xBEEFL + i);
            for (long day = 0; day < 10; day++) {
                Schedule.DayTimes early = Schedule.dayTimes(settler, day, true);
                Schedule.DayTimes usual = Schedule.dayTimes(settler, day);
                helper.assertTrue(early.wake() >= -500L && early.wake() < 0L, "Early waking 05:30–06:00: " + early);
                helper.assertTrue(early.workEnd() == usual.workEnd() && early.bed() == usual.bed(), "Only the waking time moves: " + early);
                helper.assertTrue(early.equals(Schedule.dayTimes(settler, day, true)), "Deterministic: " + early);
                wakes.add(early.wake());
            }
        }
        helper.assertTrue(wakes.size() > 100, "Early offsets differ between settlers and days: " + wakes.size());

        // Day 4, waking at 05:40 (-333); the clock of day 3 at 05:40 is 3 * 24000 + 23667.
        Schedule.DayTimes dayFour = new Schedule.DayTimes(-333L, 12500L, 14200L);
        long day3 = 3L * 24000L;
        helper.assertTrue(Schedule.moment(day3 + 23666L, dayFour).equals(new Schedule.Moment(3L, 23666L)),
                "05:39: still the night of day 3");
        helper.assertTrue(Schedule.moment(day3 + 23667L, dayFour).equals(new Schedule.Moment(4L, -333L)),
                "05:40: day 4 has begun: " + Schedule.moment(day3 + 23667L, dayFour));
        helper.assertTrue(Schedule.moment(day3 + 23999L, dayFour).equals(new Schedule.Moment(4L, -1L)), "05:59: day 4");
        helper.assertTrue(Schedule.moment(day3 + 1000L, dayFour).equals(new Schedule.Moment(3L, 1000L)), "07:00 of day 3 stays");
        Schedule.DayTimes usual = new Schedule.DayTimes(300L, 11800L, 14300L);
        helper.assertTrue(Schedule.moment(day3 + 23999L, usual).equals(new Schedule.Moment(3L, 23999L)),
                "A usual waking time never moves the day");

        Situation asleep = new Situation();
        asleep.times = dayFour;
        asleep.sleeping = true;
        asleep.dayTime = -334L;
        helper.assertTrue(asleep.wakeUp().equals(Optional.of(Intent.SLEEP)), "Asleep before 05:40");
        asleep.dayTime = -333L;
        helper.assertTrue(asleep.wakeUp().equals(Optional.of(Intent.WAKE)), "Wakes at 05:40");

        Situation awake = new Situation();
        awake.times = dayFour;
        awake.morningHandled = false;
        awake.dayTime = -300L;
        Decision decision = awake.next();
        expect(helper, decision, Intent.WORK, "Works from 05:40, before 06:00");
        helper.assertTrue(decision.morningHandled() && !decision.afterWork(), "Its morning plan is made then: " + decision);
        helper.succeed();
    }

    /**
     * A settler already awake at 06:00 (after a skipped night, woken by vanilla) waits for its own waking time: no work,
     * no morning plan before it; from its waking time on it works as usual.
     */
    private static void noWorkBeforeWaking(GameTestHelper helper) {
        Schedule.DayTimes times = new Schedule.DayTimes(300L, 11800L, 14300L);

        Situation early = new Situation();
        early.times = times;
        early.morningHandled = false;
        early.dayTime = 0L;
        Decision decision = early.next();
        expect(helper, decision, Intent.IDLE, "Awake at 06:00, waking time 06:18");
        helper.assertTrue(!decision.morningHandled() && !decision.afterWork(), "No morning plan before waking: " + decision);
        early.dayTime = 299L;
        expect(helper, early.next(), Intent.IDLE, "Awake just before its waking time");
        early.dayTime = 300L;
        decision = early.next();
        expect(helper, decision, Intent.WORK, "Works from its waking time");
        helper.assertTrue(decision.morningHandled(), "The morning plan is made at its waking time: " + decision);

        Situation hungry = new Situation();
        hungry.times = times;
        hungry.food = 5;
        hungry.dayTime = 100L;
        expect(helper, hungry.next(), Intent.IDLE, "Hungry before its waking time: the day has not started");

        Situation meal = new Situation();
        meal.times = times;
        meal.activity = Activity.EATING;
        meal.dayTime = 100L;
        expect(helper, meal.next(), Intent.CONTINUE, "A meal in progress is finished");
        helper.succeed();
    }

    /** The settler's own times decide: it wakes, ends work, stops foraging and goes to bed at its daily offset. */
    private static void windows(GameTestHelper helper) {
        Schedule.DayTimes times = new Schedule.DayTimes(300L, 11800L, 14300L);

        Situation asleep = new Situation();
        asleep.times = times;
        asleep.sleeping = true;
        asleep.dayTime = 299L;
        helper.assertTrue(asleep.wakeUp().equals(Optional.of(Intent.SLEEP)), "Still asleep before its waking time");
        asleep.dayTime = 300L;
        helper.assertTrue(asleep.wakeUp().equals(Optional.of(Intent.WAKE)), "Wakes at its waking time");

        Situation skipped = new Situation();
        skipped.times = times;
        skipped.nightSkipped = true;
        skipped.dayTime = 100L;
        helper.assertTrue(skipped.wakeUp().equals(Optional.of(Intent.RETURN_TO_REST_PLACE)),
                "Night skipped to 06:05 while not in bed: back at the rest place, even before its waking time");

        Situation working = new Situation();
        working.times = times;
        working.dayTime = 11799L;
        expect(helper, working.next(), Intent.WORK, "Works until its end of work");
        working.dayTime = 11800L;
        helper.assertTrue(working.next().afterWork(), "Off at its end of work");

        Situation forager = new Situation();
        forager.times = times;
        forager.forageDay = 3L;
        forager.dayTime = 11799L;
        helper.assertTrue(forager.wakeUp().equals(Optional.of(Intent.FORAGE)), "Forages until its end of work");
        forager.dayTime = 11800L;
        helper.assertTrue(forager.wakeUp().equals(Optional.of(Intent.END_FORAGING)), "Back at its end of work");

        Situation evening = new Situation();
        evening.times = times;
        evening.eveningMealEaten = true;
        evening.dayTime = 14299L;
        expect(helper, evening.next(), Intent.REST, "Rests until its bedtime");
        evening.dayTime = 14300L;
        expect(helper, evening.next(), Intent.GO_TO_BED, "Goes to bed at its bedtime");

        Situation sleeper = new Situation();
        sleeper.times = times;
        sleeper.sleeping = true;
        sleeper.dayTime = 14300L;
        helper.assertTrue(sleeper.wakeUp().equals(Optional.of(Intent.SLEEP)), "Asleep after its bedtime");
        helper.succeed();
    }

    /**
     * Each settler gets a daily offset inside each Schedule window (waking 06:00–06:30, end of work 17:30–18:00, going to
     * bed 20:00–20:30): stable for the same settler and day, different between settlers and days.
     */
    private static void dailyOffset(GameTestHelper helper) {
        Set<Long> wakes = new HashSet<>();
        Set<Long> workEnds = new HashSet<>();
        Set<Long> beds = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            UUID settler = new UUID(0x5EEDL * i, 0xC0FFEEL + i);
            for (long day = 0; day < 10; day++) {
                Schedule.DayTimes times = Schedule.dayTimes(settler, day);
                helper.assertTrue(times.wake() >= 0L && times.wake() < 500L, "Waking 06:00–06:30: " + times);
                helper.assertTrue(times.workEnd() >= 11500L && times.workEnd() < 12000L, "End of work 17:30–18:00: " + times);
                helper.assertTrue(times.bed() >= 14000L && times.bed() < 14500L, "Going to bed 20:00–20:30: " + times);
                helper.assertTrue(times.equals(Schedule.dayTimes(settler, day)), "Same settler and day, same times: " + times);
                wakes.add(times.wake());
                workEnds.add(times.workEnd());
                beds.add(times.bed());
            }
        }
        // 500 settler days over 500 possible ticks: a working spread hits far more than a few distinct values.
        helper.assertTrue(wakes.size() > 100 && workEnds.size() > 100 && beds.size() > 100,
                "Offsets differ between settlers and days: " + wakes.size() + " / " + workEnds.size() + " / " + beds.size());
        UUID alice = new UUID(1L, 2L);
        UUID bob = new UUID(3L, 4L);
        helper.assertFalse(Schedule.dayTimes(alice, 5L).equals(Schedule.dayTimes(bob, 5L)), "Alice and Bob differ on day 5");
        helper.assertFalse(Schedule.dayTimes(alice, 5L).equals(Schedule.dayTimes(alice, 6L)), "Alice differs on day 5 and 6");
        helper.succeed();
    }

    /** Foraging lasts until the end of work (17:30); foraging of an earlier day is over as well. */
    private static void foraging(GameTestHelper helper) {
        Situation away = new Situation();
        away.forageDay = 3L;
        helper.assertTrue(away.wakeUp().equals(Optional.of(Intent.FORAGE)), "Foraging during the workday");

        Situation beforeEnd = new Situation();
        beforeEnd.forageDay = 3L;
        beforeEnd.dayTime = 11499L;
        helper.assertTrue(beforeEnd.wakeUp().equals(Optional.of(Intent.FORAGE)), "Still foraging just before 17:30");

        Situation back = new Situation();
        back.forageDay = 3L;
        back.dayTime = 11500L;
        helper.assertTrue(back.wakeUp().equals(Optional.of(Intent.END_FORAGING)), "Back at 17:30");

        Situation yesterday = new Situation();
        yesterday.forageDay = 2L;
        helper.assertTrue(yesterday.wakeUp().equals(Optional.of(Intent.END_FORAGING)), "Foraging of an earlier day is over");

        Situation skipped = new Situation();
        skipped.forageDay = 2L;
        skipped.nightSkipped = true;
        helper.assertTrue(skipped.wakeUp().equals(Optional.of(Intent.END_FORAGING)), "After a skipped night the forager comes back first");
        helper.succeed();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A snapshot under construction; defaults: day 3, 07:00, fed, enough provisions, food point food, awake, idle. */
    private static final class Situation {
        long dayTime = MORNING;
        int food = 20;
        int provisions = 10;
        boolean pocketMeal = true;
        boolean foodPoint = true;
        /** Whether the settlement has a Clerk JobBlock to wait at; off by default (no waiting, as before). */
        boolean waitPoint;
        int waitPointAsked;
        long waitStart = Schedule.NOT_WAITING;
        boolean homeless;
        boolean sleeping;
        long forageDay = SettlerFood.NO_DAY;
        boolean nightSkipped;
        Activity activity = Activity.IDLE;
        boolean jobStepHolds;
        boolean morningHandled = true;
        boolean eveningMealEaten;
        boolean deliveryPending;
        int foodPointAsked;
        /** The window starts (06:00, 17:30, 20:00) unless a test moves them. */
        Schedule.DayTimes times = new Schedule.DayTimes(0L, SettlerWork.WORK_END, SettlerWork.SLEEP_TIME);

        Schedule.Snapshot snapshot() {
            return new Schedule.Snapshot(3L, dayTime, food, provisions, 6, pocketMeal, () -> {
                foodPointAsked++;
                return foodPoint;
            }, () -> {
                waitPointAsked++;
                return waitPoint;
            }, homeless, sleeping, forageDay, nightSkipped, activity, () -> jobStepHolds, morningHandled, eveningMealEaten,
                    waitStart, deliveryPending, times);
        }

        Optional<Intent> wakeUp() {
            return Schedule.wakeUp(snapshot());
        }

        Decision next() {
            return Schedule.next(snapshot());
        }
    }

    private static void expect(GameTestHelper helper, Decision decision, Intent intent, String what) {
        helper.assertTrue(decision.intent() == intent, what + ": expected " + intent + ", got " + decision);
    }

    /** Morning: work, fetch provisions when short and a food point has food, forage when hungry with no food anywhere. */
    private static void morning(GameTestHelper helper) {
        Situation work = new Situation();
        work.morningHandled = false;
        Decision decision = work.next();
        expect(helper, decision, Intent.WORK, "Enough provisions");
        helper.assertTrue(decision.morningHandled() && !decision.afterWork(), "The morning plan is marked: " + decision);

        Situation fetch = new Situation();
        fetch.morningHandled = false;
        fetch.provisions = 2;
        decision = fetch.next();
        expect(helper, decision, Intent.FETCH_FOOD, "Short of provisions");
        helper.assertTrue(decision.purpose() == MealPurpose.MORNING && decision.morningHandled(), "Fetches for the morning: " + decision);

        Situation noFoodPoint = new Situation();
        noFoodPoint.morningHandled = false;
        noFoodPoint.provisions = 2;
        noFoodPoint.foodPoint = false;
        expect(helper, noFoodPoint.next(), Intent.WORK, "Short of provisions, no food point food");

        Situation forage = new Situation();
        forage.morningHandled = false;
        forage.food = 5;
        forage.provisions = 0;
        forage.pocketMeal = false;
        forage.foodPoint = false;
        decision = forage.next();
        expect(helper, decision, Intent.START_FORAGING, "Hungry, no food anywhere");
        helper.assertTrue(decision.morningHandled(), "Foraging marks the morning");

        Situation handled = new Situation();
        handled.provisions = 2;
        expect(helper, handled.next(), Intent.WORK, "Morning already handled today");
        helper.assertTrue(handled.foodPointAsked == 0, "A working settler does not look into food points");

        helper.assertTrue(Schedule.morningPlan(5, 0, 6, false) == Schedule.MorningPlan.FORAGE, "Plan: hungry, no food anywhere");
        helper.assertTrue(Schedule.morningPlan(5, 3, 6, false) == Schedule.MorningPlan.WORK, "Plan: hungry but has provisions");
        helper.assertTrue(Schedule.morningPlan(20, 0, 6, true) == Schedule.MorningPlan.FETCH_PROVISIONS, "Plan: short of provisions");
        helper.assertFalse(Schedule.eatsAtFoodPoint(MealPurpose.MORNING, 15), "The morning provisions trip is no meal");
        helper.assertTrue(Schedule.eatsAtFoodPoint(MealPurpose.MORNING, 9), "Hungry in the morning: eats at the food point");
        helper.assertTrue(Schedule.eatsAtFoodPoint(MealPurpose.HUNGRY, 9) && Schedule.eatsAtFoodPoint(MealPurpose.EVENING, 15),
                "Hungry and evening trips are meals");
        helper.succeed();
    }

    /** Hunger during the day: pockets first, then the food point, else complain; starving stops work. */
    private static void hunger(GameTestHelper helper) {
        Situation pockets = new Situation();
        pockets.food = 5;
        expect(helper, pockets.next(), Intent.EAT_FROM_POCKETS, "Hungry with food in the pockets");
        helper.assertTrue(pockets.foodPointAsked == 0, "Pockets first: no food point lookup");

        Situation atFoodPoint = new Situation();
        atFoodPoint.food = 5;
        atFoodPoint.provisions = 0;
        atFoodPoint.pocketMeal = false;
        Decision decision = atFoodPoint.next();
        expect(helper, decision, Intent.FETCH_FOOD, "Hungry, empty pockets, food point food");
        helper.assertTrue(decision.purpose() == MealPurpose.HUNGRY && !decision.complain(), "Fetches because hungry: " + decision);

        Situation complain = new Situation();
        complain.food = 5;
        complain.provisions = 0;
        complain.pocketMeal = false;
        complain.foodPoint = false;
        decision = complain.next();
        expect(helper, decision, Intent.WORK, "Hungry without food still works");
        helper.assertTrue(decision.complain(), "Hungry without food complains");

        Situation starving = new Situation();
        starving.food = 0;
        starving.provisions = 0;
        starving.pocketMeal = false;
        starving.foodPoint = false;
        decision = starving.next();
        expect(helper, decision, Intent.IDLE, "Starving stops work");
        helper.assertTrue(decision.complain(), "Starving complains");

        Situation morning = new Situation();
        morning.morningHandled = false;
        morning.food = 5;
        decision = morning.next();
        expect(helper, decision, Intent.EAT_FROM_POCKETS, "Hungry morning with provisions eats them");
        helper.assertTrue(decision.morningHandled(), "The morning is still marked");
        helper.succeed();
    }

    /** Evening: deliver first, then the meal (food point before pockets), then rest near the rest place, bed from 20:00. */
    private static void evening(GameTestHelper helper) {
        Situation deliver = new Situation();
        deliver.dayTime = EVENING;
        deliver.food = 15;
        deliver.deliveryPending = true;
        Decision decision = deliver.next();
        expect(helper, decision, Intent.DELIVER, "Items left in a buffer");
        helper.assertTrue(decision.afterWork() && !decision.eveningMealHandled(), "Delivery comes before the meal: " + decision);

        Situation atFoodPoint = new Situation();
        atFoodPoint.dayTime = EVENING;
        atFoodPoint.food = 15;
        decision = atFoodPoint.next();
        expect(helper, decision, Intent.FETCH_FOOD, "Evening meal at the food point although the pockets have food");
        helper.assertTrue(decision.purpose() == MealPurpose.EVENING && decision.eveningMealHandled() && decision.afterWork(),
                "Evening meal: " + decision);

        Situation pockets = new Situation();
        pockets.dayTime = EVENING;
        pockets.food = 15;
        pockets.foodPoint = false;
        decision = pockets.next();
        expect(helper, decision, Intent.EAT_UNTIL_FULL, "No food point food: eat the provisions until full");
        helper.assertTrue(decision.eveningMealHandled(), "Pocket meal marks the evening meal");

        Situation full = new Situation();
        full.dayTime = EVENING;
        decision = full.next();
        expect(helper, decision, Intent.REST, "Full: rest near the rest place");
        helper.assertTrue(decision.eveningMealHandled() && decision.afterWork() && !decision.complain(), "Meal marked when full: " + decision);

        Situation eaten = new Situation();
        eaten.dayTime = EVENING;
        eaten.food = 15;
        eaten.eveningMealEaten = true;
        decision = eaten.next();
        expect(helper, decision, Intent.REST, "One evening meal per day");
        helper.assertTrue(!decision.eveningMealHandled() && eaten.foodPointAsked == 0, "No second meal, no food point lookup: " + decision);

        Situation hungry = new Situation();
        hungry.dayTime = EVENING;
        hungry.food = 5;
        hungry.provisions = 0;
        hungry.pocketMeal = false;
        hungry.foodPoint = false;
        decision = hungry.next();
        expect(helper, decision, Intent.REST, "Hungry without food rests");
        helper.assertTrue(decision.complain(), "Hungry without food complains in the evening");

        Situation beforeBed = new Situation();
        beforeBed.dayTime = 13999L;
        beforeBed.eveningMealEaten = true;
        expect(helper, beforeBed.next(), Intent.REST, "19:59 still rests");

        Situation bed = new Situation();
        bed.dayTime = NIGHT;
        bed.food = 15;
        decision = bed.next();
        expect(helper, decision, Intent.GO_TO_BED, "Bed from 20:00");
        helper.assertTrue(decision.afterWork() && !decision.eveningMealHandled(), "No evening meal at night: " + decision);

        Situation late = new Situation();
        late.dayTime = NIGHT;
        late.deliveryPending = true;
        expect(helper, late.next(), Intent.DELIVER, "Delivers before bed");
        helper.succeed();
    }

    /** Homeless: no schedule, no rest place; eats from the pockets when hungry, otherwise complains. */
    private static void homeless(GameTestHelper helper) {
        Situation evening = new Situation();
        evening.homeless = true;
        evening.dayTime = EVENING;
        evening.deliveryPending = true;
        Decision decision = evening.next();
        expect(helper, decision, Intent.IDLE, "Homeless in the evening");
        helper.assertTrue(!decision.afterWork() && !decision.complain() && evening.foodPointAsked == 0, "No evening for the homeless: " + decision);

        Situation morning = new Situation();
        morning.homeless = true;
        morning.morningHandled = false;
        morning.provisions = 0;
        expect(helper, morning.next(), Intent.IDLE, "No morning plan for the homeless");

        Situation pockets = new Situation();
        pockets.homeless = true;
        pockets.food = 5;
        expect(helper, pockets.next(), Intent.EAT_FROM_POCKETS, "Homeless and hungry eats from the pockets");

        Situation complain = new Situation();
        complain.homeless = true;
        complain.food = 5;
        complain.pocketMeal = false;
        decision = complain.next();
        expect(helper, decision, Intent.IDLE, "Homeless, hungry, empty pockets");
        helper.assertTrue(decision.complain() && complain.foodPointAsked == 0, "Complains without asking a food point: " + decision);

        Situation skipped = new Situation();
        skipped.homeless = true;
        skipped.nightSkipped = true;
        helper.assertTrue(skipped.wakeUp().isEmpty(), "A homeless settler has no rest place to return to");
        helper.succeed();
    }

    /** Sleeping, waking and a skipped night. */
    private static void sleeping(GameTestHelper helper) {
        Situation night = new Situation();
        night.sleeping = true;
        night.dayTime = NIGHT;
        helper.assertTrue(night.wakeUp().equals(Optional.of(Intent.SLEEP)), "Asleep at night stays asleep");

        Situation morning = new Situation();
        morning.sleeping = true;
        morning.nightSkipped = true;
        helper.assertTrue(morning.wakeUp().equals(Optional.of(Intent.WAKE)), "Asleep in the morning wakes up (also after a skipped night)");

        Situation skipped = new Situation();
        skipped.nightSkipped = true;
        helper.assertTrue(skipped.wakeUp().equals(Optional.of(Intent.RETURN_TO_REST_PLACE)), "Skipped night out of bed: back to the rest place");

        Situation skippedForaging = new Situation();
        skippedForaging.nightSkipped = true;
        skippedForaging.forageDay = 3L;
        helper.assertTrue(skippedForaging.wakeUp().equals(Optional.of(Intent.FORAGE)), "A foraging settler stays out");

        Situation skippedAtNight = new Situation();
        skippedAtNight.nightSkipped = true;
        skippedAtNight.dayTime = NIGHT;
        helper.assertTrue(skippedAtNight.wakeUp().isEmpty(), "No return before the morning");

        Situation awake = new Situation();
        helper.assertTrue(awake.wakeUp().isEmpty(), "Awake: decide with next");
        helper.succeed();
    }

    /** A meal in progress is finished first (also at night); a job step may hold the decision (mission departure). */
    private static void activityInProgress(GameTestHelper helper) {
        Situation eating = new Situation();
        eating.activity = Activity.EATING;
        eating.dayTime = NIGHT;
        eating.deliveryPending = true;
        expect(helper, eating.next(), Intent.CONTINUE, "Eating continues");

        Situation fetching = new Situation();
        fetching.activity = Activity.FETCH_FOOD;
        fetching.homeless = true;
        expect(helper, fetching.next(), Intent.CONTINUE, "Fetching food continues");

        Situation leaving = new Situation();
        leaving.activity = Activity.JOB;
        leaving.jobStepHolds = true;
        leaving.dayTime = EVENING;
        expect(helper, leaving.next(), Intent.CONTINUE, "A job step that holds the decision continues into the evening");

        Situation working = new Situation();
        working.activity = Activity.JOB;
        working.dayTime = EVENING;
        working.eveningMealEaten = true;
        expect(helper, working.next(), Intent.REST, "A job step that does not hold ends with the work day");

        Situation holdsOutsideJob = new Situation();
        holdsOutsideJob.activity = Activity.DELIVER;
        holdsOutsideJob.jobStepHolds = true;
        expect(helper, holdsOutsideJob.next(), Intent.WORK, "Only a job step can hold the decision");
        helper.succeed();
    }
}
