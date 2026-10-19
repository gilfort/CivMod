package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the example addon ({@code example-addon/}, mod id {@code civmod_example}), which the dev runs load next
 * to CivMod: its Jobs run through their category like built-in ones.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ExampleAddonGameTests {

    private static final Identifier SCOUT = Identifier.fromNamespaceAndPath("civmod_example", "scout");
    private static final Identifier SCOUTING_TRIP = Identifier.fromNamespaceAndPath("civmod_example", "scouting_trip");

    private ExampleAddonGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> register(helper, "example_scout_mission", ExampleAddonGameTests::scoutMission));
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * The Scout is a Mission Specialization of the Adventurer with its own profile; it takes its datapack mission from a
     * level-2 board, leaves, returns with loot and delivers it to the board.
     */
    private static void scoutMission(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job scout = ModJobs.REGISTRY.getValue(SCOUT);
        helper.assertTrue(scout != null && scout.category() == JobCategory.MISSION && scout.parent().orElseThrow() == ModJobs.ADVENTURER.get(),
                "The Scout is a Mission Specialization of the Adventurer: " + scout);
        helper.assertTrue(scout.profile(MissionProfile.class).filter(profile -> profile != MissionProfile.NEUTRAL).isPresent(), "Own profile");
        helper.assertTrue(ModJobs.ADVENTURER.get().offeredAt(2).contains(scout) && !ModJobs.ADVENTURER.get().offeredAt(1).contains(scout),
                "Offered by the Mission Board from level 2");
        helper.assertTrue(scout.displayName().getString().equals("Scout"), "Lang key: " + scout.displayName().getString());
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = MissionRunGameTests.missionSettler(helper, scout, "Scout");
        settler.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        boolean[] returned = new boolean[1];
        helper.onEachTick(() -> {
            if (returned[0] || !settler.getWork().isAway()) {
                return;
            }
            MissionTrip trip = settler.getWork().missionTrip().orElseThrow();
            helper.assertTrue(trip.mission().equals(SCOUTING_TRIP), "Away on the addon's mission: " + trip);
            helper.assertTrue(settler.getWork().returnFromMission(level, trip.returnTime()), "Back at the return time");
            returned[0] = true;
            helper.assertTrue(settler.getWork().buffer(board).count() > 0, "Returned with loot");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(returned[0], "The Scout should leave on its mission");
            JobBuffer buffer = settler.getWork().buffer(board);
            Container container = (Container) level.getBlockEntity(board);
            helper.assertTrue(buffer.isEmpty() && !container.isEmpty(), "The loot is delivered to the board inventory");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
