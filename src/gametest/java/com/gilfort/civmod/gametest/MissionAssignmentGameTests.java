package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for manual mission assignment (vault {@code Systems/Mission Jobs.md}): an assigned mission is locked for every
 * other settler, the assigned settler takes it first (also above its skill, several in random order), and the lock
 * stays when the settler dies or leaves the board.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionAssignmentGameTests {

    private static final UUID ALICE = new UUID(0L, 1L);
    private static final UUID BOB = new UUID(0L, 2L);

    private MissionAssignmentGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_assignment_pick", MissionAssignmentGameTests::pick);
            register(helper, "mission_assignment_lock_and_retention", MissionAssignmentGameTests::lockAndRetention);
            register(helper, "mission_assignment_give_back", MissionAssignmentGameTests::giveBack);
            register(helper, "mission_assignment_persistence", MissionAssignmentGameTests::persistence);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static MissionOffer.Entry assigned(String mission, UUID settler) {
        return new MissionOffer.Entry(CivMod.id(mission), true, Optional.of(settler), true);
    }

    /**
     * The pick rule with assignments: a locked entry is never picked by another settler, also after its settler is gone;
     * the assigned settler takes its missions first, also above its skill, several in random order.
     */
    private static void pick(GameTestHelper helper) {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(CivMod.id("lv5"), MissionRunGameTests.mission(5, 1));
        pool.put(CivMod.id("lv10"), MissionRunGameTests.mission(10, 1));
        pool.put(CivMod.id("lv50"), MissionRunGameTests.mission(50, 1));
        pool.put(CivMod.id("lv60"), MissionRunGameTests.mission(60, 1));
        RandomSource random = RandomSource.create(5L);
        List<MissionOffer.Entry> offer = List.of(new MissionOffer.Entry(CivMod.id("lv5"), true), assigned("lv10", ALICE),
                assigned("lv50", ALICE), assigned("lv60", ALICE),
                new MissionOffer.Entry(CivMod.id("lv10"), true, Optional.empty(), true));

        OptionalInt bob = MissionRules.pick(offer, pool, ModJobs.ADVENTURER.get(), 20, BOB, random);
        helper.assertTrue(bob.equals(OptionalInt.of(0)), "Bob skips Alice's and the unowned locked missions: " + bob);
        List<MissionOffer.Entry> onlyLocked = offer.subList(1, offer.size());
        helper.assertTrue(MissionRules.pick(onlyLocked, pool, ModJobs.ADVENTURER.get(), 100, BOB, random).isEmpty(),
                "Locked missions are never picked by others");

        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            OptionalInt alice = MissionRules.pick(offer, pool, ModJobs.ADVENTURER.get(), 1, ALICE, random);
            helper.assertTrue(alice.isPresent() && alice.getAsInt() >= 1 && alice.getAsInt() <= 3,
                    "Alice takes one of her assigned missions first, also above her skill: " + alice);
            picked.add(alice.getAsInt());
        }
        helper.assertTrue(picked.equals(Set.of(1, 2, 3)), "Assigned missions are taken in random order: " + picked);

        List<MissionOffer.Entry> unmarked = List.of(new MissionOffer.Entry(CivMod.id("lv10"), false, Optional.of(ALICE), true));
        helper.assertTrue(MissionRules.pick(unmarked, pool, ModJobs.ADVENTURER.get(), 100, ALICE, random).isEmpty(),
                "Only marked missions are taken");
        helper.assertTrue(MissionRules.pick(offer, pool, ModJobs.MINER.get(), 100, ALICE, random).isEmpty(),
                "An assigned mission the settler's Mission Job may not take is not taken");
        helper.succeed();
    }

    /** A second Adventurer on the board of {@link MissionRunGameTests#adventurer}. */
    private static Settler join(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        Settlement settlement = data.settlementAt(board).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 1, 5)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal(name));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        helper.assertTrue(TestAssignments.assign(data, board, settler.getUUID(), name, settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                name + " joins the board");
        settler.addJob(new SettlerJob(ModJobs.ADVENTURER.get(), board));
        return settler;
    }

    private static MissionOffer.Entry entry(GameTestHelper helper, int index) {
        return MissionRunGameTests.offer(helper).get(index);
    }

    /**
     * Assigning needs a marked entry, the mission the player saw, a settler of the board and a Mission Job the mission
     * allows. An assigned mission can be reassigned and unlocked; when its settler leaves the board or dies the
     * assignment is removed but the lock stays. Unmarking removes both.
     */
    private static void lockAndRetention(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        Map<Identifier, Mission> pool = Missions.all(level.registryAccess());
        Settler alice = MissionRunGameTests.adventurer(helper);
        Settler bob = join(helper, "Bob");
        Identifier marked = entry(helper, 0).mission();
        Identifier unmarked = entry(helper, 1).mission();
        Job adventurer = ModJobs.ADVENTURER.get();

        helper.assertFalse(MissionOffers.assign(data, board, 1, unmarked, alice.getUUID(), adventurer, pool), "An unmarked mission cannot be assigned");
        helper.assertFalse(MissionOffers.assign(data, board, 0, CivMod.id("stale"), alice.getUUID(), adventurer, pool),
                "A stale mission id changes nothing");
        helper.assertFalse(MissionOffers.assign(data, board, 0, marked, UUID.randomUUID(), adventurer, pool), "Only settlers of the board");
        helper.assertFalse(MissionOffers.assign(data, board, 0, marked, alice.getUUID(), ModJobs.MINER.get(), pool),
                "Only settlers whose Mission Job the mission allows");
        helper.assertTrue(entry(helper, 0).assignee().isEmpty() && !entry(helper, 0).locked(), "Nothing assigned yet: " + entry(helper, 0));

        helper.assertTrue(MissionOffers.assign(data, board, 0, marked, alice.getUUID(), adventurer, pool), "Assigned to Alice");
        helper.assertTrue(entry(helper, 0).assignee().equals(Optional.of(alice.getUUID())) && entry(helper, 0).locked(), "Locked: " + entry(helper, 0));
        helper.assertTrue(MissionOffers.assign(data, board, 0, marked, bob.getUUID(), adventurer, pool), "Reassigned to Bob");
        helper.assertTrue(entry(helper, 0).assignee().equals(Optional.of(bob.getUUID())), "Assigned to Bob now: " + entry(helper, 0));

        helper.assertTrue(TestAssignments.unassign(data, board, bob.getUUID()), "Bob leaves the board");
        helper.assertTrue(entry(helper, 0).assignee().isEmpty() && entry(helper, 0).locked() && entry(helper, 0).marked(),
                "Assignment removed, the mission stays marked and locked: " + entry(helper, 0));
        helper.assertTrue(MissionOffers.unlock(data, board, 0, marked), "The player unlocks it");
        helper.assertFalse(entry(helper, 0).locked(), "Unlocked: " + entry(helper, 0));

        helper.assertTrue(MissionOffers.assign(data, board, 0, marked, alice.getUUID(), adventurer, pool), "Assigned to Alice again");
        alice.perish(level);
        helper.assertTrue(entry(helper, 0).assignee().isEmpty() && entry(helper, 0).locked() && entry(helper, 0).marked(),
                "Alice died: assignment removed, still marked and locked: " + entry(helper, 0));
        helper.assertTrue(MissionOffers.setMarked(data, board, 0, marked, false), "Unmarked");
        helper.assertFalse(entry(helper, 0).locked() || entry(helper, 0).marked(), "An unmarked mission is not locked: " + entry(helper, 0));
        bob.discard();
        helper.succeed();
    }

    /**
     * A settler takes its assigned mission first, also far above its skill; a reserved assigned mission that goes back
     * to the board because the settler left it stays locked.
     */
    private static void giveBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        SettlementData data = SettlementData.get(level);
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        Settler settler = MissionRunGameTests.adventurer(helper);
        settler.getSkills().set(ModJobs.ADVENTURER.get(), new SettlerSkills.Skill(1, 0));
        Identifier mineshaft = CivMod.id("abandoned_mineshaft");
        MissionOffers.giveBack(data, board, mineshaft, Optional.of(settler.getUUID()), true);
        helper.succeedWhen(() -> {
            Optional<MissionTrip> trip = settler.getWork().missionTrip();
            helper.assertTrue(trip.filter(t -> t.mission().equals(mineshaft) && t.assigned()).isPresent(),
                    "Skill 1 takes its assigned Lv 20 mission first: " + trip);
            settler.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                    Optional.of(new MissionTrip(board, mineshaft, -1L, Optional.empty(), true))));
            SettlementWorld.unassign(level, board);
            Optional<MissionOffer.Entry> back = MissionRunGameTests.offer(helper).stream()
                    .filter(entry -> entry.mission().equals(mineshaft)).findFirst();
            helper.assertTrue(back.filter(entry -> entry.marked() && entry.locked() && entry.assignee().isEmpty()).isPresent(),
                    "Back on the board, marked and still locked: " + MissionRunGameTests.offer(helper));
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    private static List<MissionOffer.Entry> roundTrip(GameTestHelper helper, SettlementData data, BlockPos board) {
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        return loaded.settlementAt(board).flatMap(s -> s.block(board)).orElseThrow().missionOffer().entries();
    }

    /** Assignments, locks and an assigned trip survive a save / load round trip. */
    private static void persistence(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos board = new BlockPos(0, 64, 0);
        Settlement settlement = SettlementManager.place(data, board, ModJobs.ADVENTURER.get(), 2, 0L, RandomSource.create(0L),
                SettlementManager.Listener.NONE).settlement();
        Job adventurer = ModJobs.ADVENTURER.get();
        TestAssignments.assign(data, board, ALICE, "Alice", settlement.id(), List.of());
        TestAssignments.assign(data, board, BOB, "Bob", settlement.id(), List.of());
        // Only the Adventurer's missions: the example addon's Scout mission would take a place in the offer.
        Map<Identifier, Mission> pool = Missions.all(helper.getLevel().registryAccess()).entrySet().stream()
                .filter(entry -> entry.getValue().jobs().contains(ModJobs.ADVENTURER.get())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<MissionOffer.Entry> offer = MissionOffers.refresh(data, board, 3L, pool, RandomSource.create(3L));
        MissionOffers.setMarked(data, board, 0, offer.get(0).mission(), true);
        MissionOffers.setMarked(data, board, 1, offer.get(1).mission(), true);
        MissionOffers.assign(data, board, 0, offer.get(0).mission(), ALICE, adventurer, pool);
        MissionOffers.assign(data, board, 1, offer.get(1).mission(), BOB, adventurer, pool);
        TestAssignments.unassign(data, board, ALICE);

        List<MissionOffer.Entry> restored = roundTrip(helper, data, board);
        helper.assertTrue(restored.get(0).assignee().isEmpty() && restored.get(0).locked(), "Lock without assignee kept: " + restored);
        helper.assertTrue(restored.get(1).assignee().equals(Optional.of(BOB)) && restored.get(1).locked(), "Assignment kept: " + restored);

        MissionTrip trip = new MissionTrip(board, CivMod.id("dungeon_raid"), -1L, Optional.empty(), true);
        MissionTrip read = MissionTrip.CODEC.parse(JsonOps.INSTANCE, MissionTrip.CODEC.encodeStart(JsonOps.INSTANCE, trip).getOrThrow()).getOrThrow();
        helper.assertTrue(read.equals(trip), "Assigned trip kept: " + read);
        helper.succeed();
    }
}
