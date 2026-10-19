package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.job.CraftspersonProfile;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.block.UpgradeCosts;
import com.gilfort.civmod.job.JobProfile;
import com.gilfort.civmod.menu.JobBlockTabs;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the addon API (vault {@code Systems/Jobs.md}): Jobs and a JobBlock registered the way an addon does,
 * the upgrade cost fallback, JobBlock tabs by category and the Mission profile hooks.
 * <p>
 * Registers {@code civmod:gametest_producer} (a Producing Job), {@code civmod:gametest_scout} (a Mission Job whose
 * profile adds 10 % success and removes every death) and {@code civmod:gametest_no_costs} (a Service Job with a JobBlock
 * from the factory, a starter tool and no upgrade cost file).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AddonApiGameTests {

    private static final double EPSILON = 1.0E-9;
    private static final Identifier PRODUCER = CivMod.id("gametest_producer");
    private static final Identifier SCOUT = CivMod.id("gametest_scout");
    private static final Identifier NO_COSTS = CivMod.id("gametest_no_costs");
    private static final Identifier NO_COSTS_BLOCK = CivMod.id("gametest_no_costs_block");

    private AddonApiGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(CivModApi.JOB_REGISTRY, helper -> {
            helper.register(PRODUCER, Job.builder(JobCategory.PRODUCING).icon(() -> Items.STONECUTTER).profile(CraftspersonProfile::new).build());
            helper.register(SCOUT, Job.builder(JobCategory.MISSION).icon(() -> Items.SPYGLASS).profile(() -> new MissionProfile() {
                @Override
                public double successChance(double chance, int recommendedLevel, int skill) {
                    return chance + 0.1;
                }

                @Override
                public double deathChance(double chance, int recommendedLevel, int skill) {
                    return 0.0;
                }
            }).build());
            helper.register(NO_COSTS, Job.builder(JobCategory.SERVICE).icon(() -> Items.BELL).build());
        });
        event.register(Registries.BLOCK, helper -> helper.register(NO_COSTS_BLOCK, CivModApi.jobBlock(() -> job(NO_COSTS),
                BlockBehaviour.Properties.of().strength(2.5F).setId(ResourceKey.create(Registries.BLOCK, NO_COSTS_BLOCK)))));
        event.register(Registries.ITEM, helper -> helper.register(NO_COSTS_BLOCK, CivModApi.jobBlockItem(
                BuiltInRegistries.BLOCK.getValue(NO_COSTS_BLOCK), () -> Items.STONE_SHOVEL,
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, NO_COSTS_BLOCK)))));
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "api_tabs_by_category", AddonApiGameTests::tabsByCategory);
            register(helper, "api_upgrade_cost_fallback", AddonApiGameTests::upgradeCostFallback);
            register(helper, "api_job_block_factory", AddonApiGameTests::jobBlockFactory);
            register(helper, "api_mission_profile_hooks", AddonApiGameTests::missionProfileHooks);
            register(helper, "api_profile_category_check", AddonApiGameTests::profileCategoryCheck);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Job job(Identifier id) {
        return ModJobs.REGISTRY.getValue(id);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    /** The window's tabs follow the Job Category; only the Clerk keeps its own tabs. */
    private static void tabsByCategory(GameTestHelper helper) {
        helper.assertTrue(JobBlockTabs.of(ModJobs.LUMBERJACK.get()) == JobBlockTabs.FOUND, "Resource Collection: Found tab");
        helper.assertTrue(JobBlockTabs.of(ModJobs.CRAFTSPERSON.get()) == JobBlockTabs.PATTERNS, "Craftsperson: Patterns tab");
        helper.assertTrue(JobBlockTabs.of(job(PRODUCER)) == JobBlockTabs.PATTERNS, "Any Producing Job: Patterns tab");
        helper.assertTrue(JobBlockTabs.of(ModJobs.ADVENTURER.get()) == JobBlockTabs.MISSIONS, "Mission: Missions tab");
        helper.assertTrue(JobBlockTabs.of(ModJobs.CLERK.get()) == JobBlockTabs.CLERK, "Clerk keeps its tabs");
        helper.assertTrue(JobBlockTabs.of(job(NO_COSTS)) == JobBlockTabs.STANDARD, "Service: Storage and Info only");
        helper.assertTrue(JobBlockTabs.PATTERNS.count() == 3 && JobBlockTabs.PATTERNS.tall() && JobBlockTabs.CLERK.count() == 5
                && !JobBlockTabs.FOUND.tall(), "Tab counts and panel heights");
        helper.succeed();
    }

    /** A job without an upgrade cost file uses the default costs; a job with a file keeps its own. */
    private static void upgradeCostFallback(GameTestHelper helper) {
        List<UpgradeCosts.Cost> fallback = UpgradeCosts.get(job(NO_COSTS), 2);
        helper.assertTrue(fallback.size() == 2 && fallback.get(0).tag().filter(ItemTags.PLANKS::equals).isPresent()
                && fallback.get(0).count() == 32 && fallback.get(1).item().orElseThrow() == Items.IRON_INGOT && fallback.get(1).count() == 8,
                "Default costs for level 2: " + fallback);
        helper.assertTrue(UpgradeCosts.get(job(NO_COSTS), 5).size() == 3, "Default costs up to level 5");
        List<UpgradeCosts.Cost> dummy = UpgradeCosts.get(job(CivMod.id("gametest_dummy")), 2);
        helper.assertTrue(dummy.size() == 1 && dummy.get(0).item().orElseThrow() == Items.DIAMOND, "Own file wins: " + dummy);
        helper.succeed();
    }

    /**
     * A JobBlock and item from the factory: placed, it registers in a settlement, puts its starter tool into its
     * inventory, cannot be pushed and keeps its level in the dropped item.
     */
    private static void jobBlockFactory(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.getValue(NO_COSTS_BLOCK);
        Item item = BuiltInRegistries.ITEM.getValue(NO_COSTS_BLOCK);
        BlockPos pos = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(pos);
        helper.assertTrue(block.defaultBlockState().getPistonPushReaction() == PushReaction.IMMOVEABLE, "Not pushable");
        ItemStack fresh = new ItemStack(item);
        helper.assertTrue(fresh.has(ModDataComponents.STARTER_TOOL.get()), "New item carries its starter tool");
        StarterToolGameTests.place(helper, helper.makeMockPlayer(GameType.SURVIVAL), fresh, pos);
        JobBlockEntity entity = helper.getBlockEntity(pos, JobBlockEntity.class);
        helper.assertTrue(StarterToolGameTests.contents(helper, pos).stream().anyMatch(stack -> stack.is(Items.STONE_SHOVEL)),
                "Starter tool in the inventory: " + StarterToolGameTests.contents(helper, pos));
        SettlementData data = SettlementData.get(helper.getLevel());
        helper.assertTrue(data.settlementAt(absolute).flatMap(s -> s.block(absolute)).filter(found -> found.jobType() == job(NO_COSTS)).isPresent(),
                "Registered in a settlement with its Job");
        entity.setJobLevel(3);
        ItemStack drop = new ItemStack(item);
        drop.applyComponents(entity.collectComponents());
        helper.assertTrue(drop.getOrDefault(ModDataComponents.JOB_LEVEL.get(), 1) == 3, "The item keeps the level");
        StarterToolGameTests.remove(helper, pos);
        helper.succeed();
    }

    /** Neutral hooks keep the formula; a profile's hooks change the chances, clamped and with death at most 1 − success. */
    private static void missionProfileHooks(GameTestHelper helper) {
        OutcomeRules rules = OutcomeRules.FALLBACK;
        MissionRules.Chances base = MissionRules.chances(rules, 30, 30);
        MissionRules.Chances adventurer = MissionRules.chances(rules, 30, 30, ModJobs.ADVENTURER.get());
        helper.assertTrue(near(base.success(), adventurer.success()) && near(base.death(), adventurer.death()), "Neutral: " + adventurer);
        helper.assertTrue(ModJobs.ADVENTURER.get().profile(MissionProfile.class).orElseThrow() == MissionProfile.NEUTRAL,
                "A Mission Job without a profile gets the neutral one");
        MissionRules.Chances scout = MissionRules.chances(rules, 30, 30, job(SCOUT));
        helper.assertTrue(near(scout.success(), 0.85) && near(scout.death(), 0.0), "Scout: " + scout);
        MissionRules.Chances easy = MissionRules.chances(rules, 1, 100, job(SCOUT));
        helper.assertTrue(near(easy.success(), 1.0), "Clamped to 1: " + easy);
        helper.succeed();
    }

    /** A profile of another Job Category is refused when it is first used. */
    private static void profileCategoryCheck(GameTestHelper helper) {
        Job wrong = Job.builder(JobCategory.PRODUCING).icon(() -> Items.STICK).profile(() -> MissionProfile.NEUTRAL).build();
        boolean refused = false;
        try {
            wrong.profile(MissionProfile.class);
        } catch (IllegalStateException e) {
            refused = true;
        }
        helper.assertTrue(refused, "A Mission profile on a Producing Job is refused");
        helper.assertTrue(ModJobs.MINER.get().profile(JobProfile.class).isPresent() && ModJobs.MINER.get().profile(MissionProfile.class).isEmpty(),
                "The typed accessor returns only a profile of the asked type");
        helper.succeed();
    }
}
