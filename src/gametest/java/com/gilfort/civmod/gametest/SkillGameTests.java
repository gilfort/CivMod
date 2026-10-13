package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.block.UpgradeCosts;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SkillRules;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the EXP and JobBlock upgrade story.
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
            register(helper, "skill_upgrade_costs", SkillGameTests::upgradeCosts);
            register(helper, "skill_upgrade_in_world", SkillGameTests::upgradeInWorld);
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
        helper.assertTrue(skills.addExp(JobType.MINER, 20, 20, rules) == 1 && skills.get(JobType.MINER).level() == 2
                && skills.get(JobType.MINER).exp() == 0, "20 EXP: level 2");
        skills.addExp(JobType.MINER, 100000, 20, rules);
        helper.assertTrue(skills.get(JobType.MINER).level() == 20 && skills.get(JobType.MINER).exp() == 0, "Stops at the cap, excess discarded");
        helper.assertTrue(skills.addExp(JobType.MINER, 50, 20, rules) == 0 && skills.get(JobType.MINER).exp() == 0, "No EXP at the cap");
        helper.assertTrue(skills.get(JobType.LUMBERJACK).level() == 1, "Skill is per job type");

        skills.set(JobType.FISHERMAN, new SettlerSkills.Skill(45, 3));
        helper.assertTrue(skills.effectiveLevel(JobType.FISHERMAN, SettlementRules.skillCap(1)) == 20, "Works at the cap of a level-1 JobBlock");
        helper.assertTrue(skills.addExp(JobType.FISHERMAN, 50, 20, rules) == 0 && skills.get(JobType.FISHERMAN).level() == 45,
                "Higher skill is kept, no EXP below it");
        helper.assertTrue(skills.effectiveLevel(JobType.FISHERMAN, SettlementRules.skillCap(3)) == 45, "Full skill at a level-3 JobBlock");
        helper.succeed();
    }

    /** Later files override earlier ones. */
    private static void rulesMerge(GameTestHelper helper) {
        SkillRules merged = SkillRules.merge(List.of(JsonParser.parseString("{\"base\":10,\"base_exp\":{\"miner\":2}}"),
                JsonParser.parseString("{\"per_level\":3,\"base_exp\":{\"miner\":4}}")));
        helper.assertTrue(merged.base() == 10 && merged.perLevel() == 3 && merged.baseExp(JobType.MINER) == 4
                && merged.baseExp(JobType.FISHERMAN) == 5, "Merged rules: " + merged);
        helper.assertTrue(SkillRules.get().baseExp(JobType.FISHERMAN) == 5, "Loaded default data");
        helper.succeed();
    }

    /** Costs from the default data; item and tag costs are checked and paid. */
    private static void upgradeCosts(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        List<UpgradeCosts.Cost> miner = UpgradeCosts.get(JobType.MINER, 2);
        helper.assertTrue(miner.size() == 2, "Miner level 2 costs loaded: " + miner);
        player.getInventory().add(new ItemStack(Items.COBBLESTONE, 40));
        helper.assertFalse(UpgradeCosts.canAfford(player.getInventory(), miner), "Iron is missing");
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 8));
        helper.assertTrue(UpgradeCosts.canAfford(player.getInventory(), miner), "Affordable");
        UpgradeCosts.pay(player.getInventory(), miner);
        helper.assertTrue(player.getInventory().countItem(Items.COBBLESTONE) == 8 && player.getInventory().countItem(Items.IRON_INGOT) == 0,
                "Paid exactly the costs");

        List<UpgradeCosts.Cost> lumberjack = UpgradeCosts.get(JobType.LUMBERJACK, 2);
        player.getInventory().add(new ItemStack(Items.OAK_LOG, 20));
        player.getInventory().add(new ItemStack(Items.BIRCH_LOG, 12));
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 8));
        helper.assertTrue(UpgradeCosts.canAfford(player.getInventory(), lumberjack), "Any logs pay the tag cost");
        helper.assertTrue(UpgradeCosts.get(JobType.FISHERMAN, 6).isEmpty(), "No level 6");
        helper.succeed();
    }

    /** Upgrading via the menu pays, raises block and settlement level (cap, radius) and keeps the level on the item. */
    private static void upgradeInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.MINER_BLOCK.get());
        BlockPos pos = helper.absolutePos(relative);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 8));
        helper.runAfterDelay(1, () -> {
            JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(pos);
            JobBlockMenu menu = new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(level, pos, JobType.MINER, entity.getJobLevel()));
            helper.assertTrue(menu.clickMenuButton(player, JobBlockMenu.BUTTON_UPGRADE), "Upgrade should succeed");
            helper.assertTrue(entity.getJobLevel() == 2 && menu.getInfo().level() == 2, "JobBlock level 2");
            int settlementLevel = SettlementData.get(level).settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow().level();
            helper.assertTrue(settlementLevel == 2 && SettlementRules.joinRadius(settlementLevel) == 20, "Settlement entry level 2 (radius 20)");
            helper.assertTrue(player.getInventory().countItem(Items.COBBLESTONE) == 0 && player.getInventory().countItem(Items.IRON_INGOT) == 0,
                    "Costs paid");
            helper.assertFalse(menu.clickMenuButton(player, JobBlockMenu.BUTTON_UPGRADE), "Level 3 is not affordable");
            ItemStack drop = new ItemStack(ModBlocks.MINER_BLOCK.get());
            drop.applyComponents(entity.collectComponents());
            helper.assertTrue(drop.getOrDefault(com.gilfort.civmod.registry.ModDataComponents.JOB_LEVEL.get(), 1) == 2, "Item keeps the level");
            helper.succeed();
        });
    }
}
