package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Entertain;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.ServiceAct;
import com.gilfort.civmod.api.ServiceProfile;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.Stage;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.job.BardProfile;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.ServiceActs;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.menu.JobBlockTabs;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.PointResult;
import com.gilfort.civmod.settlement.JobPointsDisplay;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.StationTypes;
import com.gilfort.civmod.settlement.TestSettlements;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Stage and the Bard as a Service Job (spec "Service and Bard", story #210; vault
 * {@code Systems/Entertainment.md}, Bard, and {@code Jobs/Bard.md}): the numbers of the Bard, the Stage block, the stage act
 * with the Sound center, the Bard's hours and the flat catch-up point. The tests of the evening run in the environment
 * {@code civmod:evening} (17:36, the clock stands still).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StageGameTests {

    private static final Identifier STAGE_BLOCK = CivMod.id("stage_block");
    private static final Identifier BARD = CivMod.id("bard");
    private static final BlockPos STAGE = ServiceGameTests.JOB_BLOCK;
    /** A Stage of level 3: Bards can reach skill level 60, JobBlocks join settlers up to 30 blocks away. */
    private static final int STAGE_LEVEL = 3;
    private static final int BARD_SKILL = 10;

    private StageGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "stage_table", StageGameTests::table);
            register(helper, "stage_registry_and_block", StageGameTests::registryAndBlock);
            register(helper, "stage_act_block_center", StageGameTests::blockCenter);
            register(helper, "stage_act_sound_center", StageGameTests::soundCenter);
            register(helper, "stage_act_two_bards", StageGameTests::twoBards);
            register(helper, "stage_bard_hours", StageGameTests::hours);
            register(helper, "stage_sound_center_point", StageGameTests::soundCenterPoint);
            register(helper, "stage_catch_up", StageGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** A level-3 Stage at (1, 1, 1) with its Bard (skill 10) on a stone floor. */
    private static ServiceGameTests.Scene scene(GameTestHelper helper) {
        ServiceGameTests.Scene scene = ServiceGameTests.scene(helper, STAGE_BLOCK, BARD);
        raise(helper, STAGE);
        scene.performer().getSkills().set(scene.job(), new SettlerSkills.Skill(BARD_SKILL, 0));
        return scene;
    }

    /** Raises the JobBlock at a position to the Stage level, in the block entity and in the settlement. */
    private static void raise(GameTestHelper helper, BlockPos relative) {
        helper.getBlockEntity(relative, JobBlockEntity.class).setJobLevel(STAGE_LEVEL);
        SettlementManager.setLevel(SettlementData.get(helper.getLevel()), helper.absolutePos(relative), STAGE_LEVEL, SettlementManager.Listener.NONE);
    }

    private static boolean crafts(ServerLevel level, CraftingInput input) {
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level)
                .filter(recipe -> recipe.value().assemble(input).is(ModItems.STAGE_BLOCK.get())).isPresent();
    }

    // ---------------------------------------------------------------- tests

    /**
     * The pure numbers of the Bard: the windows and radii of its acts, the points with the skill multiplier, the caps per
     * kind of act, the EXP per Stage level and the end of its work.
     */
    private static void table(GameTestHelper helper) {
        Job bard = ModJobs.BARD.get();
        // The acts: the day round from 11:00 to 17:30, the stage act from 17:30 to 20:00, both 8 blocks around.
        List<ServiceAct> acts = new BardProfile().acts();
        helper.assertTrue(acts.size() == 2, "Two acts: " + acts);
        ServiceAct round = acts.get(0);
        ServiceAct stage = acts.get(1);
        helper.assertTrue(round.id().equals("round") && round.kind() == ServiceAct.Kind.ROUND && round.start() == 5000 && round.end() == 11500
                && round.radius() == 8.0, "The round: " + round);
        helper.assertTrue(stage.id().equals("stage") && stage.kind() == ServiceAct.Kind.STATIONARY && stage.start() == 11500 && stage.end() == 14000
                && stage.radius() == 8.0, "The stage act: " + stage);
        helper.assertTrue(round.effects().equals(List.of(new Entertain(1.0, 1.0, Entertain.Cap.ROUND_STOP)))
                && stage.effects().equals(List.of(new Entertain(1.0, 1.0, Entertain.Cap.STAGE_ACT))), "+1, 1 % per level, one cap per kind of act");

        // The points: 1 + 1 % per Bard level.
        Entertain entertain = new Entertain(1.0, 1.0, Entertain.Cap.STAGE_ACT);
        int[] skills = {0, 1, 10, 20, 50};
        double[] points = {1.0, 1.01, 1.1, 1.2, 1.5};
        for (int i = 0; i < skills.length; i++) {
            helper.assertTrue(ServiceGameTests.near(entertain.pointsFor(skills[i]), points[i]), "Points at skill " + skills[i] + ": " + entertain.pointsFor(skills[i]));
        }
        helper.assertTrue(ServiceGameTests.near(new Entertain(2.0).pointsFor(40), 2.0), "An Entertain without growth ignores the skill");
        helper.assertTrue(entertain.withNumbers(java.util.Map.of("points", 2.0, "percent_per_level", 0.5)).equals(new Entertain(2.0, 0.5, Entertain.Cap.STAGE_ACT)),
                "The datapack changes points and growth, not the cap");

        // The caps: one payment per day and kind of act, however many Bards reach the settler.
        Entertainment first = Entertainment.START.addCapped(1.1, Entertain.Cap.STAGE_ACT, 5);
        helper.assertTrue(ServiceGameTests.near(first.value(), 1.1) && first.heard(Entertain.Cap.STAGE_ACT, 5) && !first.heard(Entertain.Cap.STAGE_ACT, 6)
                && !first.heard(Entertain.Cap.ROUND_STOP, 5), "A stage act pays once and marks the day only for its kind");
        helper.assertTrue(first.addCapped(1.1, Entertain.Cap.STAGE_ACT, 5).equals(first), "A second Bard on the same evening adds nothing");
        Entertainment both = first.addCapped(1.1, Entertain.Cap.ROUND_STOP, 5);
        helper.assertTrue(ServiceGameTests.near(both.value(), 2.2) && both.addCapped(1.1, Entertain.Cap.ROUND_STOP, 5).equals(both),
                "A round stop pays on its own, once");
        helper.assertTrue(ServiceGameTests.near(both.addCapped(1.1, Entertain.Cap.STAGE_ACT, 6).value(), 3.3), "The next day pays again");
        helper.assertTrue(ServiceGameTests.near(Entertainment.START.addCapped(1.0, Entertain.Cap.NONE, 5).addCapped(1.0, Entertain.Cap.NONE, 5).value(), 2.0),
                "Without a cap every play pays");
        helper.assertTrue(Entertainment.of(9.5).addCapped(1.1, Entertain.Cap.STAGE_ACT, 5).value() == 10.0, "The value stays at 10");
        var encoded = Entertainment.CODEC.encodeStart(JsonOps.INSTANCE, both).getOrThrow();
        helper.assertTrue(Entertainment.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(both), "The marks survive a save");
        Entertainment old = Entertainment.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"value\": 3.0}")).getOrThrow();
        helper.assertTrue(!old.heard(Entertain.Cap.STAGE_ACT, 0) && !old.heard(Entertain.Cap.ROUND_STOP, 0), "A save without marks loads without migration");

        // A datapack file civmod_jobs/bard.json changes the numbers of the acts, not their caps or effects.
        JobBalancing file = JobBalancing.merge(List.<JsonElement>of(JsonParser.parseString(
                "{\"base_exp\": 12, \"service_acts\": {\"stage\": {\"radius\": 6.0, \"end\": 15000, \"effects\": [{\"points\": 2, \"percent_per_level\": 0.5}]}}}")));
        List<String> problems = new java.util.ArrayList<>();
        List<ServiceAct> changed = ServiceActs.resolve(acts, file.serviceActs().orElseThrow(), problems::add);
        helper.assertTrue(problems.isEmpty() && changed.get(0).equals(round) && changed.get(1).radius() == 6.0 && changed.get(1).end() == 15000
                && changed.get(1).effects().equals(List.of(new Entertain(2.0, 0.5, Entertain.Cap.STAGE_ACT))), "Datapack numbers: " + changed + " " + problems);
        helper.assertTrue(file.baseExp().orElse(0) == 12, "base_exp is a datapack value too");

        // EXP per act: the base 10 times the Stage level; the Stage level caps the skill.
        for (int level = 1; level <= 5; level++) {
            helper.assertTrue(bard.baseExp() * level == 10 * level, "EXP at Stage level " + level);
            helper.assertTrue(SettlementRules.skillCap(level) == 20 * level, "Skill cap at Stage level " + level);
        }
        // The Bard works until the stage act ends.
        helper.assertTrue(ServiceActs.windowEnd(bard).orElse(-1) == 14000, "Work ends with the stage act at 20:00");
        helper.assertTrue(ServiceActs.windowEnd(ModJobs.PRIEST.get()).isEmpty(), "A Service Job without a profile has no window");
        helper.succeed();
    }

    /**
     * The Bard is a Service Base Job without parent, unlock item and mission share; its Stage has one place on every level,
     * the Workers and Storage tabs and the recipe of the spec.
     */
    private static void registryAndBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job bard = ModJobs.BARD.get();
        helper.assertTrue(bard.category() == JobCategory.SERVICE && bard.parent().isEmpty() && bard.unlockItem().isEmpty(),
                "A Service Base Job without parent and unlock item");
        helper.assertTrue(bard.defaultBaseExp() == 10 && bard.defaultHungerFactor() == 0.5, "Base EXP 10, hunger factor 0.5");
        helper.assertTrue(bard.displayName().getString().equals("Bard"), "Name: " + bard.displayName().getString());
        helper.assertTrue(bard.profile(ServiceProfile.class).isPresent(), "It has a Service profile");
        for (int stageLevel = SettlementRules.MIN_LEVEL; stageLevel <= SettlementRules.MAX_LEVEL; stageLevel++) {
            helper.assertTrue(SettlementRules.capacity(bard, stageLevel) == 1, "One Bard per Stage at level " + stageLevel);
        }
        helper.assertTrue(ModBlocks.STAGE_BLOCK.get() instanceof JobBlock block && block.getJobType() == bard, "The Stage block offers the Bard");
        helper.assertTrue(JobBlockTabGameTests.keys(JobBlockTabs.of(bard)).equals(List.of("civmod.gui.job_block.tab.workers", "civmod.gui.job_block.tab.storage")),
                "Workers and Storage tabs only: " + JobBlockTabGameTests.keys(JobBlockTabs.of(bard)));
        helper.assertTrue(!StationTypes.hasStationTypes(bard), "No Specializations");

        // Not on the Mission Board any more: no slot, no unlock item, no share of the offer.
        Job adventurer = ModJobs.ADVENTURER.get();
        helper.assertTrue(!StationTypes.specializations(adventurer).contains(bard), "The Bard is no Specialization of the Adventurer");
        helper.assertTrue(StationTypes.unlockedBy(adventurer, Items.NOTE_BLOCK).isEmpty(), "A note block unlocks nothing on the board");
        helper.assertTrue(StationTypes.unlocked(new SettlementBlock(STAGE, adventurer, 5, true)).equals(List.of(adventurer)), "The board offers the Adventurer only");

        // The recipe: note block on top, barrel in the middle, planks around.
        ItemStack planks = new ItemStack(Items.OAK_PLANKS);
        CraftingInput stage = CraftingInput.of(3, 3, List.of(planks, new ItemStack(Items.NOTE_BLOCK), planks, planks, new ItemStack(Items.BARREL), planks,
                planks, planks, planks));
        helper.assertTrue(crafts(level, stage), "The Stage crafts from note block, barrel and planks");
        CraftingInput swapped = CraftingInput.of(3, 3, List.of(planks, new ItemStack(Items.BARREL), planks, planks, new ItemStack(Items.NOTE_BLOCK), planks,
                planks, planks, planks));
        helper.assertTrue(!crafts(level, swapped), "Barrel and note block do not swap");
        helper.succeed();
    }

    /**
     * The stage act with the Sound center at the Stage block: at 17:36 the Bard stands on its Stage and plays once; the
     * listeners within 8 blocks of the block get the points with the skill multiplier, the Bard gets the EXP of a level-3
     * Stage.
     */
    private static void blockCenter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper);
        Settler inside = ServiceGameTests.listener(helper, scene, 7.9, "Inside");
        Settler outside = ServiceGameTests.listener(helper, scene, 8.1, "Outside");
        Settler close = ServiceGameTests.listener(helper, scene, 3.0, "Close");
        Settler bard = scene.performer();
        helper.assertTrue(Tavern.isEvening(DayClock.dayTime(level.getOverworldClockTime())), "The test starts in the evening");
        helper.startSequence()
                .thenExecute(() -> bard.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.entertainment(close) > 0.0, "The stage act is played (status "
                        + bard.getWork().status(bard.getJobs().get(0).jobBlock()) + ", at " + bard.position() + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(inside), 1.1), "Just inside the radius: 1 + 10 % : "
                            + ServiceGameTests.entertainment(inside));
                    helper.assertTrue(ServiceGameTests.entertainment(outside) == 0.0, "Just outside hears nothing");
                    helper.assertTrue(ServiceGameTests.entertainment(bard) == 0.0, "The Bard does not hear itself");
                    Vec3 top = Vec3.atBottomCenterOf(helper.absolutePos(STAGE.above()));
                    helper.assertTrue(Math.abs(bard.getX() - top.x) < 0.7 && Math.abs(bard.getZ() - top.z) < 0.7 && bard.getY() > top.y - 0.2,
                            "The Bard stands on the Stage block: " + bard.position() + " instead of " + top);
                    helper.assertTrue(bard.getWork().status(bard.getJobs().get(0).jobBlock()) == JobStatus.PLAYING, "Playing");
                    helper.assertTrue(bard.getMainHandItem().is(Items.GOAT_HORN), "The horn in the hand while it plays");
                    helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 30, "EXP: 10 times Stage level 3: " + ServiceGameTests.exp(bard, scene.job()));
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(close), 1.1), "Once per evening: " + ServiceGameTests.entertainment(close));
                    helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 30, "EXP for the one act");
                    ServiceGameTests.cleanUp(level, scene, inside, outside, close);
                })
                .thenSucceed();
    }

    /**
     * A Sound center set with the Scroll moves the circle: those within 8 blocks of it hear the act, those next to the
     * Stage but further away from the spot do not.
     */
    private static void soundCenter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper);
        BlockPos spot = helper.absolutePos(new BlockPos(11, 1, 1));
        helper.assertTrue(JobAssignments.cycleSoundCenter(SettlementData.get(level), helper.absolutePos(STAGE), spot) == PointResult.SOUND_CENTER,
                "The Sound center is set");
        Settler atSpot = ServiceGameTests.listener(helper, scene, 10.0, "AtSpot");
        Settler inside = ServiceGameTests.listener(helper, scene, 3.1, "Inside");
        Settler outside = ServiceGameTests.listener(helper, scene, 1.0, "Outside");
        Settler atStage = ServiceGameTests.listener(helper, scene, -1.0, "AtStage");
        Settler bard = scene.performer();
        helper.startSequence()
                .thenExecute(() -> bard.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.entertainment(atSpot) > 0.0, "The stage act is played"))
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(inside), 1.1), "6.9 blocks from the spot hears it: "
                            + ServiceGameTests.entertainment(inside));
                    helper.assertTrue(ServiceGameTests.entertainment(outside) == 0.0, "9 blocks from the spot does not: " + outside.position() + " spot " + spot);
                    helper.assertTrue(ServiceGameTests.entertainment(atStage) == 0.0, "Next to the Stage, 11 blocks from the spot: nothing");
                    helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) == 30, "The Bard gets its EXP");
                    ServiceGameTests.cleanUp(level, scene, atSpot, inside, outside, atStage);
                })
                .thenSucceed();
    }

    /** Two Bards on two Stages reach one settler: it gets the points of the evening once. */
    private static void twoBards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper);
        BlockPos second = new BlockPos(1, 1, 5);
        TestSettlements.setBlock(helper, second, ModBlocks.STAGE_BLOCK.get());
        raise(helper, second);
        Settler other = ServiceGameTests.settler(helper, scene.settlement(), Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 5))), "Second", true);
        other.getSkills().set(scene.job(), new SettlerSkills.Skill(BARD_SKILL, 0));
        helper.assertTrue(SettlementWorld.assign(level, helper.absolutePos(second), other, scene.job()) == JobAssignments.AssignResult.OK, "A second Bard");
        Settler hearer = ServiceGameTests.settler(helper, scene.settlement(), Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 3))), "Hearer", true);
        Settler bard = scene.performer();
        helper.startSequence()
                .thenExecute(() -> {
                    bard.getWork().decideNow(level);
                    other.getWork().decideNow(level);
                })
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.exp(bard, scene.job()) > 0 && ServiceGameTests.exp(other, scene.job()) > 0,
                        "Both Bards play"))
                .thenExecute(() -> {
                    helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(hearer), 1.1),
                            "Both reach the settler, it gets the evening once: " + ServiceGameTests.entertainment(hearer));
                    ServiceGameTests.cleanUp(level, scene, other, hearer);
                })
                .thenSucceed();
    }

    /**
     * The Bard's hours: it does not work its Stage before 11:00, walks its round by day (and waits on the Stage when nobody
     * works), stands on the Stage from 17:30 and goes back to its next job at 20:00. Its work ends at 20:00 without waking early.
     */
    private static void hours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper);
        Settler other = ServiceGameTests.listener(helper, scene, 3.0, "Other");
        Settler bard = scene.performer();
        long day = DayClock.day(level);
        helper.assertTrue(bard.getWork().dayTimes(day).workEnd() == ServiceAct.clock(20, 0), "Work ends at 20:00: " + bard.getWork().dayTimes(day));
        helper.assertTrue(bard.getWork().dayTimes(day).wake() >= 0L, "No early wake like a counter job: " + bard.getWork().dayTimes(day));
        helper.assertTrue(other.getWork().dayTimes(day).workEnd() < ServiceAct.clock(20, 0), "Another settler stops at 17:30 to 18:00");
        long[] times = {ServiceAct.clock(8, 0), ServiceAct.clock(10, 59), ServiceAct.clock(12, 0), ServiceAct.clock(17, 36), ServiceAct.clock(19, 59), ServiceAct.clock(20, 0)};
        boolean[] plays = {false, false, true, true, true, false};
        for (int i = 0; i < times.length; i++) {
            ServiceGameTests.setDayTime(level, times[i]);
            bard.getWork().decideNow(level);
            helper.assertTrue(ServiceGameTests.works(bard, scene.job()) == plays[i], "At day time " + times[i] + " the Bard "
                    + (plays[i] ? "works" : "does not work") + " its Stage: " + bard.getWork().activeJob());
        }
        ServiceGameTests.cleanUp(level, scene, other);
        helper.succeed();
    }

    /**
     * The Sound center point: set inside the settlement and within 24 blocks of the Stage, one per Stage, a second click on it
     * or a click on the Stage removes it, a broken block takes it with it; it is saved and shown.
     */
    private static void soundCenterPoint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stage = helper.absolutePos(STAGE);
        TestSettlements.setBlock(helper, STAGE, ModBlocks.STAGE_BLOCK.get());
        raise(helper, STAGE);
        SettlementData data = SettlementData.get(level);
        SettlementBlock block = data.settlementAt(stage).flatMap(settlement -> settlement.block(stage)).orElseThrow();
        helper.assertTrue(Stage.isStage(block) && block.soundCenter().isEmpty(), "A Stage starts without a Sound center");
        helper.assertTrue(ServiceGameTests.near(Stage.soundCenter(block).distanceTo(Vec3.atBottomCenterOf(stage)), 0.0), "The Stage block is the default center");

        BlockPos first = stage.offset(10, 0, 0);
        BlockPos second = stage.offset(0, 0, -6);
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, first) == PointResult.SOUND_CENTER && block.soundCenter().equals(Optional.of(first)), "Set");
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, second) == PointResult.SOUND_CENTER && block.soundCenter().equals(Optional.of(second)),
                "A Stage has one: the new spot replaces the old one");
        helper.assertTrue(Stage.soundCenter(block).distanceTo(Vec3.atBottomCenterOf(second)) == 0.0, "The spot is the center of the stage act");
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, second) == PointResult.REMOVED && block.soundCenter().isEmpty(), "A second click removes it");
        JobAssignments.cycleSoundCenter(data, stage, first);
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, stage) == PointResult.REMOVED && block.soundCenter().isEmpty(), "A click on the Stage resets it");

        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, stage.offset(24, 0, 0)) == PointResult.SOUND_CENTER, "24 blocks away is allowed");
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, stage.offset(25, 0, 0)) == PointResult.TOO_FAR && block.soundCenter().equals(Optional.of(stage.offset(24, 0, 0))),
                "25 blocks away is too far and changes nothing");
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, stage.offset(0, 24, 0)) == PointResult.SOUND_CENTER, "The distance counts in 3D, 24 up is allowed");
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, stage.offset(0, 25, 0)) == PointResult.TOO_FAR, "25 up is too far");
        helper.assertTrue(JobAssignments.cycleSoundCenter(data, stage, stage.offset(31, 0, 0)) == PointResult.OUTSIDE, "Outside the settlement (join radius 30)");

        BlockPos kept = stage.offset(5, 0, 5);
        JobAssignments.cycleSoundCenter(data, stage, kept);
        var context = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementBlock loaded = SettlementBlock.CODEC.parse(context, SettlementBlock.CODEC.encodeStart(context, block).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.soundCenter().equals(Optional.of(kept)), "The Sound center survives a save: " + loaded.soundCenter());
        JobAssignments.cycleSoundCenter(data, stage, kept);
        SettlementBlock without = SettlementBlock.CODEC.parse(context, SettlementBlock.CODEC.encodeStart(context, block).getOrThrow()).getOrThrow();
        helper.assertTrue(without.soundCenter().isEmpty(), "Without one it loads without one (old saves)");

        var points = new JobPointsDisplay.JobPoints(stage, List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(), List.of(), Optional.of(kept));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        JobPointsDisplay.JobPoints.STREAM_CODEC.encode(buffer, points);
        helper.assertTrue(JobPointsDisplay.JobPoints.STREAM_CODEC.decode(buffer).equals(points), "The display sends the Sound center");

        JobAssignments.cycleSoundCenter(data, stage, kept);
        helper.assertTrue(JobAssignments.removePointsAt(data, kept) && block.soundCenter().isEmpty(), "A broken block takes the Sound center with it");
        helper.succeed();
    }

    /**
     * Catch-up gives each settler a flat +1 for a missed day while a Bard is assigned to a connected Stage, no multiplier and
     * once per day; without a Bard it gives nothing.
     */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = scene(helper);
        Settler hearer = ServiceGameTests.listener(helper, scene, 3.0, "Hearer");
        Settler later = ServiceGameTests.listener(helper, scene, 4.0, "Later");
        long missed = DayClock.day(level) - 1;
        TavernGameTests.catchUpYesterday(helper, hearer);
        helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(hearer), 1.0) && hearer.getWork().entertainment().heard(Entertain.Cap.STAGE_ACT, missed),
                "A flat +1 for the missed day, whatever the Bard's level: " + ServiceGameTests.entertainment(hearer));
        TavernGameTests.catchUpYesterday(helper, hearer);
        helper.assertTrue(ServiceGameTests.near(ServiceGameTests.entertainment(hearer), 1.0), "The same day is not paid twice");

        SettlementWorld.unassign(level, helper.absolutePos(STAGE));
        TavernGameTests.catchUpYesterday(helper, later);
        helper.assertTrue(ServiceGameTests.entertainment(later) == 0.0, "No Bard assigned: nothing");
        ServiceGameTests.cleanUp(level, scene, hearer, later);
        helper.succeed();
    }
}
