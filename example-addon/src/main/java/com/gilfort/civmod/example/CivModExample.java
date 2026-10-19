package com.gilfort.civmod.example;

import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.CivModJobs;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;

import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Example addon for CivMod's addon API: every Job here uses {@code com.gilfort.civmod.api} only (the build fails on any
 * other CivMod class).
 * <p>
 * The Scout is a Mission Job. Like every Mission Job it has no JobBlock: it is a Specialization of the Adventurer, so the
 * Mission Board offers it from level 2. Its mission {@code civmod_example:scouting_trip} names it in the datapack
 * ({@code data/civmod_example/civmod/mission/scouting_trip.json}); its names are the lang keys
 * {@code job.civmod_example.scout} and {@code job.civmod_example.scout.indefinite}.
 */
@Mod(CivModExample.MOD_ID)
public final class CivModExample {

    /** The mod id. */
    public static final String MOD_ID = "civmod_example";

    /** Jobs in the {@code civmod_example} namespace. */
    public static final DeferredRegister<Job> JOBS = CivModApi.jobs(MOD_ID);

    /** A careful Mission Job: half the death chance of the outcome formula. */
    public static final DeferredHolder<Job, Job> SCOUT = JOBS.register("scout", () -> Job.builder(JobCategory.MISSION)
            .parent(CivModJobs.ADVENTURER).icon(() -> Items.SPYGLASS).hungerFactor(1.0).baseExp(8).profile(ScoutProfile::new).build());

    /**
     * Registers the addon's content.
     *
     * @param modEventBus the mod event bus
     */
    public CivModExample(IEventBus modEventBus) {
        JOBS.register(modEventBus);
    }

    /** The Scout's mission specifics: it avoids danger, so it dies half as often. */
    private static final class ScoutProfile implements MissionProfile {

        @Override
        public double deathChance(double chance, int recommendedLevel, int skill) {
            return chance / 2.0;
        }
    }
}
