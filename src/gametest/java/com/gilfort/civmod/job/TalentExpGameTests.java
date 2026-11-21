package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.chronicle.DiaryEntry;
import com.gilfort.civmod.gametest.CatchUpGameTests;
import com.gilfort.civmod.gametest.LumberjackGameTests;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.origin.Gender;
import com.gilfort.civmod.origin.Place;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementRules;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the talent (vault {@code Systems/Settler Origin.md}, {@code Systems/JobSkill Level.md}): the EXP bonus in the Jobs
 * the upbringing favours, on both EXP funnels, live and caught up, and the confirmation at the first level-up. The class lives in
 * the job package because the funnels are package-private.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class TalentExpGameTests {

    private static final Identifier WOODCUTTERS = CivMod.id("woodcutters");
    private static final Identifier FARMERS = CivMod.id("farmers");
    private static final Identifier STONE_AND_SEA = CivMod.id("stone_and_sea");
    private static final Identifier PLAINS = CivMod.id("plains");
    private static final BlockPos NO_BLOCK = BlockPos.ZERO;
    private static final Vec3 SETTLER_AT = new Vec3(2.5, 1.0, 2.5);
    private static final long DAY = 24000L;
    private static final int CAP = SettlementRules.skillCap(SettlementRules.MIN_LEVEL);

    private TalentExpGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "talent_bonus_favoured_base_job", TalentExpGameTests::favouredBaseJob);
            register(helper, "talent_bonus_specialization", TalentExpGameTests::specialization);
            register(helper, "talent_bonus_second_base_job", TalentExpGameTests::secondBaseJob);
            register(helper, "talent_no_bonus_elsewhere", TalentExpGameTests::noBonusElsewhere);
            register(helper, "talent_bonus_value_from_rules", TalentExpGameTests::bonusValueFromRules);
            register(helper, "talent_bonus_resource_collection", TalentExpGameTests::resourceCollectionBonus);
            register(helper, "talent_no_bonus_resource_collection_elsewhere", TalentExpGameTests::resourceCollectionNoBonus);
            register(helper, "talent_confirmation_first_level_up", TalentExpGameTests::firstLevelUp);
            register(helper, "talent_confirmation_needs_a_level", TalentExpGameTests::needsALevel);
            register(helper, "talent_confirmation_other_job", TalentExpGameTests::otherJobDoesNotConfirm);
            register(helper, "talent_confirmation_resource_collection_live", TalentExpGameTests::resourceCollectionLive);
            register(helper, "talent_confirmation_catch_up", TalentExpGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    private static Settler handBuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 at = helper.absoluteVec(SETTLER_AT);
        settler.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        settler.setRace(Races.HUMAN);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        return settler;
    }

    private static SettlerOrigin origin(Identifier upbringing) {
        return new SettlerOrigin(Gender.MALE, Optional.empty(), Place.somewhere(PLAINS), Optional.of(upbringing), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static Settler withUpbringing(GameTestHelper helper, Identifier upbringing) {
        Settler settler = handBuilt(helper);
        settler.getPast().setOrigin(origin(upbringing));
        return settler;
    }

    /** One action of a Job through the funnel of every loop but the Resource Collection one. */
    private static void act(GameTestHelper helper, Settler settler, Job job, int amount) {
        settler.getWork().gainExp(helper.getLevel(), new SettlerJob(job, NO_BLOCK), amount, CAP);
    }

    private static SettlerSkills.Skill skill(Settler settler, Job job) {
        return settler.getSkills().get(job);
    }

    /** All EXP of a skill in hundredths of a point: the levels passed, the EXP towards the next and the fraction carried. */
    private static long hundredths(SettlerSkills.Skill skill) {
        long points = skill.exp();
        for (int level = 1; level < skill.level(); level++) {
            points += SkillRules.get().expToNext(level);
        }
        return points * 100 + skill.carry();
    }

    private static List<DiaryEntry> confirmations(Settler settler) {
        return settler.getPast().chronicle().entries().stream().filter(entry -> entry.type().equals(ModDiaryEntryTypes.TALENT.getId())).toList();
    }

    private static boolean talentBubble(Settler settler) {
        return settler.getWork().recentThoughts().stream().anyMatch(show -> show.bubble() == ThoughtBubble.TALENT);
    }

    // ---------------------------------------------------------------- bonus

    /** A base EXP of 1 still gains the bonus over several actions: the fraction is carried and nothing is lost. */
    private static void favouredBaseJob(GameTestHelper helper) {
        Settler settler = withUpbringing(helper, WOODCUTTERS);
        Job lumberjack = ModJobs.LUMBERJACK.get();
        act(helper, settler, lumberjack, 1);
        helper.assertTrue(skill(settler, lumberjack).exp() == 1 && skill(settler, lumberjack).carry() == 25,
                "One action gives 1.25: " + skill(settler, lumberjack));
        for (int i = 0; i < 3; i++) {
            act(helper, settler, lumberjack, 1);
        }
        helper.assertTrue(skill(settler, lumberjack).exp() == 5 && skill(settler, lumberjack).carry() == 0,
                "Four actions give exactly 5: " + skill(settler, lumberjack));
        act(helper, settler, lumberjack, 8);
        helper.assertTrue(hundredths(skill(settler, lumberjack)) == 5 * 100 + 8 * 125, "A bigger amount gains 25 % too: " + skill(settler, lumberjack));
        settler.discard();
        helper.succeed();
    }

    /** The Specializations of a favoured Base Job share its talent. */
    private static void specialization(GameTestHelper helper) {
        Settler settler = withUpbringing(helper, FARMERS);
        List<Job> favoured = List.of(ModJobs.GATHERER.get(), ModJobs.PLANTATION_FARMER.get(), ModJobs.VEGGY_FARMER.get());
        for (Job job : favoured) {
            for (int i = 0; i < 4; i++) {
                act(helper, settler, job, 1);
            }
            helper.assertTrue(skill(settler, job).exp() == 5, job + " gives 25 % more: " + skill(settler, job));
        }
        settler.discard();
        helper.succeed();
    }

    /** An upbringing with two Base Jobs favours both. */
    private static void secondBaseJob(GameTestHelper helper) {
        Settler settler = withUpbringing(helper, STONE_AND_SEA);
        for (Job job : List.of(ModJobs.MINER.get(), ModJobs.FISHERMAN.get())) {
            for (int i = 0; i < 4; i++) {
                act(helper, settler, job, 1);
            }
            helper.assertTrue(skill(settler, job).exp() == 5, job + " gives 25 % more: " + skill(settler, job));
        }
        settler.discard();
        helper.succeed();
    }

    /** No bonus in another Job, for a settler without an upbringing, and none as a penalty for a Job it does not favour. */
    private static void noBonusElsewhere(GameTestHelper helper) {
        Settler woodcutter = withUpbringing(helper, WOODCUTTERS);
        Settler withoutOrigin = handBuilt(helper);
        Job miner = ModJobs.MINER.get();
        Job lumberjack = ModJobs.LUMBERJACK.get();
        for (int i = 0; i < 4; i++) {
            act(helper, woodcutter, miner, 1);
            act(helper, withoutOrigin, lumberjack, 1);
        }
        helper.assertTrue(skill(woodcutter, miner).exp() == 4 && skill(woodcutter, miner).carry() == 0,
                "A Job the upbringing does not favour gives plain EXP: " + skill(woodcutter, miner));
        helper.assertTrue(skill(withoutOrigin, lumberjack).exp() == 4 && skill(withoutOrigin, lumberjack).carry() == 0,
                "A settler without an origin gets plain EXP: " + skill(withoutOrigin, lumberjack));
        // A Specialization of another Base Job is no favoured Job either.
        act(helper, woodcutter, ModJobs.PLANTATION_FARMER.get(), 4);
        helper.assertTrue(skill(woodcutter, ModJobs.PLANTATION_FARMER.get()).exp() == 4, "Plain EXP in a foreign Specialization");
        woodcutter.discard();
        withoutOrigin.discard();
        helper.succeed();
    }

    /** The value is a datapack value with a default of 25 %. */
    private static void bonusValueFromRules(GameTestHelper helper) {
        helper.assertTrue(SkillRules.get().talentBonusPercent() == 25, "Default: " + SkillRules.get());
        SkillRules merged = SkillRules.merge(List.of(JsonParser.parseString("{\"talent_bonus_percent\":50}")));
        helper.assertTrue(merged.talentBonusPercent() == 50 && merged.base() == SkillRules.FALLBACK.base(), "Merged: " + merged);
        SkillRules off = SkillRules.merge(List.of(JsonParser.parseString("{\"talent_bonus_percent\":0}")));
        helper.assertTrue(off.talentBonusPercent() == 0, "Zero switches the bonus off: " + off);
        boolean rejected = false;
        try {
            SkillRules.merge(List.of(JsonParser.parseString("{\"talent_bonus_percent\":-5}")));
        } catch (RuntimeException e) {
            rejected = true;
        }
        helper.assertTrue(rejected, "A negative bonus is rejected");
        helper.succeed();
    }

    // ---------------------------------------------------------------- Resource Collection path

    /** Runs a batch of Lumberjack rolls through the rules and returns what it made; the settler's skill moves by that. */
    private static WorkRules.Result roll(GameTestHelper helper, Settler settler, SettlerJob job) {
        ServerLevel level = helper.getLevel();
        JobBlockSite site = JobBlockSite.at(level, job.jobBlock()).orElseThrow();
        ResourceCollectionProfile profile = job.jobType().profile(ResourceCollectionProfile.class).orElseThrow();
        WorkRules.Output output = new WorkRules.Output() {
            @Override
            public boolean hasRoom() {
                return true;
            }

            @Override
            public boolean accept(List<ItemStack> stacks) {
                return true;
            }
        };
        return WorkRules.work(new WorkRules.Setup(level, settler, job, profile, site, settler.getWork().workplace(job.jobBlock()), WorkRules.ToolSupply.BELT),
                helper.absolutePos(new BlockPos(4, 1, 1)), 4000.0, SimulatedSpan.LIVE, output);
    }

    /**
     * Waits until the Lumberjack has scanned its trees, then calls the check once; the check finishes the test.
     */
    private static void whenScanned(GameTestHelper helper, Settler settler, List<long[]> forced, Consumer<SettlerJob> check) {
        ServerLevel level = helper.getLevel();
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            SettlerJob job = settler.getJobs().get(0);
            if (done[0] || settler.getWork().scan(job.jobBlock()).map(ScanResult::isEmpty).orElse(true)) {
                return;
            }
            done[0] = true;
            check.accept(job);
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    private static Settler lumberjack(GameTestHelper helper, List<long[]> forced, Optional<Identifier> upbringing) {
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        upbringing.ifPresent(id -> settler.getPast().setOrigin(origin(id)));
        return settler;
    }

    /** The direct EXP of a Resource Collection roll gets the bonus too, and the result reports the EXP that was granted. */
    private static void resourceCollectionBonus(GameTestHelper helper) {
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = lumberjack(helper, forced, Optional.of(WOODCUTTERS));
        whenScanned(helper, settler, forced, job -> {
            long before = hundredths(skill(settler, job.jobType()));
            WorkRules.Result result = roll(helper, settler, job);
            helper.assertTrue(result.rolls() >= 4, "Rolls to count: " + result);
            long after = hundredths(skill(settler, job.jobType()));
            long gained = after - before;
            helper.assertTrue(gained % 125 == 0, "The skill gains 25 % more than the plain EXP: " + gained / 100.0);
            long plain = gained / 125;
            helper.assertTrue(result.exp() > plain, "The result reports the bonus: " + result.exp() + " points granted for " + plain + " plain");
            helper.assertTrue(result.exp() == after / 100 - before / 100, "The result reports the whole points the skill gained: " + result.exp() + " of " + gained / 100.0);
        });
    }

    /** The same roll for a settler whose upbringing favours another Job gives plain EXP. */
    private static void resourceCollectionNoBonus(GameTestHelper helper) {
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = lumberjack(helper, forced, Optional.of(STONE_AND_SEA));
        whenScanned(helper, settler, forced, job -> {
            long before = hundredths(skill(settler, job.jobType()));
            WorkRules.Result result = roll(helper, settler, job);
            helper.assertTrue(result.rolls() >= 4, "Rolls to count: " + result);
            long gained = hundredths(skill(settler, job.jobType())) - before;
            helper.assertTrue(gained == result.exp() * 100L, "The result reports the plain EXP of " + gained / 100.0 + ": " + result.exp());
        });
    }

    // ---------------------------------------------------------------- confirmation

    /** The first level-up in a favoured Job writes the entry and shows the bubble, once. */
    private static void firstLevelUp(GameTestHelper helper) {
        Settler settler = withUpbringing(helper, WOODCUTTERS);
        Job lumberjack = ModJobs.LUMBERJACK.get();
        helper.assertFalse(settler.getPast().chronicle().talentConfirmed(), "Not confirmed at the start");
        act(helper, settler, lumberjack, 16);
        helper.assertTrue(skill(settler, lumberjack).level() == 2, "A level gained: " + skill(settler, lumberjack));
        helper.assertTrue(settler.getPast().chronicle().talentConfirmed(), "Confirmed by the level-up");
        helper.assertTrue(confirmations(settler).size() == 1 && confirmations(settler).get(0).params().equals(List.of(lumberjack.id().toString())),
                "One entry naming the Job: " + settler.getPast().chronicle().entries());
        helper.assertTrue(talentBubble(settler), "The thought bubble shows: " + settler.getWork().recentThoughts());
        ThoughtBubble.Show bubble = settler.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.TALENT).findFirst().orElseThrow();
        helper.assertTrue(bubble.icon() == lumberjack.bubbleIcon().getItem(), "The bubble shows the Job's icon: " + bubble.icon());
        int bubbles = settler.getWork().recentThoughts().size();
        act(helper, settler, lumberjack, 100);
        helper.assertTrue(skill(settler, lumberjack).level() > 2, "More levels gained");
        helper.assertTrue(confirmations(settler).size() == 1, "No second entry: " + settler.getPast().chronicle().entries());
        helper.assertTrue(settler.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.TALENT).count() == 1
                && settler.getWork().recentThoughts().size() >= bubbles, "No second bubble");
        settler.discard();
        helper.succeed();
    }

    /** EXP without a level-up confirms nothing. */
    private static void needsALevel(GameTestHelper helper) {
        Settler settler = withUpbringing(helper, WOODCUTTERS);
        act(helper, settler, ModJobs.LUMBERJACK.get(), 10);
        helper.assertTrue(skill(settler, ModJobs.LUMBERJACK.get()).level() == 1, "No level yet");
        helper.assertFalse(settler.getPast().chronicle().talentConfirmed() || talentBubble(settler) || !confirmations(settler).isEmpty(), "Nothing confirmed");
        settler.discard();
        helper.succeed();
    }

    /** A level-up in a Job the upbringing does not favour confirms nothing; a settler without an origin never confirms. */
    private static void otherJobDoesNotConfirm(GameTestHelper helper) {
        Settler woodcutter = withUpbringing(helper, WOODCUTTERS);
        Settler withoutOrigin = handBuilt(helper);
        act(helper, woodcutter, ModJobs.MINER.get(), 100);
        act(helper, withoutOrigin, ModJobs.LUMBERJACK.get(), 100);
        helper.assertTrue(skill(woodcutter, ModJobs.MINER.get()).level() > 1 && skill(withoutOrigin, ModJobs.LUMBERJACK.get()).level() > 1, "Levels gained");
        helper.assertFalse(woodcutter.getPast().chronicle().talentConfirmed() || talentBubble(woodcutter) || !confirmations(woodcutter).isEmpty(),
                "An unfavoured Job confirms nothing");
        helper.assertFalse(withoutOrigin.getPast().chronicle().talentConfirmed() || talentBubble(withoutOrigin) || !confirmations(withoutOrigin).isEmpty(),
                "A settler without an origin confirms nothing");
        woodcutter.discard();
        withoutOrigin.discard();
        helper.succeed();
    }

    /** A Lumberjack that works live shows the bubble at its first level-up and writes the entry. */
    private static void resourceCollectionLive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = lumberjack(helper, forced, Optional.of(WOODCUTTERS));
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(1, 19));
        helper.succeedWhen(() -> {
            helper.assertTrue(skill(settler, ModJobs.LUMBERJACK.get()).level() >= 2, "No level-up yet: " + skill(settler, ModJobs.LUMBERJACK.get())
                    + " status " + settler.getWork().status(settler.getJobs().get(0).jobBlock()) + " at " + settler.position() + " scan "
                    + settler.getWork().scan(settler.getJobs().get(0).jobBlock()));
            helper.assertTrue(talentBubble(settler), "No talent bubble: " + settler.getWork().recentThoughts());
            helper.assertTrue(confirmations(settler).size() == 1, "One diary entry: " + settler.getPast().chronicle().entries());
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A first level-up while the settler catches up writes the entry but shows no bubble. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = lumberjack(helper, forced, Optional.of(WOODCUTTERS));
        whenScanned(helper, settler, forced, job -> {
            settler.getSkills().set(job.jobType(), new SettlerSkills.Skill(1, 19));
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(skill(settler, job.jobType()).level() >= 2, "The day gave a level: " + skill(settler, job.jobType()));
            helper.assertTrue(settler.getPast().chronicle().talentConfirmed() && confirmations(settler).size() == 1,
                    "The entry is written: " + settler.getPast().chronicle().entries());
            helper.assertFalse(talentBubble(settler), "No bubble while catching up: " + settler.getWork().recentThoughts());
        });
    }
}
