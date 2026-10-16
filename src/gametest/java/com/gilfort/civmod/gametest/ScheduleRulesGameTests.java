package com.gilfort.civmod.gametest;

import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.job.Schedule.Decision;
import com.gilfort.civmod.job.Schedule.Intent;
import com.gilfort.civmod.job.Schedule.MealPurpose;

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
        });
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

    /** A snapshot under construction; defaults: day 3, 07:00, fed, enough provisions, pantry food, awake, idle. */
    private static final class Situation {
        long dayTime = MORNING;
        int food = 20;
        int provisions = 10;
        boolean pocketMeal = true;
        boolean pantryFood = true;
        boolean homeless;
        boolean sleeping;
        long forageDay = SettlerFood.NO_DAY;
        boolean nightSkipped;
        Activity activity = Activity.IDLE;
        boolean jobStepHolds;
        boolean morningHandled = true;
        boolean eveningMealEaten;
        boolean deliveryPending;
        int pantryAsked;

        Schedule.Snapshot snapshot() {
            return new Schedule.Snapshot(3L, dayTime, food, provisions, 6, pocketMeal, () -> {
                pantryAsked++;
                return pantryFood;
            }, homeless, sleeping, forageDay, nightSkipped, activity, () -> jobStepHolds, morningHandled, eveningMealEaten,
                    deliveryPending);
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

    /** Morning: work, fetch provisions when short and a pantry has food, forage when hungry with no food anywhere. */
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

        Situation noPantry = new Situation();
        noPantry.morningHandled = false;
        noPantry.provisions = 2;
        noPantry.pantryFood = false;
        expect(helper, noPantry.next(), Intent.WORK, "Short of provisions, no pantry food");

        Situation forage = new Situation();
        forage.morningHandled = false;
        forage.food = 5;
        forage.provisions = 0;
        forage.pocketMeal = false;
        forage.pantryFood = false;
        decision = forage.next();
        expect(helper, decision, Intent.START_FORAGING, "Hungry, no food anywhere");
        helper.assertTrue(decision.morningHandled(), "Foraging marks the morning");

        Situation handled = new Situation();
        handled.provisions = 2;
        expect(helper, handled.next(), Intent.WORK, "Morning already handled today");
        helper.assertTrue(handled.pantryAsked == 0, "A working settler does not look into pantries");

        helper.assertTrue(Schedule.morningPlan(5, 0, 6, false) == Schedule.MorningPlan.FORAGE, "Plan: hungry, no food anywhere");
        helper.assertTrue(Schedule.morningPlan(5, 3, 6, false) == Schedule.MorningPlan.WORK, "Plan: hungry but has provisions");
        helper.assertTrue(Schedule.morningPlan(20, 0, 6, true) == Schedule.MorningPlan.FETCH_PROVISIONS, "Plan: short of provisions");
        helper.assertFalse(Schedule.eatsAtPantry(MealPurpose.MORNING, 15), "The morning provisions trip is no meal");
        helper.assertTrue(Schedule.eatsAtPantry(MealPurpose.MORNING, 9), "Hungry in the morning: eats at the pantry");
        helper.assertTrue(Schedule.eatsAtPantry(MealPurpose.HUNGRY, 9) && Schedule.eatsAtPantry(MealPurpose.EVENING, 15),
                "Hungry and evening trips are meals");
        helper.succeed();
    }

    /** Hunger during the day: pockets first, then the pantry, else complain; starving stops work. */
    private static void hunger(GameTestHelper helper) {
        Situation pockets = new Situation();
        pockets.food = 5;
        expect(helper, pockets.next(), Intent.EAT_FROM_POCKETS, "Hungry with food in the pockets");
        helper.assertTrue(pockets.pantryAsked == 0, "Pockets first: no pantry lookup");

        Situation pantry = new Situation();
        pantry.food = 5;
        pantry.provisions = 0;
        pantry.pocketMeal = false;
        Decision decision = pantry.next();
        expect(helper, decision, Intent.FETCH_FOOD, "Hungry, empty pockets, pantry food");
        helper.assertTrue(decision.purpose() == MealPurpose.HUNGRY && !decision.complain(), "Fetches because hungry: " + decision);

        Situation complain = new Situation();
        complain.food = 5;
        complain.provisions = 0;
        complain.pocketMeal = false;
        complain.pantryFood = false;
        decision = complain.next();
        expect(helper, decision, Intent.WORK, "Hungry without food still works");
        helper.assertTrue(decision.complain(), "Hungry without food complains");

        Situation starving = new Situation();
        starving.food = 0;
        starving.provisions = 0;
        starving.pocketMeal = false;
        starving.pantryFood = false;
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

    /** Evening: deliver first, then the meal (pantry before pockets), then rest near the rest place, bed from 20:00. */
    private static void evening(GameTestHelper helper) {
        Situation deliver = new Situation();
        deliver.dayTime = EVENING;
        deliver.food = 15;
        deliver.deliveryPending = true;
        Decision decision = deliver.next();
        expect(helper, decision, Intent.DELIVER, "Items left in a buffer");
        helper.assertTrue(decision.afterWork() && !decision.eveningMealHandled(), "Delivery comes before the meal: " + decision);

        Situation pantry = new Situation();
        pantry.dayTime = EVENING;
        pantry.food = 15;
        decision = pantry.next();
        expect(helper, decision, Intent.FETCH_FOOD, "Evening meal at the pantry although the pockets have food");
        helper.assertTrue(decision.purpose() == MealPurpose.EVENING && decision.eveningMealHandled() && decision.afterWork(),
                "Evening meal: " + decision);

        Situation pockets = new Situation();
        pockets.dayTime = EVENING;
        pockets.food = 15;
        pockets.pantryFood = false;
        decision = pockets.next();
        expect(helper, decision, Intent.EAT_UNTIL_FULL, "No pantry food: eat the provisions until full");
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
        helper.assertTrue(!decision.eveningMealHandled() && eaten.pantryAsked == 0, "No second meal, no pantry lookup: " + decision);

        Situation hungry = new Situation();
        hungry.dayTime = EVENING;
        hungry.food = 5;
        hungry.provisions = 0;
        hungry.pocketMeal = false;
        hungry.pantryFood = false;
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
        helper.assertTrue(!decision.afterWork() && !decision.complain() && evening.pantryAsked == 0, "No evening for the homeless: " + decision);

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
        helper.assertTrue(decision.complain() && complain.pantryAsked == 0, "Complains without asking a pantry: " + decision);

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
