package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SkillRules;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementRules;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the EXP story (JobBlock upgrades: {@link UpgradeItemGameTests}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SkillGameTests {

    private SkillGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "skill_exp_and_cap", SkillGameTests::expAndCap);
            register(helper, "skill_rules_merge", SkillGameTests::rulesMerge);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Level curve, cap with discarded EXP, effective level above the cap, skill per job type. */
    private static void expAndCap(GameTestHelper helper) {
        SkillRules rules = SkillRules.FALLBACK;
        helper.assertTrue(rules.expToNext(1) == 20 && rules.expToNext(19) == 110, "EXP to next level = 15 + 5 × level");
        SettlerSkills skills = new SettlerSkills();
        helper.assertTrue(skills.addExp(ModJobs.MINER.get(), 20, 20, rules) == 1 && skills.get(ModJobs.MINER.get()).level() == 2
                && skills.get(ModJobs.MINER.get()).exp() == 0, "20 EXP: level 2");
        skills.addExp(ModJobs.MINER.get(), 100000, 20, rules);
        helper.assertTrue(skills.get(ModJobs.MINER.get()).level() == 20 && skills.get(ModJobs.MINER.get()).exp() == 0, "Stops at the cap, excess discarded");
        helper.assertTrue(skills.addExp(ModJobs.MINER.get(), 50, 20, rules) == 0 && skills.get(ModJobs.MINER.get()).exp() == 0, "No EXP at the cap");
        helper.assertTrue(skills.get(ModJobs.LUMBERJACK.get()).level() == 1, "Skill is per job type");

        skills.set(ModJobs.FISHERMAN.get(), new SettlerSkills.Skill(45, 3));
        helper.assertTrue(skills.effectiveLevel(ModJobs.FISHERMAN.get(), SettlementRules.skillCap(1)) == 20, "Works at the cap of a level-1 JobBlock");
        helper.assertTrue(skills.addExp(ModJobs.FISHERMAN.get(), 50, 20, rules) == 0 && skills.get(ModJobs.FISHERMAN.get()).level() == 45,
                "Higher skill is kept, no EXP below it");
        helper.assertTrue(skills.effectiveLevel(ModJobs.FISHERMAN.get(), SettlementRules.skillCap(3)) == 45, "Full skill at a level-3 JobBlock");
        helper.succeed();
    }

    /** Later files override earlier ones. */
    private static void rulesMerge(GameTestHelper helper) {
        SkillRules merged = SkillRules.merge(List.of(JsonParser.parseString("{\"base\":10,\"per_level\":2}"),
                JsonParser.parseString("{\"per_level\":3}")));
        helper.assertTrue(merged.base() == 10 && merged.perLevel() == 3, "Merged rules: " + merged);
        helper.assertTrue(SkillRules.get().equals(SkillRules.FALLBACK), "Loaded default data: " + SkillRules.get());
        helper.succeed();
    }
}
