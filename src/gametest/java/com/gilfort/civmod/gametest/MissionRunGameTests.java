package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.job.SkillRules;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestAssignments;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for running a mission (vault {@code Systems/Mission Jobs.md}): the mission rules, and a settler that takes a
 * marked mission, leaves (the GameTest clock is frozen at 08:00, inside the departure window), is away and returns live
 * or during catch-up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionRunGameTests {

    private static final double EPSILON = 1.0E-9;
    private static final long DAY = 24000L;
    static final BlockPos BOARD = new BlockPos(1, 1, 1);
    /** Bed head of the settler in the death tests (only the settlement entry, no bed block). */
    private static final BlockPos BED = new BlockPos(7, 1, 7);
    /** Level of the test board (skill cap 40). */
    private static final int BOARD_LEVEL = 2;
    /** Mission Job skill of the test settler: every sample mission fits, below the board cap. */
    private static final int SKILL = 25;
    /** Settler and random source of the pick rule test. */
    private static final UUID SETTLER = new UUID(0L, 1L);
    private static final RandomSource RANDOM = RandomSource.create(0L);

    private MissionRunGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_chances", MissionRunGameTests::chances);
            register(helper, "mission_outcome_rules_merge", MissionRunGameTests::outcomeRulesMerge);
            register(helper, "mission_pick_rule", MissionRunGameTests::pickRule);
            register(helper, "mission_pick_order", MissionRunGameTests::pickOrder);
            register(helper, "mission_rolls_and_exp", MissionRunGameTests::rollsAndExp);
            register(helper, "mission_full_cycle", MissionRunGameTests::fullCycle);
            register(helper, "mission_catch_up", MissionRunGameTests::catchUp);
            register(helper, "mission_persistence", MissionRunGameTests::persistence);
            register(helper, "mission_return_ends_work_day", MissionRunGameTests::returnEndsWorkDay);
            register(helper, "mission_resume_leaving", MissionRunGameTests::resumeLeaving);
            register(helper, "mission_death", MissionRunGameTests::death);
            register(helper, "mission_death_catch_up", MissionRunGameTests::deathCatchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    static Mission mission(int recommendedLevel, int rolls) {
        return new Mission(Component.literal("Test"), Component.empty(), Optional.empty(), List.of(BuiltInLootTables.SIMPLE_DUNGEON), 1,
                recommendedLevel, rolls, List.of(ModJobs.ADVENTURER.get()), 1, 1);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    /** Success / death chances at gaps 0, +20, -20 and at the clamps; failed is the rest. */
    private static void chances(GameTestHelper helper) {
        OutcomeRules rules = OutcomeRules.FALLBACK;
        MissionRules.Chances equal = MissionRules.chances(rules, 30, 30);
        helper.assertTrue(near(equal.success(), 0.75) && near(equal.death(), 0.03) && near(equal.failed(), 0.22), "Gap 0: " + equal);
        MissionRules.Chances above = MissionRules.chances(rules, 40, 20);
        helper.assertTrue(near(above.success(), 0.35) && near(above.death(), 0.33) && near(above.failed(), 0.32), "Gap +20: " + above);
        MissionRules.Chances below = MissionRules.chances(rules, 20, 40);
        helper.assertTrue(near(below.success(), 0.95) && near(below.death(), 0.01) && near(below.failed(), 0.04), "Gap -20: " + below);
        MissionRules.Chances hopeless = MissionRules.chances(rules, 100, 1);
        helper.assertTrue(near(hopeless.success(), 0.05) && near(hopeless.death(), 0.60) && near(hopeless.failed(), 0.35), "Gap +99: " + hopeless);
        MissionRules.Chances easy = MissionRules.chances(rules, 1, 100);
        helper.assertTrue(near(easy.success(), 0.95) && near(easy.death(), 0.01), "Gap -99: " + easy);

        RandomSource random = RandomSource.create(11L);
        Map<MissionRules.Outcome, Integer> counts = new EnumMap<>(MissionRules.Outcome.class);
        for (int i = 0; i < 10000; i++) {
            counts.merge(MissionRules.roll(above, random), 1, Integer::sum);
        }
        helper.assertTrue(Math.abs(counts.getOrDefault(MissionRules.Outcome.SUCCESS, 0) - 3500) < 250
                && Math.abs(counts.getOrDefault(MissionRules.Outcome.DEATH, 0) - 3300) < 250, "Rolled outcomes follow the chances: " + counts);
        helper.succeed();
    }

    /** Outcome constants from several files are merged; later values override, missing ones keep the fallback. */
    private static void outcomeRulesMerge(GameTestHelper helper) {
        OutcomeRules merged = OutcomeRules.merge(List.of(JsonParser.parseString("{\"success_base\":0.5,\"death_max\":0.9}"),
                JsonParser.parseString("{\"success_base\":0.6}")));
        helper.assertTrue(near(merged.successBase(), 0.6) && near(merged.deathMax(), 0.9), "Merged: " + merged);
        helper.assertTrue(near(merged.successPerLevel(), OutcomeRules.FALLBACK.successPerLevel())
                && near(merged.successExp(), 2.0) && near(merged.failedExp(), 0.8), "Fallback kept: " + merged);
        helper.assertTrue(near(MissionRules.chances(merged, 10, 10).success(), 0.6), "Merged constants are used");
        helper.succeed();
    }

    /**
     * The settler takes the marked mission with the highest recommended level at or below its skill; unmarked missions,
     * missions above its skill and missions its job may not take are never picked.
     */
    private static void pickRule(GameTestHelper helper) {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(CivMod.id("lv5"), mission(5, 1));
        pool.put(CivMod.id("lv12"), mission(12, 1));
        pool.put(CivMod.id("lv15"), mission(15, 1));
        pool.put(CivMod.id("lv30"), mission(30, 1));
        pool.put(CivMod.id("other_job"), new Mission(Component.literal("Other"), Component.empty(), Optional.empty(),
                List.of(BuiltInLootTables.SIMPLE_DUNGEON), 1, 1, 1, List.of(ModJobs.LUMBERJACK.get()), 1, 1));
        List<MissionOffer.Entry> offer = List.of(new MissionOffer.Entry(CivMod.id("lv5"), true), new MissionOffer.Entry(CivMod.id("lv15"), false),
                new MissionOffer.Entry(CivMod.id("lv12"), true), new MissionOffer.Entry(CivMod.id("lv30"), true),
                new MissionOffer.Entry(CivMod.id("other_job"), true), new MissionOffer.Entry(CivMod.id("removed"), true));
        OptionalInt pick = MissionRules.pick(offer, pool, ModJobs.ADVENTURER.get(), 20, SETTLER, RANDOM);
        helper.assertTrue(pick.equals(OptionalInt.of(2)), "Skill 20 takes the marked Lv 12 (Lv 15 is unmarked): " + pick);
        helper.assertTrue(MissionRules.pick(offer, pool, ModJobs.ADVENTURER.get(), 12, SETTLER, RANDOM).equals(OptionalInt.of(2)), "At its level");
        helper.assertTrue(MissionRules.pick(offer, pool, ModJobs.ADVENTURER.get(), 11, SETTLER, RANDOM).equals(OptionalInt.of(0)), "Skill 11 takes Lv 5");
        helper.assertTrue(MissionRules.pick(offer, pool, ModJobs.ADVENTURER.get(), 4, SETTLER, RANDOM).isEmpty(), "Nothing at or below skill 4");
        helper.assertTrue(MissionRules.pick(offer, pool, ModJobs.MINER.get(), 100, SETTLER, RANDOM).isEmpty(), "A job no mission allows takes nothing");
        helper.assertTrue(MissionRules.pick(List.of(), pool, ModJobs.ADVENTURER.get(), 100, SETTLER, RANDOM).isEmpty(), "Empty offer");
        helper.succeed();
    }

    /** Settlers picking at the same time go in ascending skill; equal skills in random order. */
    private static void pickOrder(GameTestHelper helper) {
        record Candidate(String name, int skill) {
        }
        List<Candidate> candidates = List.of(new Candidate("a", 30), new Candidate("b", 10), new Candidate("c", 20), new Candidate("d", 10));
        RandomSource random = RandomSource.create(4L);
        boolean bFirst = false;
        boolean dFirst = false;
        for (int i = 0; i < 50; i++) {
            List<Candidate> order = MissionRules.order(candidates, Candidate::skill, random);
            List<String> names = order.stream().map(Candidate::name).toList();
            helper.assertTrue(names.subList(2, 4).equals(List.of("c", "a")), "Ascending skill: " + names);
            helper.assertTrue(new ArrayList<>(names.subList(0, 2)).containsAll(List.of("b", "d")), "Lowest skills first: " + names);
            bFirst |= names.get(0).equals("b");
            dFirst |= names.get(0).equals("d");
        }
        helper.assertTrue(bFirst && dFirst, "Ties are ordered randomly");
        helper.assertTrue(candidates.get(0).name().equals("a"), "The input list is not changed");
        helper.succeed();
    }

    /**
     * Rolls: base + 1 per full 20 levels above the recommendation. EXP: base × board level × mission level, × 2 on success,
     * × 0.8 on failure, none on death.
     */
    private static void rollsAndExp(GameTestHelper helper) {
        Mission mission = mission(10, 2);
        helper.assertTrue(MissionRules.rolls(mission, 5) == 2, "Below the recommendation: base rolls");
        helper.assertTrue(MissionRules.rolls(mission, 29) == 2, "19 levels above: base rolls");
        helper.assertTrue(MissionRules.rolls(mission, 30) == 3, "20 levels above: +1");
        helper.assertTrue(MissionRules.rolls(mission, 70) == 5, "60 levels above: +3");

        OutcomeRules rules = OutcomeRules.FALLBACK;
        helper.assertTrue(MissionRules.exp(MissionRules.Outcome.SUCCESS, rules, 10, 1, 1) == 20, "Lv 1 board, Lv 1 mission: 10 × 2");
        helper.assertTrue(MissionRules.exp(MissionRules.Outcome.FAILED, rules, 10, 1, 1) == 8, "Failed: 10 × 0.8");
        helper.assertTrue(MissionRules.exp(MissionRules.Outcome.SUCCESS, rules, 10, 5, 100) == 10000, "Lv 5 board, Lv 100 mission: 5000 × 2");
        helper.assertTrue(MissionRules.exp(MissionRules.Outcome.FAILED, rules, 10, 3, 20) == 480, "600 × 0.8");
        helper.assertTrue(MissionRules.exp(MissionRules.Outcome.DEATH, rules, 10, 5, 100) == 0, "No EXP on death");

        helper.assertTrue(MissionRules.returnTime(3L, 2) == 4L * 24000L + 11500L, "Back in the evening (17:30) of the second day");
        helper.assertTrue(MissionRules.returnTime(3L, 1) == 3L * 24000L + 11500L, "A one-day mission returns the same evening");
        helper.succeed();
    }

    // ---------------------------------------------------------------- a settler on a mission

    /** A level-2 Mission Board with an Adventurer of skill {@value #SKILL}; the first offered mission is marked. */
    static Settler adventurer(GameTestHelper helper) {
        return missionSettler(helper, ModJobs.ADVENTURER.get(), "Adventurer");
    }

    /**
     * A settler holding a Mission Job on a level-2 board in a fresh settlement; the first offered mission the Job may take
     * is marked.
     */
    static Settler missionSettler(GameTestHelper helper, Job missionJob, String name) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(BOARD, ModBlocks.MISSION_BOARD.get());
        BlockPos board = helper.absolutePos(BOARD);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(board).orElseThrow();
        // Both the block entity and the settlement entry (the block entity syncs its level on load).
        helper.getBlockEntity(BOARD, JobBlockEntity.class).setJobLevel(BOARD_LEVEL);
        SettlementManager.setLevel(data, board, BOARD_LEVEL, SettlementManager.Listener.NONE);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 1, 4)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal(name));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getSkills().set(missionJob, new SettlerSkills.Skill(SKILL, 0));
        // A Mission Specialization is unlocked by its item in a Station Types slot.
        missionJob.parent().flatMap(base -> missionJob.unlockItem()).ifPresent(item -> TestAssignments.setStationType(data, board, 1, item));
        TestAssignments.assign(data, board, settler.getUUID(), name, settlement.id(), List.of(), missionJob);
        settler.addJob(new SettlerJob(missionJob, board));
        // Seeds from 1 until the daily offer holds a mission the Job may take (seed 1 for the Adventurer); each try is an
        // earlier day, as an offer is drawn once per day (the board refills on the real day and keeps the marked entry).
        List<MissionOffer.Entry> offer = List.of();
        int index = 0;
        for (long seed = 1L; seed <= 100L; seed++) {
            offer = MissionOffers.refresh(data, board, Math.floorDiv(level.getOverworldClockTime(), DAY) - seed + 1, Missions.all(level.registryAccess()),
                    RandomSource.create(seed));
            index = 0;
            while (index < offer.size() && !Missions.get(level.registryAccess(), offer.get(index).mission()).orElseThrow().jobs().contains(missionJob)) {
                index++;
            }
            if (index < offer.size()) {
                break;
            }
        }
        helper.assertTrue(index < offer.size(), "The offer holds a mission for " + missionJob + ": " + offer);
        helper.assertTrue(MissionOffers.setMarked(data, board, index, offer.get(index).mission(), true), "First mission for the Job marked");
        return settler;
    }

    static List<MissionOffer.Entry> offer(GameTestHelper helper) {
        BlockPos board = helper.absolutePos(BOARD);
        return SettlementData.get(helper.getLevel()).settlementAt(board).flatMap(s -> s.block(board)).orElseThrow().missionOffer().entries();
    }

    private static int count(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    /** All EXP gained in the Adventurer job so far (level-ups included). */
    private static int exp(Settler settler) {
        SettlerSkills.Skill skill = settler.getSkills().get(ModJobs.ADVENTURER.get());
        int total = skill.exp();
        for (int level = 1; level < skill.level(); level++) {
            total += SkillRules.get().expToNext(level);
        }
        return total;
    }

    /**
     * Checks the rewards of a return from a level-2 board: success = loot and 2 × 10 × 2 × mission level EXP, failure =
     * no loot and 0.8 × 10 × 2 × mission level EXP.
     */
    private static void assertRewards(GameTestHelper helper, Mission mission, int expBefore, int expAfter, int loot) {
        int success = 40 * mission.recommendedLevel();
        int failed = 16 * mission.recommendedLevel();
        int gained = expAfter - expBefore;
        helper.assertTrue(loot > 0 ? gained == success : gained == failed,
                "EXP " + gained + " with " + loot + " loot items (success " + success + ", failed " + failed + ")");
    }

    /**
     * The settler takes the marked mission (it leaves the offer), leaves in the morning by walking out visibly, then is
     * away (invisible, not pickable, AI skipped) and reappears where it vanished at the return time, with loot that it
     * delivers to the board inventory and EXP.
     */
    private static void fullCycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = adventurer(helper);
        settler.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        BlockPos board = helper.absolutePos(BOARD);
        Identifier marked = offer(helper).stream().filter(MissionOffer.Entry::marked).findFirst().orElseThrow().mission();
        int offered = offer(helper).size();
        Mission mission = Missions.get(level.registryAccess(), marked).orElseThrow();
        int[] expBefore = new int[1];
        boolean[] returned = new boolean[1];
        boolean[] walkedOut = new boolean[1];
        helper.onEachTick(() -> {
            Optional<MissionTrip> leaving = settler.getWork().missionTrip().filter(trip -> trip.departed() && !trip.gone());
            if (leaving.isPresent()) {
                helper.assertFalse(settler.isInvisible(), "Visible while walking out");
                walkedOut[0] = true;
            }
            if (!returned[0] && settler.getWork().isAway()) {
                MissionTrip trip = settler.getWork().missionTrip().orElseThrow();
                helper.assertTrue(trip.mission().equals(marked) && trip.board().equals(board), "Away on the marked mission: " + trip);
                helper.assertTrue(offer(helper).size() == offered - 1, "The taken mission left the offer: " + offer(helper));
                helper.assertTrue(settler.isInvisible() && !settler.isPickable(), "Away: invisible and not interactable");
                helper.assertTrue(settler.getWork().status(board) == JobStatus.ON_MISSION, "Status: " + settler.getWork().status(board));
                long clock = level.getOverworldClockTime();
                helper.assertTrue(trip.returnTime() == MissionRules.returnTime(Math.floorDiv(clock, DAY), mission.days()), "Return time");
                Vec3 before = settler.position();
                helper.assertFalse(settler.getWork().returnFromMission(level, trip.returnTime() - 1), "Not back before the return time");
                helper.assertTrue(settler.position().equals(before), "Still away");
                expBefore[0] = exp(settler);
                helper.assertTrue(settler.getWork().returnFromMission(level, trip.returnTime()), "Back at the return time");
                returned[0] = true;
                helper.assertFalse(settler.isInvisible() || settler.getWork().isAway(), "Visible again");
                helper.assertTrue(settler.blockPosition().equals(trip.exit().orElseThrow()), "Reappears where it vanished");
                assertRewards(helper, mission, expBefore[0], exp(settler), settler.getWork().buffer(board).count());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(walkedOut[0], "The settler should walk out before vanishing");
            helper.assertTrue(returned[0], "The settler should leave on the mission");
            Container container = (Container) level.getBlockEntity(board);
            JobBuffer buffer = settler.getWork().buffer(board);
            helper.assertTrue(buffer.isEmpty(), "The loot is delivered to the board inventory (" + buffer.count() + " left, "
                    + count(container) + " in the board)");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * While away, missed time costs no hunger; the return is rolled during catch-up once the return time has passed,
     * with the loot delivered straight into the board inventory.
     */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = adventurer(helper);
        settler.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        BlockPos board = helper.absolutePos(BOARD);
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || !settler.getWork().isAway()) {
                return;
            }
            done[0] = true;
            MissionTrip trip = settler.getWork().missionTrip().orElseThrow();
            Mission mission = Missions.get(level.registryAccess(), trip.mission()).orElseThrow();
            long clock = level.getOverworldClockTime();
            int food = settler.getFood().level();
            float saturation = settler.getFood().saturation();
            settler.getWork().setCatchUp(clock, trip.returnTime() - 1);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(settler.getWork().isAway(), "Still away before the return time");
            helper.assertTrue(settler.getFood().level() == food && settler.getFood().saturation() == saturation, "No hunger while away");

            Container container = (Container) level.getBlockEntity(board);
            int expBefore = exp(settler);
            settler.getWork().setCatchUp(trip.returnTime() - 1, trip.returnTime() + 1000);
            CatchUp.runNow(level, List.of(settler));
            helper.assertFalse(settler.getWork().isAway() || settler.isInvisible(), "Returned during catch-up");
            assertRewards(helper, mission, expBefore, exp(settler), count(container) + settler.getWork().buffer(board).count());
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** A settler loaded while walking out (departed, not vanished) goes on leaving and vanishes. */
    private static void resumeLeaving(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = adventurer(helper);
        long clock = level.getOverworldClockTime();
        MissionTrip leaving = new MissionTrip(helper.absolutePos(BOARD), CivMod.id("dungeon_raid"),
                MissionRules.returnTime(Math.floorDiv(clock, DAY), 1));
        settler.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                Optional.of(leaving)));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().isAway() && settler.isInvisible(), "The settler should go on leaving and vanish");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** An Adventurer with a bed and carried items (tool belt, pockets) that will not survive its mission. */
    private static Settler doomed(GameTestHelper helper) {
        Settler settler = adventurer(helper);
        settler.getWork().forceMissionOutcome(MissionRules.Outcome.DEATH);
        SettlementData data = SettlementData.get(helper.getLevel());
        data.assignBed(data.settlementAt(helper.absolutePos(BOARD)).orElseThrow(), helper.absolutePos(BED), settler.getUUID());
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.IRON_SWORD));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COBBLESTONE, 16));
        return settler;
    }

    /** Something in the Mission Job's buffer while away, which a death must not drop either. */
    private static void fillBuffer(Settler settler, MissionTrip trip) {
        settler.getWork().buffer(trip.board()).add(new ItemStack(Items.GOLD_INGOT, 5));
    }

    /** The settler is gone for good: removed, bed and Mission Board slot free, no member, its items not dropped. */
    private static void assertDead(GameTestHelper helper, Settler settler) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos board = helper.absolutePos(BOARD);
        Settlement settlement = data.settlementAt(board).orElseThrow();
        helper.assertTrue(settler.isRemoved(), "The settler does not come back");
        helper.assertFalse(JobAssignments.isAssignee(data, board, settler.getUUID()), "Mission Board slot freed");
        helper.assertTrue(settlement.bedOf(settler.getUUID()).isEmpty() && !settlement.beds().containsKey(helper.absolutePos(BED)), "Bed freed");
        helper.assertFalse(settlement.members().contains(settler.getUUID()), "Gone from the settlement");
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(BOARD)).inflate(32)).isEmpty(),
                "Carried items are lost, not dropped");
    }

    /** A forced death outcome at the return time removes the settler and frees its bed and jobs. */
    private static void death(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = doomed(helper);
        helper.onEachTick(() -> {
            if (settler.isRemoved() || !settler.getWork().isAway()) {
                return;
            }
            MissionTrip trip = settler.getWork().missionTrip().orElseThrow();
            fillBuffer(settler, trip);
            helper.assertTrue(settler.getWork().returnFromMission(level, trip.returnTime()), "The mission ends at the return time");
            assertDead(helper, settler);
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** A death resolved during catch-up removes the settler and frees its bed and jobs as well. */
    private static void deathCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = doomed(helper);
        helper.onEachTick(() -> {
            if (settler.isRemoved() || !settler.getWork().isAway()) {
                return;
            }
            MissionTrip trip = settler.getWork().missionTrip().orElseThrow();
            fillBuffer(settler, trip);
            settler.getWork().setCatchUp(level.getOverworldClockTime(), trip.returnTime() + 1000);
            CatchUp.runNow(level, List.of(settler));
            assertDead(helper, settler);
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /**
     * A settler back from a mission (17:30) has ended its work day, even if its own end of work that day is later; the
     * next day its own times apply again.
     */
    private static void returnEndsWorkDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = 5L;
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        for (long i = 1; settler.getWork().dayTimes(day).workEnd() == SettlerWork.WORK_END; i++) {
            // A settler whose own end of work on the return day is after 17:30.
            settler.setUUID(new UUID(0x3E7L, i));
        }
        long ownWorkEnd = settler.getWork().dayTimes(day).workEnd();
        MissionTrip trip = new MissionTrip(helper.absolutePos(BOARD), CivMod.id("dungeon_raid"), MissionRules.returnTime(day, 1))
                .withExit(helper.absolutePos(new BlockPos(1, 2, 1)));
        settler.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                Optional.of(trip)));
        helper.assertTrue(settler.getWork().dayTimes(day).workEnd() == ownWorkEnd, "Away: its own end of work " + ownWorkEnd);
        helper.assertTrue(settler.getWork().returnFromMission(level, trip.returnTime()), "Back at the return time");
        long returned = Math.floorMod(trip.returnTime(), DAY);
        helper.assertTrue(settler.getWork().dayTimes(day).workEnd() == returned,
                "Back at 17:30: its work day is over, end of work " + settler.getWork().dayTimes(day).workEnd());
        helper.assertTrue(settler.getWork().dayTimes(day + 1).equals(Schedule.dayTimes(settler.getUUID(), day + 1)),
                "The next day: its own times again");
        settler.discard();
        helper.succeed();
    }

    /** A reserved or current mission survives saving; a restored absent settler is away again. */
    private static void persistence(GameTestHelper helper) {
        MissionTrip trip = new MissionTrip(new BlockPos(1, 2, 3), CivMod.id("dungeon_raid"), 5 * DAY + SettlerWork.WORK_END)
                .withExit(new BlockPos(30, 2, 3));
        SettlerWork.BuffersSnapshot snapshot = new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                Optional.of(trip));
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlerWork.BuffersSnapshot loaded = SettlerWork.BuffersSnapshot.CODEC.parse(ops,
                SettlerWork.BuffersSnapshot.CODEC.encodeStart(ops, snapshot).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.trip().equals(Optional.of(trip)), "Mission saved: " + loaded.trip());
        Settler settler = ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
        settler.getWork().restore(loaded);
        helper.assertTrue(settler.getWork().isAway() && settler.isAway(), "Restored settler is away");
        MissionTrip reserved = new MissionTrip(new BlockPos(1, 2, 3), CivMod.id("dungeon_raid"), -1L);
        settler.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                Optional.of(reserved)));
        helper.assertFalse(settler.getWork().isAway(), "A reserved mission is not away");
        helper.assertTrue(settler.getWork().missionTrip().equals(Optional.of(reserved)), "Reservation restored");
        settler.discard();
        helper.succeed();
    }
}
