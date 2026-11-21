package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.mastery.MasteryRules;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Table test of the rule module of Mastery (vault {@code Systems/Producing Jobs.md}): the chance by skill, the hits of a
 * roll, the saving by recipe size and the datapack merge.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MasteryRulesGameTests {

    private MasteryRulesGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> register(helper, "mastery_rules_table", MasteryRulesGameTests::rulesTable));
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static void rulesTable(GameTestHelper helper) {
        MasteryRules rules = MasteryRules.FALLBACK;

        // skill, expected chance: skill ÷ 10 %, 0.1 % at level 1 and 10 % at level 100, never more than the maximum
        double[][] chances = {{0, 0.0}, {1, 0.001}, {50, 0.05}, {100, 0.1}, {250, 0.1}};
        for (double[] row : chances) {
            helper.assertTrue(Math.abs(rules.chance((int) row[0]) - row[1]) < 1e-9, "Skill " + row[0] + " has the chance " + row[1] + ": " + rules.chance((int) row[0]));
        }

        // rolls in order, expected hits: a hit rolls again, at most 3 hits
        double hit = MasteryRolls.HIT;
        double miss = MasteryRolls.MISS;
        double[][] rolls = {{miss}, {hit, miss}, {hit, hit, miss}, {hit, hit, hit}, {hit}};
        int[] expected = {0, 1, 2, 3, 3};
        for (int i = 0; i < rolls.length; i++) {
            int hits = rules.hits(100, MasteryRolls.scripted(rolls[i]));
            helper.assertTrue(hits == expected[i], "Rolls " + List.of(rolls[i][0]) + "... give " + expected[i] + " hits: " + hits);
        }
        helper.assertTrue(rules.hits(0, MasteryRolls.always()) == 0, "No skill, no chance, no hit");

        // ingredient items, hits, expected saved: n × 13 % × hits rounded down, at least 1 from 3 items on, none below
        int[][] savings = {{3, 1, 1}, {3, 3, 1}, {2, 3, 0}, {1, 1, 0}, {6, 1, 1}, {6, 3, 2}, {8, 3, 3}, {9, 1, 1}, {9, 2, 2}, {9, 3, 3},
                {20, 3, 7}, {9, 0, 0}};
        for (int[] row : savings) {
            helper.assertTrue(rules.saved(row[0], row[1]) == row[2], row[0] + " items with " + row[1] + " hits save " + row[2] + ": " + rules.saved(row[0], row[1]));
        }

        // the datapack: later files override single values, the rest keeps the defaults
        MasteryRules merged = MasteryRules.merge(List.of(json("{\"max_hits\": 5}"), json("{\"saving_share\": 1.0, \"saving_minimum\": 2}")));
        helper.assertTrue(merged.maxHits() == 5 && merged.savingShare() == 1.0 && merged.savingMinimum() == 2 && merged.chancePerLevel() == rules.chancePerLevel(),
                "Merged: " + merged);
        // everything saved would make the craft free: one item is always used
        helper.assertTrue(merged.saved(3, 3) == 2, "A saving never takes the last item: " + merged.saved(3, 3));
        helper.assertTrue(merged.hits(100, MasteryRolls.always()) == 5, "Five hits at most: " + merged.hits(100, MasteryRolls.always()));
        boolean rejected = false;
        try {
            MasteryRules.merge(List.of(json("{\"chance_per_level\": -1.0}")));
        } catch (IllegalStateException e) {
            rejected = true;
        }
        helper.assertTrue(rejected, "A negative chance is rejected");
        helper.succeed();
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }
}
