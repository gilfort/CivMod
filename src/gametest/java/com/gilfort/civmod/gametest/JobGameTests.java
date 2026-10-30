package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.block.JobBlockSettlers;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the job registry and per-job datapack overrides.
 * <p>
 * Registers {@code civmod:gametest_dummy}, a job without work loop whose balancing values the test datapack overrides
 * ({@code civmod_jobs/gametest_dummy.json}, {@code civmod_job_blocks/gametest_dummy.json}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobGameTests {

    private static final double DUMMY_HUNGER_FACTOR = 1.0;
    private static final int DUMMY_BASE_EXP = 1;

    private JobGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(ModJobs.KEY, helper -> helper.register(CivMod.id("gametest_dummy"), Job.builder(JobCategory.SERVICE)
                .icon(() -> Items.STICK).hungerFactor(DUMMY_HUNGER_FACTOR).baseExp(DUMMY_BASE_EXP).build()));
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "job_registry", JobGameTests::registry);
            register(helper, "job_balancing_merge", JobGameTests::balancingMerge);
            register(helper, "job_balancing_datapack_override", JobGameTests::datapackOverride);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The PoC jobs are namespaced registry entries with category, icon and defaults; ids round-trip, legacy ids map to civmod. */
    private static void registry(GameTestHelper helper) {
        Job lumberjack = ModJobs.LUMBERJACK.get();
        Job miner = ModJobs.MINER.get();
        Job fisherman = ModJobs.FISHERMAN.get();
        helper.assertTrue(lumberjack.id().equals(CivMod.id("lumberjack")) && ModJobs.REGISTRY.getValue(CivMod.id("miner")) == miner,
                "Namespaced ids");
        for (Job job : List.of(lumberjack, miner, fisherman)) {
            helper.assertTrue(job.category() == JobCategory.RESOURCE_COLLECTION && job.parent().isEmpty() && job.profile(ResourceCollectionProfile.class).isPresent(),
                    job + " is a Resource Collection Base Job");
        }
        helper.assertTrue(lumberjack.icon().is(ModItems.LUMBERJACK_BLOCK.get()) && fisherman.icon().is(ModItems.FISHERMAN_BLOCK.get()),
                "Icon is the JobBlock");
        helper.assertTrue(fisherman.defaultHungerFactor() == 0.5 && fisherman.defaultBaseExp() == 5 && miner.defaultHungerFactor() == 1.0
                && miner.defaultBaseExp() == 1, "Default balancing values");
        helper.assertTrue(lumberjack.displayName().getString().equals("Lumberjack"), "Display name: " + lumberjack.displayName().getString());

        helper.assertTrue(Job.CODEC.encodeStart(JsonOps.INSTANCE, miner).getOrThrow().getAsString().equals("civmod:miner"), "Saved as namespaced id");
        helper.assertTrue(Job.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("\"civmod:fisherman\"")).getOrThrow() == fisherman,
                "Namespaced id is read");
        helper.assertTrue(Job.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("\"lumberjack\"")).getOrThrow() == lumberjack,
                "Legacy id maps to civmod");
        helper.assertTrue(Job.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("\"civmod:unknown\"")).isError(), "Unknown job is an error");
        helper.succeed();
    }

    /** Later files override earlier ones per value; unset values stay unset. */
    private static void balancingMerge(GameTestHelper helper) {
        JobBalancing merged = JobBalancing.merge(List.of(JsonParser.parseString("{\"hunger_factor\":2.0,\"base_exp\":3}"),
                JsonParser.parseString("{\"base_exp\":4}")));
        helper.assertTrue(merged.hungerFactor().orElseThrow() == 2.0 && merged.baseExp().orElseThrow() == 4, "Merged: " + merged);
        helper.assertTrue(JobBalancing.merge(List.of(JsonParser.parseString("{}"))).equals(JobBalancing.NONE), "Empty file overrides nothing");
        helper.succeed();
    }

    /** The test datapack overrides hunger factor, base EXP and JobBlock settlers of the dummy job; built-in jobs keep their defaults. */
    private static void datapackOverride(GameTestHelper helper) {
        Job dummy = ModJobs.REGISTRY.getValue(CivMod.id("gametest_dummy"));
        helper.assertTrue(dummy != null, "Dummy job registered");
        helper.assertTrue(dummy.hungerFactor() == 2.5 && dummy.defaultHungerFactor() == DUMMY_HUNGER_FACTOR, "Hunger factor overridden: " + dummy.hungerFactor());
        helper.assertTrue(dummy.baseExp() == 7 && dummy.defaultBaseExp() == DUMMY_BASE_EXP, "Base EXP overridden: " + dummy.baseExp());
        helper.assertTrue(JobBlockSettlers.settlers(dummy, 1) == 2, "Settlers by job id, the leftover cost entry ignored");
        Job fisherman = ModJobs.FISHERMAN.get();
        helper.assertTrue(fisherman.hungerFactor() == 0.5 && fisherman.baseExp() == 5, "Built-in jobs keep their defaults");
        helper.succeed();
    }
}
