package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.MissionVillagers;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Villager missions (vault {@code Systems/Mission Jobs.md}, {@code Jobs/Bard.md}): the mission data, the
 * nitwit chance, and a Bard that brings Villagers back live or during catch-up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionVillagerGameTests {

    private static final double EPSILON = 1.0E-9;
    private static final long DAY = 24000L;

    private MissionVillagerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_villager_data", MissionVillagerGameTests::data);
            register(helper, "mission_villager_nitwit_chance", MissionVillagerGameTests::nitwitChance);
            register(helper, "mission_villager_return", MissionVillagerGameTests::villagerReturn);
            register(helper, "mission_villager_evening_return", MissionVillagerGameTests::eveningReturn);
            register(helper, "mission_villager_catch_up", MissionVillagerGameTests::catchUp);
            register(helper, "mission_villager_failed", test -> nothingBack(test, MissionRules.Outcome.FAILED));
            register(helper, "mission_villager_death", test -> nothingBack(test, MissionRules.Outcome.DEATH));
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    private static DataResult<Mission> parse(GameTestHelper helper, String result) {
        String json = "{\"title\":\"Test\",\"days\":2,\"recommended_level\":5,\"jobs\":[\"civmod:bard\"]," + result + "}";
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return Mission.CODEC.parse(ops, JsonParser.parseString(json));
    }

    /**
     * A mission has either loot tables or a villagers block: both or neither is rejected with a message; a villagers block
     * takes the nitwit defaults.
     */
    private static void data(GameTestHelper helper) {
        String villagers = "\"villagers\":{\"count\":3,\"type\":\"minecraft:desert\"}";
        String loot = "\"loot_tables\":[\"minecraft:chests/simple_dungeon\"],\"rolls\":2";
        DataResult<Mission> valid = parse(helper, villagers);
        Mission mission = valid.getOrThrow();
        Mission.Villagers found = mission.villagers().orElseThrow();
        helper.assertTrue(found.count() == 3 && found.type().is(VillagerType.DESERT), "Count and type: " + found);
        helper.assertTrue(near(found.nitwitBase(), 0.25) && near(found.nitwitPerLevel(), 0.01) && near(found.nitwitMin(), 0.0)
                && near(found.nitwitMax(), 0.60), "Nitwit defaults: " + found);
        helper.assertTrue(mission.lootTables().isEmpty(), "No loot tables");
        helper.assertTrue(parse(helper, loot).isSuccess(), "A loot mission still loads");

        DataResult<Mission> both = parse(helper, villagers + "," + loot);
        helper.assertTrue(both.isError() && both.error().orElseThrow().message().contains("not both"), "Both rejected: " + both);
        DataResult<Mission> neither = parse(helper, "\"rolls\":1");
        helper.assertTrue(neither.isError() && neither.error().orElseThrow().message().contains("loot_tables or villagers"),
                "Neither rejected: " + neither);
        helper.assertTrue(parse(helper, "\"villagers\":{\"count\":0,\"type\":\"minecraft:plains\"}").isError(), "Count below 1 rejected");
        helper.assertTrue(parse(helper, "\"villagers\":{\"count\":1,\"type\":\"minecraft:moon\"}").isError(), "Unknown type rejected");
        helper.succeed();
    }

    /**
     * Nitwit chance per Villager: {@code clamp(base − perLevel · (skill − recommended), min, max)}, with the mission's own
     * values.
     */
    private static void nitwitChance(GameTestHelper helper) {
        Mission.Villagers defaults = Mission.Villagers.of(1, VillagerType.PLAINS);
        helper.assertTrue(near(MissionRules.nitwitChance(defaults, 40, 40), 0.25), "At the mission level: base");
        helper.assertTrue(near(MissionRules.nitwitChance(defaults, 40, 50), 0.15), "10 levels above: −0.10");
        helper.assertTrue(near(MissionRules.nitwitChance(defaults, 40, 70), 0.0), "30 levels above: clamped at the minimum");
        helper.assertTrue(near(MissionRules.nitwitChance(defaults, 40, 30), 0.35), "10 levels below: +0.10");
        helper.assertTrue(near(MissionRules.nitwitChance(defaults, 40, 1), 0.60), "39 levels below: clamped at the maximum");

        Mission.Villagers own = new Mission.Villagers(1, defaults.type(), 0.5, 0.02, 0.1, 0.9);
        helper.assertTrue(near(MissionRules.nitwitChance(own, 10, 30), 0.1), "Own values: 0.5 − 0.02 · 20");
        helper.assertTrue(near(MissionRules.nitwitChance(own, 10, 40), 0.1), "Own minimum");
        helper.assertTrue(near(MissionRules.nitwitChance(own, 10, 1), 0.68), "Own values below the level: 0.5 + 0.02 · 9");
        helper.assertTrue(near(MissionRules.nitwitChance(new Mission.Villagers(1, defaults.type(), 0.5, 0.1, 0.0, 0.7), 10, 1), 0.7),
                "Own maximum");
        helper.succeed();
    }

    // ---------------------------------------------------------------- a Bard on a Villager mission

    /** The Villagers in the test area. */
    private static List<Villager> villagers(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(Villager.class, new AABB(helper.absolutePos(MissionRunGameTests.BOARD)).inflate(64));
    }

    /** The mission the Bard is away on; it must be a Villager mission. */
    private static Mission awayMission(GameTestHelper helper, Settler bard) {
        MissionTrip trip = bard.getWork().missionTrip().orElseThrow();
        Mission mission = Missions.get(helper.getLevel().registryAccess(), trip.mission()).orElseThrow();
        helper.assertTrue(mission.villagers().isPresent(), "The Bard took a Villager mission: " + trip.mission());
        return mission;
    }

    /**
     * Every Villager is an adult of the mission's biome type, unemployed or a nitwit, and remembers the mission's level.
     */
    private static void assertArrived(GameTestHelper helper, List<Villager> arrived, Mission mission) {
        Mission.Villagers wanted = mission.villagers().orElseThrow();
        helper.assertTrue(arrived.size() == wanted.count(), "Count " + arrived.size() + " instead of " + wanted.count());
        for (Villager villager : arrived) {
            VillagerData data = villager.getVillagerData();
            helper.assertTrue(data.type().equals(wanted.type()), "Biome type " + data.type());
            helper.assertTrue(data.profession().is(VillagerProfession.NONE) || data.profession().is(VillagerProfession.NITWIT),
                    "Unemployed or nitwit: " + data.profession());
            helper.assertFalse(villager.isBaby(), "An adult");
            helper.assertTrue(MissionVillagers.missionLevel(villager).equals(OptionalInt.of(mission.recommendedLevel())),
                    "Mission level stored: " + MissionVillagers.missionLevel(villager));
        }
    }

    private static void cleanUp(GameTestHelper helper, Settler bard, List<long[]> forced) {
        villagers(helper).forEach(Villager::discard);
        bard.discard();
        forced.forEach(chunk -> helper.getLevel().setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    /**
     * A successful Bard reappears with the mission's Villagers where it vanished; they follow it to the Mission Board.
     */
    private static void villagerReturn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler bard = MissionRunGameTests.missionSettler(helper, ModJobs.BARD.get(), "Bard");
        bard.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        boolean[] returned = new boolean[1];
        helper.onEachTick(() -> {
            if (returned[0] || !bard.getWork().isAway()) {
                return;
            }
            Mission mission = awayMission(helper, bard);
            MissionTrip trip = bard.getWork().missionTrip().orElseThrow();
            helper.assertTrue(villagers(helper).isEmpty(), "No Villagers before the return");
            helper.assertTrue(bard.getWork().returnFromMission(level, trip.returnTime()), "Back at the return time");
            returned[0] = true;
            List<Villager> arrived = villagers(helper);
            assertArrived(helper, arrived, mission);
            BlockPos exit = trip.exit().orElseThrow();
            helper.assertTrue(arrived.stream().allMatch(villager -> villager.blockPosition().closerThan(exit, 4.0)),
                    "The Villagers appear with the Bard at " + exit);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(returned[0], "The Bard should return");
            List<Villager> arrived = villagers(helper);
            helper.assertTrue(!arrived.isEmpty() && arrived.stream().allMatch(villager -> villager.blockPosition().closerThan(board, 6.0)),
                    "The Villagers should follow the Bard to the board");
            cleanUp(helper, bard, forced);
        });
    }

    /**
     * The real return time is the end of work (the clock stands at 17:36): the Bard, back from its trip, still leads its
     * Villagers to the board before its evening.
     */
    private static void eveningReturn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(20, 0, 8));
        Settler bard = MissionRunGameTests.missionSettler(helper, ModJobs.BARD.get(), "Bard");
        bard.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        for (int x = 9; x <= 20; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        BlockPos exit = helper.absolutePos(new BlockPos(18, 1, 4));
        bard.snapTo(exit.getX() + 0.5, exit.getY(), exit.getZ() + 0.5, 0.0F, 0.0F);
        long day = Math.floorDiv(level.getOverworldClockTime(), DAY);
        MissionTrip trip = new MissionTrip(board, CivMod.id("invite_villagers_plains"), MissionRules.returnTime(day, 1))
                .withExit(exit);
        bard.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                Optional.of(trip)));
        boolean[] leading = new boolean[1];
        helper.onEachTick(() -> leading[0] |= bard.getWork().status(board) == JobStatus.BRINGING_VILLAGERS);
        helper.succeedWhen(() -> {
            helper.assertFalse(bard.getWork().isAway(), "The Bard should be back");
            helper.assertTrue(leading[0], "The Bard should lead its Villagers in the evening");
            List<Villager> arrived = villagers(helper);
            helper.assertTrue(arrived.size() == 1 && arrived.getFirst().blockPosition().closerThan(board, 6.0),
                    "The Villager should follow the Bard to the board: " + arrived.stream().map(Villager::blockPosition).toList());
            cleanUp(helper, bard, forced);
        });
    }

    /** A return caught up while the area was unloaded puts the Villagers at the Mission Board. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler bard = MissionRunGameTests.missionSettler(helper, ModJobs.BARD.get(), "Bard");
        bard.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || !bard.getWork().isAway()) {
                return;
            }
            done[0] = true;
            Mission mission = awayMission(helper, bard);
            MissionTrip trip = bard.getWork().missionTrip().orElseThrow();
            bard.getWork().setCatchUp(level.getOverworldClockTime(), trip.returnTime() + 1000);
            CatchUp.runNow(level, List.of(bard));
            helper.assertFalse(bard.getWork().isAway(), "Returned during catch-up");
            List<Villager> arrived = villagers(helper);
            assertArrived(helper, arrived, mission);
            helper.assertTrue(arrived.stream().allMatch(villager -> villager.blockPosition().closerThan(board, 3.0)),
                    "The Villagers appear at the board");
            cleanUp(helper, bard, forced);
            helper.succeed();
        });
    }

    /** A failed Bard comes back alone; a dead one does not come back, and no Villager does either. */
    private static void nothingBack(GameTestHelper helper, MissionRules.Outcome outcome) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler bard = MissionRunGameTests.missionSettler(helper, ModJobs.BARD.get(), "Bard");
        bard.getWork().forceMissionOutcome(outcome);
        helper.onEachTick(() -> {
            if (bard.isRemoved() || !bard.getWork().isAway()) {
                return;
            }
            awayMission(helper, bard);
            MissionTrip trip = bard.getWork().missionTrip().orElseThrow();
            helper.assertTrue(bard.getWork().returnFromMission(level, trip.returnTime()), "The mission ends at the return time");
            helper.assertTrue(bard.isRemoved() == (outcome == MissionRules.Outcome.DEATH), "Only a dead Bard stays away");
            helper.assertTrue(villagers(helper).isEmpty(), "No Villagers after " + outcome);
            cleanUp(helper, bard, forced);
            helper.succeed();
        });
    }
}
