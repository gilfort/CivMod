package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the need tiers (vault {@code Systems/Needs.md}): the tier from the capped levels, the grace day, the
 * downgrade to the top of the lower tier and the instant climb back.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class NeedTierGameTests {

    private NeedTierGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "need_tier_bounds", NeedTierGameTests::bounds);
            register(helper, "need_state_downgrade", NeedTierGameTests::downgrade);
            register(helper, "need_state_climb", NeedTierGameTests::climb);
            register(helper, "need_state_saved", NeedTierGameTests::saved);
            register(helper, "need_tier_from_current_jobs", NeedTierGameTests::tierFromCurrentJobs);
            register(helper, "need_food_hunger_state", NeedTierGameTests::foodNeed);
            register(helper, "need_grace_then_downgrade", NeedTierGameTests::graceThenDowngrade);
            register(helper, "need_instant_climb", NeedTierGameTests::instantClimb);
            register(helper, "need_all_jobs_capped", NeedTierGameTests::allJobsCapped);
            register(helper, "need_survives_relog", NeedTierGameTests::survivesRelog);
            register(helper, "need_catch_up_downgrade", NeedTierGameTests::catchUpDowngrade);
            register(helper, "need_report_rows", NeedTierGameTests::reportRows);
        });
    }

    private static final long DAY = 24000L;
    /** A JobBlock of level 3: skill cap 60. */
    private static final int BLOCK_LEVEL = 3;

    /** Registers a JobBlock of a level without a block in the world (pure settlement rules). */
    private static BlockPos place(GameTestHelper helper, BlockPos relative, Job job, int level) {
        BlockPos pos = helper.absolutePos(relative);
        SettlementManager.place(SettlementData.get(helper.getLevel()), pos, job, level, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        return pos;
    }

    /** A settler of the JobBlock's settlement that does not move, hungry without any food, with a job there. */
    private static Settler hungry(GameTestHelper helper, BlockPos jobBlock, Job job, int skill) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        settler.getFood().setSaturation(0.0F);
        settler.getFood().setLevel(4);
        settler.getFood().setAppetite(1.0F);
        settler.getInventory().setItem(Settler.POCKETS_START, ItemStack.EMPTY);
        settler.getSkills().set(job, new SettlerSkills.Skill(skill, 0));
        settler.addJob(new SettlerJob(job, jobBlock));
        return settler;
    }

    private static void end(GameTestHelper helper, Settler settler, BlockPos... jobBlocks) {
        settler.discard();
        for (BlockPos pos : jobBlocks) {
            SettlementManager.remove(SettlementData.get(helper.getLevel()), pos, SettlementManager.Listener.NONE);
        }
        helper.succeed();
    }

    /** The highest capped target level of the current jobs; skills of other jobs do not count. */
    private static void tierFromCurrentJobs(GameTestHelper helper) {
        BlockPos high = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        BlockPos low = place(helper, new BlockPos(3, 1, 1), ModJobs.MINER.get(), 1);
        Settler settler = hungry(helper, high, ModJobs.LUMBERJACK.get(), 70);
        settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(90, 0));
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), low));
        settler.getSkills().set(ModJobs.ADVENTURER.get(), new SettlerSkills.Skill(100, 0));
        helper.assertTrue(settler.getWork().needTier() == 3, "70 capped at 60, 90 capped at 20, 100 not held: " + settler.getWork().needTier());
        settler.removeJob(high);
        helper.assertTrue(settler.getWork().needTier() == 1, "Only the job at the level 1 JobBlock is left: " + settler.getWork().needTier());
        settler.removeJob(low);
        helper.assertTrue(settler.getWork().needTier() == 1, "No job: tier 1: " + settler.getWork().needTier());
        end(helper, settler, high, low);
    }

    /** Food is met unless the settler is in the hunger state; provisions, a full stomach count as met. */
    private static void foodNeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        helper.assertFalse(food(settler, level).met(), "Hungry without food: unmet");
        helper.assertTrue(food(settler, level).detail().getContents() instanceof TranslatableContents key && key.getKey().equals("civmod.settler.status.hungry"),
                "The hunger state as detail: " + food(settler, level));

        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD));
        helper.assertTrue(food(settler, level).met(), "Hungry with provisions: not the hunger state");
        settler.getInventory().setItem(Settler.POCKETS_START, ItemStack.EMPTY);

        settler.getFood().setLevel(15);
        helper.assertTrue(food(settler, level).met(), "Not hungry");

        settler.getFood().startForaging(1L);
        helper.assertFalse(food(settler, level).met(), "Foraging: the hunger state");
        settler.getFood().endForaging();
        end(helper, settler, jobBlock);
    }

    private static NeedsReport.Row food(Settler settler, ServerLevel level) {
        return settler.getWork().needsReport(level).rows(1).get(0);
    }

    /** One row per need of its tier, carried to the client with the settler info; tier the settler works in. */
    private static void reportRows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        NeedsReport report = SettlerInfo.of(settler).needs();
        helper.assertTrue(report.tier() == 3, "Tier from its job: " + report);
        helper.assertTrue(report.rows(1).size() == 1 && !report.rows(1).get(0).met(), "Tier 1 holds food, unmet: " + report);
        for (int tier = 2; tier <= NeedState.MAX_TIER; tier++) {
            helper.assertTrue(report.rows(tier).isEmpty(), "Needs without a system are hidden, tier " + tier);
        }
        settler.getWork().checkNeedsNow(level, 100L);
        settler.getWork().checkNeedsNow(level, 101L);
        settler.getWork().checkNeedsNow(level, 102L);
        helper.assertTrue(SettlerInfo.of(settler).needs().tier() == 1, "Works in tier 1 now: " + SettlerInfo.of(settler).needs());
        end(helper, settler, jobBlock);
    }

    /** 45: grace day at 45 with a complaint, then 40, then 20; the settler info shows why. */
    private static void graceThenDowngrade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        SettlerJob job = settler.getJobs().get(0);

        settler.getWork().checkNeedsNow(level, 100L);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 45, "Grace day at the full level: " + settler.getWork().effectiveLevel(job));
        helper.assertTrue(settler.getWork().needState().inGrace(), "On its grace day");
        helper.assertFalse(SettlerInfo.of(settler).jobStates().get(0).skill().limitedByNeeds(), "Not limited yet");

        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().recentThoughts().contains(new ThoughtBubble.Show(settler.getId(), ThoughtBubble.NEED_UNMET, Items.BREAD, true)),
                "Complains with the icon of the missing need: " + settler.getWork().recentThoughts());

        settler.getFood().endForaging();
        settler.getWork().checkNeedsNow(level, 101L);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 40, "Next morning at the top of tier 2: " + settler.getWork().effectiveLevel(job));
        SettlerInfo.SkillInfo skill = SettlerInfo.of(settler).jobStates().get(0).skill();
        helper.assertTrue(skill.level() == 45 && skill.effective() == 40 && skill.limited() && skill.limitedByNeeds(), "The job tab shows the cap: " + skill);

        settler.getWork().checkNeedsNow(level, 102L);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 20, "Tier 2 unmet too: " + settler.getWork().effectiveLevel(job));
        end(helper, settler, jobBlock);
    }

    /** The 5 s decision step climbs back as soon as the needs are met; the complaint ends. */
    private static void instantClimb(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        SettlerJob job = settler.getJobs().get(0);
        long today = DayClock.day(level.getOverworldClockTime());
        settler.getWork().checkNeedsNow(level, today - 2);
        settler.getWork().checkNeedsNow(level, today - 1);
        settler.getWork().checkNeedsNow(level, today);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 20, "Downgraded: " + settler.getWork().effectiveLevel(job));

        settler.getFood().setLevel(15);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 45, "Back at once: " + settler.getWork().effectiveLevel(job));
        helper.assertFalse(settler.getWork().needState().inGrace(), "No grace day left");
        end(helper, settler, jobBlock);
    }

    /** Every current job is capped at the top of the lower tier, also a lower one below its own cap stays as it is. */
    private static void allJobsCapped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        BlockPos second = place(helper, new BlockPos(3, 1, 1), ModJobs.MINER.get(), BLOCK_LEVEL);
        BlockPos third = place(helper, new BlockPos(5, 1, 1), ModJobs.FISHERMAN.get(), BLOCK_LEVEL);
        Settler settler = hungry(helper, first, ModJobs.LUMBERJACK.get(), 45);
        settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(55, 0));
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), second));
        settler.getSkills().set(ModJobs.FISHERMAN.get(), new SettlerSkills.Skill(10, 0));
        settler.addJob(new SettlerJob(ModJobs.FISHERMAN.get(), third));
        settler.getWork().checkNeedsNow(level, 100L);
        settler.getWork().checkNeedsNow(level, 101L);
        List<Integer> levels = settler.getJobs().stream().map(settler.getWork()::effectiveLevel).toList();
        helper.assertTrue(levels.equals(List.of(40, 40, 10)), "All capped at 40: " + levels);
        end(helper, settler, first, second, third);
    }

    /** Grace mark and need tier are saved with the settler. */
    private static void survivesRelog(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        settler.getWork().checkNeedsNow(level, 100L);
        settler.getWork().checkNeedsNow(level, 101L);
        NeedState before = settler.getWork().needState();

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        helper.assertTrue(loaded.getWork().needState().equals(before) && before.workTier() == 2 && before.inGrace(),
                "Saved with the settler: " + loaded.getWork().needState());
        loaded.discard();
        end(helper, settler, jobBlock);
    }

    /** Two missed days without food: the first is the grace day, the second is worked one tier lower. */
    private static void catchUpDowngrade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        settler.getFood().setLevel(0);
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY;
        settler.getWork().setCatchUp(dayStart - 2 * DAY, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(settler.getWork().effectiveLevel(settler.getJobs().get(0)) == 40,
                "Grace day, then the top of tier 2: " + settler.getWork().needState() + ", food " + settler.getFood().level());
        end(helper, settler, jobBlock);
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Lv 1–20, 21–40, 41–60, 61–80, 81+. */
    private static void bounds(GameTestHelper helper) {
        int[][] tierOfLevel = {{1, 1}, {20, 1}, {21, 2}, {40, 2}, {41, 3}, {60, 3}, {61, 4}, {80, 4}, {81, 5}, {100, 5}};
        for (int[] pair : tierOfLevel) {
            helper.assertTrue(NeedState.tierOf(pair[0]) == pair[1], "Level " + pair[0] + " is tier " + pair[1] + ": " + NeedState.tierOf(pair[0]));
        }
        int[][] topOfTier = {{1, 20}, {2, 40}, {3, 60}, {4, 80}, {5, 100}};
        for (int[] pair : topOfTier) {
            helper.assertTrue(NeedState.topLevel(pair[0]) == pair[1], "Top of tier " + pair[0] + ": " + NeedState.topLevel(pair[0]));
        }
        helper.succeed();
    }

    /** Tier 3 and everything below unmet: 45 → one grace day → 40 → 20, then it stays at the bottom and keeps complaining. */
    private static void downgrade(GameTestHelper helper) {
        int tier = 3;
        int noneMet = 0;
        NeedState state = NeedState.START.morning(10L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 45 && state.inGrace(), "Day 1 is the grace day at the full level: " + state);

        state = state.morning(11L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 40 && state.inGrace(), "Next morning at the top of tier 2, its grace day: " + state);

        state = state.morning(12L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 20 && state.inGrace(), "Tier 2 is unmet too: 20: " + state);

        state = state.morning(13L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 20 && state.inGrace(), "Nothing below tier 1: " + state);

        NeedState lowerMet = NeedState.START.morning(10L, tier, 2).morning(11L, tier, 2).morning(12L, tier, 2);
        helper.assertTrue(lowerMet.limit(45) == 40 && !lowerMet.inGrace(), "Stays at the tier that is met: " + lowerMet);

        NeedState sameDay = NeedState.START.morning(10L, tier, noneMet).morning(10L, tier, noneMet);
        helper.assertTrue(sameDay.limit(45) == 45, "A second check on the same day is not the next morning: " + sameDay);

        NeedState met = NeedState.START.morning(10L, tier, 5);
        helper.assertTrue(met.equals(new NeedState(NeedState.MAX_TIER, NeedState.NO_DAY, 10L)), "Met: nothing happens: " + met);
        helper.succeed();
    }

    /** Met again: back at once; a grace day ends without a downgrade; a new, higher tier gets its own grace day. */
    private static void climb(GameTestHelper helper) {
        int tier = 3;
        NeedState down = NeedState.START.morning(10L, tier, 0).morning(11L, tier, 0).morning(12L, tier, 0);
        NeedState back = down.climb(tier, 5);
        helper.assertTrue(back.limit(45) == 45 && !back.inGrace(), "Met again: instantly at the full level: " + back);

        NeedState partly = down.climb(tier, 2);
        helper.assertTrue(partly.limit(45) == 40 && !partly.inGrace(), "Tier 2 met again, tier 3 not: 40: " + partly);

        NeedState grace = NeedState.START.morning(10L, tier, 0);
        NeedState endedGrace = grace.climb(tier, 3);
        helper.assertTrue(!endedGrace.inGrace() && endedGrace.morning(11L, tier, 3).limit(45) == 45, "The grace day ended: no downgrade: " + endedGrace);

        NeedState unmetButNoNews = grace.climb(tier, 0);
        helper.assertTrue(unmetButNoNews.equals(grace), "Still unmet: the re-check changes nothing: " + unmetButNoNews);

        helper.assertTrue(NeedState.START.morning(10L, 1, 5).settled(1), "Met, nothing to climb to");
        helper.assertFalse(grace.settled(tier), "A grace day may end: re-check");
        helper.assertFalse(down.settled(tier), "Works below its tier: re-check");

        NeedState levelledUp = NeedState.START.morning(10L, 1, 1).morning(11L, 2, 1);
        helper.assertTrue(levelledUp.limit(21) == 21 && levelledUp.inGrace(), "A settler reaching tier 2 gets its grace day first: " + levelledUp);
        helper.succeed();
    }

    /** The current need tier, the grace mark and the day of the last check survive saving. */
    private static void saved(GameTestHelper helper) {
        NeedState state = new NeedState(2, 11L, 12L);
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        NeedState loaded = NeedState.CODEC.parse(ops, NeedState.CODEC.encodeStart(ops, state).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.equals(state), "Saved: " + loaded);
        NeedState fresh = NeedState.CODEC.parse(ops, NeedState.CODEC.encodeStart(ops, NeedState.START).getOrThrow()).getOrThrow();
        helper.assertTrue(fresh.equals(NeedState.START), "No day stays no day: " + fresh);
        helper.succeed();
    }
}
