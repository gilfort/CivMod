package com.gilfort.civmod.gametest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.BlockingReason;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SimpleCollectionProfile;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.block.JobBlockSettlers;
import com.gilfort.civmod.job.BlockCountScan;
import com.gilfort.civmod.job.CraftspersonProfile;
import com.gilfort.civmod.job.FishermanProfile;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.LumberjackProfile;
import com.gilfort.civmod.job.MinerProfile;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.menu.JobBlockTabs;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * from the factory, a starter tool and no upgrade cost file) and {@code civmod:gametest_collector} (a
 * {@link SimpleCollectionProfile} Job for wool with a JobBlock and a blocking reason without an icon).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AddonApiGameTests {

    private static final double EPSILON = 1.0E-9;
    private static final Identifier PRODUCER = CivMod.id("gametest_producer");
    private static final Identifier SCOUT = CivMod.id("gametest_scout");
    private static final Identifier NO_COSTS = CivMod.id("gametest_no_costs");
    private static final Identifier NO_COSTS_BLOCK = CivMod.id("gametest_no_costs_block");
    private static final Identifier COLLECTOR = CivMod.id("gametest_collector");
    private static final Identifier COLLECTOR_BLOCK = CivMod.id("gametest_collector_block");
    private static final Identifier SAND = Identifier.withDefaultNamespace("sand");
    private static final Identifier RED_SAND = Identifier.withDefaultNamespace("red_sand");

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
            helper.register(COLLECTOR, Job.builder(JobCategory.RESOURCE_COLLECTION).icon(() -> Items.SHEARS)
                    .profile(() -> SimpleCollectionProfile.builder(BlockTags.WOOL).tool(ItemTags.SHOVELS, () -> Items.STONE_SHOVEL)
                            .whenNothingFound(BlockingReason.of(Component.literal("No wool around"))).build())
                    .build());
        });
        event.register(Registries.BLOCK, helper -> {
            helper.register(NO_COSTS_BLOCK, CivModApi.jobBlock(() -> job(NO_COSTS),
                    BlockBehaviour.Properties.of().strength(2.5F).setId(ResourceKey.create(Registries.BLOCK, NO_COSTS_BLOCK))));
            helper.register(COLLECTOR_BLOCK, CivModApi.jobBlock(() -> job(COLLECTOR),
                    BlockBehaviour.Properties.of().strength(2.5F).setId(ResourceKey.create(Registries.BLOCK, COLLECTOR_BLOCK))));
        });
        event.register(Registries.ITEM, helper -> helper.register(NO_COSTS_BLOCK, CivModApi.jobBlockItem(
                BuiltInRegistries.BLOCK.getValue(NO_COSTS_BLOCK), () -> Items.STONE_SHOVEL,
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, NO_COSTS_BLOCK)))));
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "api_tabs_by_category", AddonApiGameTests::tabsByCategory);
            register(helper, "api_job_block_file_fallback", AddonApiGameTests::jobBlockFileFallback);
            register(helper, "api_job_block_factory", AddonApiGameTests::jobBlockFactory);
            register(helper, "api_mission_profile_hooks", AddonApiGameTests::missionProfileHooks);
            register(helper, "api_profile_category_check", AddonApiGameTests::profileCategoryCheck);
            register(helper, "api_resource_collection_profiles", AddonApiGameTests::resourceCollectionProfiles);
            register(helper, "api_simple_collection_profile", AddonApiGameTests::simpleCollectionProfile);
            register(helper, "api_blocked_generic_marker", AddonApiGameTests::blockedGenericMarker);
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
        String storage = "civmod.gui.job_block.tab.storage";
        String info = "civmod.gui.job_block.tab.workers";
        helper.assertValueEqual(tabs(ModJobs.LUMBERJACK.get()), List.of(storage, info, "civmod.gui.job_block.tab.found"), "Resource Collection: Found tab");
        List<String> producing = List.of(storage, info, "civmod.gui.patterns.tab_job", "civmod.gui.station_types.tab");
        helper.assertValueEqual(tabs(ModJobs.CRAFTSPERSON.get()).subList(0, 4), producing, "Craftsperson: Patterns tab");
        helper.assertValueEqual(tabs(job(PRODUCER)), producing, "Any Producing Job: Patterns tab");
        helper.assertValueEqual(tabs(ModJobs.ADVENTURER.get()), List.of(storage, info, "civmod.gui.job_block.tab.missions", "civmod.gui.station_types.tab"),
                "Mission: Missions and Station Types tabs");
        helper.assertValueEqual(tabs(ModJobs.CLERK.get()), List.of(storage, info, "civmod.gui.clerk.tab.stock", "civmod.gui.clerk.tab.tasks"),
                "Clerk keeps its tabs");
        helper.assertValueEqual(tabs(job(NO_COSTS)), List.of(storage, info), "Service: Storage and Info only");
        helper.succeed();
    }

    /** The label keys of the tabs a JobBlock window of that Job gets. */
    private static List<String> tabs(Job job) {
        return JobBlockTabGameTests.keys(JobBlockTabs.of(job));
    }

    /** A job without a {@code civmod_job_blocks} file holds 1 settler on every level; a job with a file reads its list. */
    private static void jobBlockFileFallback(GameTestHelper helper) {
        for (int level = SettlementRules.MIN_LEVEL; level <= SettlementRules.MAX_LEVEL; level++) {
            helper.assertTrue(JobBlockSettlers.settlers(job(NO_COSTS), level) == 1, "1 settler without a file at level " + level);
        }
        helper.assertTrue(JobBlockSettlers.settlers(job(CivMod.id("gametest_dummy")), 5) == 6, "Own file wins");
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
        helper.assertTrue(ModJobs.MINER.get().profile(ResourceCollectionProfile.class).isPresent()
                && ModJobs.MINER.get().profile(MissionProfile.class).isEmpty(),
                "The typed accessor returns only a profile of the asked type");
        helper.succeed();
    }

    /**
     * CivMod's collecting Jobs implement the public profile; the Lumberjack's leaves option is its own, not part of the
     * interface.
     */
    private static void resourceCollectionProfiles(GameTestHelper helper) {
        helper.assertTrue(ModJobs.LUMBERJACK.get().profile(ResourceCollectionProfile.class).filter(LumberjackProfile.class::isInstance).isPresent()
                && ModJobs.MINER.get().profile(ResourceCollectionProfile.class).filter(MinerProfile.class::isInstance).isPresent()
                && ModJobs.FISHERMAN.get().profile(ResourceCollectionProfile.class).filter(FishermanProfile.class::isInstance).isPresent(),
                "Lumberjack, Miner and Fisherman implement the Resource Collection profile");
        helper.assertTrue(Arrays.stream(ResourceCollectionProfile.class.getMethods())
                .noneMatch(method -> method.getName().equals("secondaryTool") || method.getName().equals("hasLeavesOption")),
                "No leaves option in the public profile");
        helper.assertTrue(Arrays.stream(ResourceCollectionProfile.Context.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("secondary")), "No second tool in the public production context");
        helper.succeed();
    }

    /**
     * The builder's rules: the radius rule, types below the minimum count are not found, productivity by the total
     * count, rolls weighted by count among the types not excluded (all excluded: an empty roll), and its scans are
     * saved.
     */
    private static void simpleCollectionProfile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 6; x++) {
            helper.setBlock(new BlockPos(x, 0, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 1), x < 4 ? Blocks.SAND : Blocks.RED_SAND);
        }
        SimpleCollectionProfile profile = SimpleCollectionProfile.builder(BlockTags.SAND).tool(ItemTags.SHOVELS, () -> Items.STONE_SHOVEL)
                .radius(jobBlockLevel -> 3 * jobBlockLevel).minimumCount(3).productivity(total -> total / 10.0).build();
        helper.assertTrue(profile.scanRadius(2) == 6, "Radius rule: " + profile.scanRadius(2));
        helper.assertTrue(profile.isTool(new ItemStack(Items.IRON_SHOVEL)) && !profile.isTool(new ItemStack(Items.IRON_AXE)), "Tool tag");
        ScanResult scan = profile.createScan(helper.absolutePos(new BlockPos(0, 1, 1)), 10).runAll(level);
        helper.assertTrue(scan instanceof BlockCountScan.Result counted && counted.counts().equals(Map.of(SAND, 4)),
                "Red sand found twice stays below the minimum count: " + scan);
        helper.assertTrue(near(profile.productivity(scan), 0.4), "Productivity by total count: " + profile.productivity(scan));

        BlockCountScan.Result area = new BlockCountScan.Result(Map.of(SAND, 30, RED_SAND, 10));
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Map<Item, Integer> rolled = roll(profile, settler, level, helper.absolutePos(new BlockPos(1, 1, 1)), area, Set.of());
        double sand = rolled.getOrDefault(Items.SAND, 0) / 2000.0;
        helper.assertTrue(Math.abs(sand - 0.75) < 0.04 && rolled.getOrDefault(Items.RED_SAND, 0) > 0, "Weighted by count (~75 % sand): " + rolled);
        Map<Item, Integer> noRed = roll(profile, settler, level, helper.absolutePos(new BlockPos(1, 1, 1)), area, Set.of(RED_SAND));
        helper.assertTrue(noRed.equals(Map.of(Items.SAND, 2000)), "Excluded red sand leaves the roll: " + noRed);
        Map<Item, Integer> none = roll(profile, settler, level, helper.absolutePos(new BlockPos(1, 1, 1)), area, Set.of(SAND, RED_SAND));
        helper.assertTrue(none.equals(Map.of(Items.AIR, 2000)), "All excluded: empty rolls that still count as work: " + none);
        settler.discard();

        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SavedScan saved = SavedScan.of(BlockPos.ZERO, BlockPos.ZERO, 10, 3L, area).orElseThrow();
        SavedScan loaded = SavedScan.CODEC.parse(ops, SavedScan.CODEC.encodeStart(ops, saved).getOrThrow()).getOrThrow();
        helper.assertTrue(area.equals(loaded.result()), "Saved and loaded: " + loaded.result());
        helper.succeed();
    }

    /** Rolls 2000 times; an empty roll (nothing produced, but work done) counts as air. */
    private static Map<Item, Integer> roll(SimpleCollectionProfile profile, Settler settler, ServerLevel level, BlockPos poi, ScanResult scan,
            Set<Identifier> excluded) {
        Map<Item, Integer> counts = new HashMap<>();
        ItemStack tool = new ItemStack(Items.STONE_SHOVEL);
        for (int i = 0; i < 2000; i++) {
            Optional<ResourceCollectionProfile.Produced> produced = profile.produce(new ResourceCollectionProfile.Context(level, settler, scan, poi,
                    tool, excluded, 1, Set.of(), type -> profile.droppedItems(level, poi, type, tool)));
            items(produced).forEach(item -> counts.merge(item, 1, Integer::sum));
        }
        return counts;
    }

    private static List<Item> items(Optional<ResourceCollectionProfile.Produced> produced) {
        if (produced.isEmpty()) {
            return List.of(Items.BARRIER);
        }
        return produced.get().stacks().isEmpty() ? List.of(Items.AIR) : produced.get().stacks().stream().map(ItemStack::getItem).toList();
    }

    /**
     * A blocking reason without an icon: status "Blocked" with the reason as text, and the problem bubble shows the
     * generic "blocked" marker (a barrier).
     */
    private static void blockedGenericMarker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BuiltInRegistries.BLOCK.getValue(COLLECTOR_BLOCK));
        BlockPos jobBlock = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_SHOVEL));
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, settler, job(COLLECTOR)) == JobAssignments.AssignResult.OK, "Collector assigned");
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.BLOCKED, "Blocked: " + settler.getWork().status(jobBlock));
        helper.assertTrue(settler.getWork().statusText(jobBlock).getString().equals("No wool around"),
                "Reason as status text: " + settler.getWork().statusText(jobBlock).getString());
        ThoughtBubble.Show marker = new ThoughtBubble.Show(settler.getId(), ThoughtBubble.BLOCKED, Items.BARRIER, true);
        helper.assertTrue(settler.getWork().recentThoughts().equals(List.of(marker)),
                "Generic marker: " + settler.getWork().recentThoughts());
        TestAssignments.unassign(data, jobBlock);
        settler.discard();
        helper.succeed();
    }
}
