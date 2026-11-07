package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.social.SocialRules;
import com.gilfort.civmod.social.SocialRules.Outcome;
import com.gilfort.civmod.social.SocialRules.Situation;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Table test of the rule module of the meeting check (vault {@code Systems/Entertainment.md}): the roll bands, the pair
 * cooldown, the radius, the states that cannot be interrupted and the datapack merge.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SocialRulesGameTests {

    private SocialRulesGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> register(helper, "social_rules_table", SocialRulesGameTests::rulesTable));
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static void rulesTable(GameTestHelper helper) {
        SocialRules rules = SocialRules.FALLBACK;

        // roll, expected outcome: the conversation share comes first (below 0.20), then the greeting share (below 0.50)
        Object[][] bands = {{0.0, Outcome.CONVERSATION}, {0.1, Outcome.CONVERSATION}, {0.1999, Outcome.CONVERSATION},
                {0.2, Outcome.GREETING}, {0.35, Outcome.GREETING}, {0.4999, Outcome.GREETING},
                {0.5, Outcome.NONE}, {0.75, Outcome.NONE}, {0.9999, Outcome.NONE}};
        for (Object[] row : bands) {
            Outcome actual = rules.outcome((double) row[0]);
            helper.assertTrue(actual == row[1], "Roll " + row[0] + " is " + row[1] + ": " + actual);
        }

        // ticks since the pair last greeted, expected ready: 60 seconds at the earliest
        long[][] cooldown = {{0, 0}, {1, 0}, {600, 0}, {1199, 0}, {1200, 1}, {1201, 1}, {24000, 1}};
        for (long[] row : cooldown) {
            helper.assertTrue(rules.greetingReady(row[0]) == (row[1] == 1), "After " + row[0] + " ticks ready is " + (row[1] == 1));
        }

        // squared distance, expected in range: 6 blocks count, 7 do not
        double[][] range = {{0, 1}, {25, 1}, {36, 1}, {36.01, 0}, {49, 0}, {400, 0}};
        for (double[] row : range) {
            helper.assertTrue(rules.inRange(row[0]) == (row[1] == 1), "Squared distance " + row[0] + " in range is " + (row[1] == 1));
        }

        // sleeping, away, onMission, leading, serving, eating, talking: every blocking state alone stops the check
        boolean[][] situations = {{false, false, false, false, false, false, false, true}, {true, false, false, false, false, false, false, false},
                {false, true, false, false, false, false, false, false}, {false, false, true, false, false, false, false, false},
                {false, false, false, true, false, false, false, false}, {false, false, false, false, true, false, false, false},
                {false, false, false, false, false, true, false, false}, {false, false, false, false, false, false, true, false},
                {true, true, true, true, true, true, true, false}};
        for (boolean[] row : situations) {
            Situation situation = new Situation(row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
            helper.assertTrue(SocialRules.interruptible(situation) == row[7], situation + " is interruptible: " + row[7]);
        }
        helper.assertTrue(SocialRules.interruptible(Situation.FREE), "A free settler is interruptible");

        // conversation: ticks since the settler's last one, conversations today, expected "may talk"; the cooldown is 2000
        // ticks, the limit 2 a day, and both must hold
        long[][] limits = {{Long.MAX_VALUE, 0, 1}, {Long.MAX_VALUE, 1, 1}, {Long.MAX_VALUE, 2, 0}, {Long.MAX_VALUE, 3, 0},
                {0, 0, 0}, {1999, 0, 0}, {2000, 0, 1}, {2000, 1, 1}, {2000, 2, 0}, {24000, 1, 1}, {1999, 2, 0}};
        for (long[] row : limits) {
            helper.assertTrue(rules.mayConverse(row[0], (int) row[1]) == (row[2] == 1),
                    "After " + row[0] + " ticks with " + row[1] + " today: may talk is " + (row[2] == 1));
        }
        helper.assertTrue(rules.points() == 0.5 && rules.conversationTicks() == 120 && rules.walkGiveUp() == 100,
                "A conversation gives 0.5 points, lasts 120 ticks and the walk gives up after 100");

        // the marks in the Entertainment record: a day counts its own conversations, the new day starts again at 0
        long day = 12;
        Entertainment first = Entertainment.START.talked(rules.points(), day, 5000L);
        helper.assertTrue(first.value() == 0.5 && first.talk().on(day) == 1 && first.talk().pointsOn(day) == 0.5
                && first.talk().lastTick() == 5000L, "One conversation: 0.5 points, counted once, tick marked: " + first);
        Entertainment second = first.talked(rules.points(), day, 9000L);
        helper.assertTrue(second.value() == 1.0 && second.talk().on(day) == 2 && second.talk().pointsOn(day) == 1.0
                && second.talk().lastTick() == 9000L, "Two conversations: 1 point, counted twice: " + second);
        helper.assertTrue(second.talk().on(day + 1) == 0 && second.talk().pointsOn(day + 1) == 0.0, "The next day starts at 0");
        helper.assertTrue(second.talk().elapsed(9000L + 2000L) == 2000L && Entertainment.START.talk().elapsed(1L) == Long.MAX_VALUE,
                "Elapsed ticks since the last, and none before the first");
        Entertainment nextDay = second.talked(rules.points(), day + 1, 40000L);
        helper.assertTrue(nextDay.talk().on(day + 1) == 1 && nextDay.value() == 1.5, "A talk on the next day counts 1: " + nextDay);
        // the Bard stands in as "heard something else first" (+1), then a talk (+0.5): 1.5 in total, both counted
        Entertainment heardThenTalked = Entertainment.START.add(1.0, day).talked(rules.points(), day, 5000L);
        helper.assertTrue(heardThenTalked.value() == 1.5 && heardThenTalked.pointsOn(day) == 1.5 && heardThenTalked.talk().pointsOn(day) == 0.5,
                "Heard (+1) then talked (+0.5): 1.5 in total, 0.5 of it from the talk: " + heardThenTalked);
        // the other marks survive a conversation
        Entertainment marked = Entertainment.of(2.0).payTavern(3.0, day).markRecord(day).talked(0.5, day, 1L);
        helper.assertTrue(marked.tavernPaid(day) && marked.recordSettled(day) && marked.value() == 5.5, "Tavern and record marks stay: " + marked);
        // and the other way round: the talk marks survive the decay and the Tavern
        Entertainment kept = second.decayed(day + 3, EntertainmentRules.FALLBACK).payTavern(3.0, day + 3);
        helper.assertTrue(kept.talk().equals(second.talk()), "Decay and the Tavern keep the talk marks: " + kept.talk());

        // datapack files: later values override, missing values keep the defaults
        helper.assertTrue(SocialRules.merge(List.of()).equals(rules), "No file: the defaults");
        SocialRules merged = SocialRules.merge(List.of(json("{\"conversation_chance\": 0.1, \"radius\": 4}"),
                json("{\"conversation_chance\": 0.05, \"greeting_cooldown_ticks\": 600}")));
        helper.assertTrue(merged.equals(new SocialRules(0.05, 0.3, 4, 600, 120, 100, 2000, 2, 0.5)), "Merged in order: " + merged);
        helper.assertTrue(merged.outcome(0.04) == Outcome.CONVERSATION && merged.outcome(0.05) == Outcome.GREETING
                && merged.outcome(0.36) == Outcome.NONE, "The merged shares set the bands");

        // shares that add up to more than 1 leave no room for nothing
        SocialRules crowded = new SocialRules(0.6, 0.6, 6, 1200, 120, 100, 2000, 2, 0.5);
        helper.assertTrue(crowded.outcome(0.59) == Outcome.CONVERSATION && crowded.outcome(0.7) == Outcome.GREETING
                && crowded.outcome(0.9999) == Outcome.GREETING, "Shares above 1 cut the greeting share off");
        helper.succeed();
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }
}
