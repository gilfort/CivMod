package com.gilfort.civmod.gametest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Missions and the daily mission offer of the Mission Board (vault {@code Systems/Mission Jobs.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionOfferGameTests {

    private static final BlockPos BOARD = new BlockPos(0, 64, 0);
    private static final Identifier COMMON = CivMod.id("test_common");
    private static final Identifier RARE = CivMod.id("test_rare");
    private static final Identifier SPECIAL = CivMod.id("test_special");

    private MissionOfferGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_offer_size", MissionOfferGameTests::offerSize);
            register(helper, "mission_draw_weights", MissionOfferGameTests::drawWeights);
            register(helper, "mission_draw_maximum", MissionOfferGameTests::drawMaximum);
            register(helper, "mission_draw_level_filter", MissionOfferGameTests::drawLevelFilter);
            register(helper, "mission_refill_keeps_marked", MissionOfferGameTests::refillKeepsMarked);
            register(helper, "mission_offer_daily_refill", MissionOfferGameTests::dailyRefill);
            register(helper, "mission_offer_per_board", MissionOfferGameTests::perBoard);
            register(helper, "mission_offer_persistence", MissionOfferGameTests::persistence);
            register(helper, "mission_samples", MissionOfferGameTests::samples);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    static Mission mission(Job job, int weight, int maxPerOffer) {
        return new Mission(Component.literal("Test"), Component.empty(), Optional.empty(), List.of(BuiltInLootTables.SIMPLE_DUNGEON), 1, 10, 2,
                List.of(job), weight, maxPerOffer);
    }

    private static Job specialization() {
        return ModJobs.REGISTRY.getValue(CivMod.id("gametest_mission_specialization"));
    }

    private static long count(List<MissionOffer.Entry> offer, Identifier mission) {
        return offer.stream().filter(entry -> entry.mission().equals(mission)).count();
    }

    /** The offer holds 2 / 4 / 10 / 14 / 20 missions by board level. */
    private static void offerSize(GameTestHelper helper) {
        int[] expected = {2, 4, 10, 14, 20};
        for (int level = 1; level <= 5; level++) {
            helper.assertTrue(MissionRules.offerSize(level) == expected[level - 1], "Offer size at level " + level + ": " + MissionRules.offerSize(level));
        }
        helper.succeed();
    }

    /** A mission with weight 3 is drawn about three times as often as one with weight 1. */
    private static void drawWeights(GameTestHelper helper) {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(COMMON, mission(ModJobs.ADVENTURER.get(), 3, 100));
        pool.put(RARE, mission(ModJobs.ADVENTURER.get(), 1, 100));
        RandomSource random = RandomSource.create(42L);
        long common = 0;
        long total = 0;
        for (int i = 0; i < 100; i++) {
            List<MissionOffer.Entry> offer = MissionRules.refill(List.of(), pool, List.of(ModJobs.ADVENTURER.get()), 20, random);
            helper.assertTrue(offer.size() == 20, "Full offer: " + offer.size());
            common += count(offer, COMMON);
            total += offer.size();
        }
        double share = common / (double) total;
        helper.assertTrue(share > 0.70 && share < 0.80, "Weight 3 of 4 should give ~75 %, got " + share);
        helper.succeed();
    }

    /** A mission appears at most its maximum per offer; with every mission at its maximum the offer stays short. */
    private static void drawMaximum(GameTestHelper helper) {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(RARE, mission(ModJobs.ADVENTURER.get(), 1000, 1));
        pool.put(COMMON, mission(ModJobs.ADVENTURER.get(), 1, 3));
        List<MissionOffer.Entry> offer = MissionRules.refill(List.of(), pool, List.of(ModJobs.ADVENTURER.get()), 10, RandomSource.create(1L));
        helper.assertTrue(count(offer, RARE) == 1, "Rare at most once: " + offer);
        helper.assertTrue(count(offer, COMMON) == 3, "Common at most three times: " + offer);
        helper.assertTrue(offer.size() == 4, "The pool is exhausted after 4 entries: " + offer);
        helper.assertTrue(offer.stream().noneMatch(MissionOffer.Entry::marked), "New entries are unmarked");
        helper.succeed();
    }

    /** Only missions a Mission Job unlocked at the board (by its Station Types) can take are drawn. */
    private static void drawLevelFilter(GameTestHelper helper) {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(SPECIAL, mission(specialization(), 1, 20));
        pool.put(COMMON, mission(ModJobs.ADVENTURER.get(), 1, 20));
        SettlementData data = new SettlementData();
        BlockPos board = new BlockPos(0, 64, 0);
        placeBoard(data, board, 5);
        List<MissionOffer.Entry> locked = MissionOffers.refresh(data, board, 1L, pool, RandomSource.create(2L));
        helper.assertTrue(count(locked, SPECIAL) == 0 && count(locked, COMMON) == 20, "Without Station Types only Adventurer missions: " + locked);
        TestAssignments.setStationType(data, board, 1, Items.BELL);
        List<MissionOffer.Entry> unlocked = MissionOffers.refresh(data, board, 2L, pool, RandomSource.create(2L));
        helper.assertTrue(count(unlocked, SPECIAL) > 0, "The bell adds Specialization missions: " + unlocked);
        helper.succeed();
    }

    /**
     * A refill keeps marked entries (counted towards size and maximum), replaces unmarked ones and drops marked entries
     * whose mission no longer exists.
     */
    private static void refillKeepsMarked(GameTestHelper helper) {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(RARE, mission(ModJobs.ADVENTURER.get(), 1000, 1));
        pool.put(COMMON, mission(ModJobs.ADVENTURER.get(), 1, 20));
        List<MissionOffer.Entry> before = List.of(new MissionOffer.Entry(COMMON, true), new MissionOffer.Entry(COMMON, false),
                new MissionOffer.Entry(RARE, true), new MissionOffer.Entry(CivMod.id("removed"), true));
        List<MissionOffer.Entry> after = MissionRules.refill(before, pool, List.of(ModJobs.ADVENTURER.get()), 4, RandomSource.create(3L));
        helper.assertTrue(after.size() == 4, "Refilled to 4: " + after);
        helper.assertTrue(after.subList(0, 2).equals(List.of(new MissionOffer.Entry(COMMON, true), new MissionOffer.Entry(RARE, true))),
                "Marked entries stay first, in order: " + after);
        helper.assertTrue(after.subList(2, 4).equals(List.of(new MissionOffer.Entry(COMMON, false), new MissionOffer.Entry(COMMON, false))),
                "The rare mission is at its maximum, so only common ones are drawn: " + after);
        helper.succeed();
    }

    private static Map<Identifier, Mission> distinctPool() {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            pool.put(CivMod.id("test_" + i), mission(ModJobs.ADVENTURER.get(), 1, 1));
        }
        return pool;
    }

    private static Settlement placeBoard(SettlementData data, BlockPos pos, int level) {
        return SettlementManager.place(data, pos, ModJobs.ADVENTURER.get(), level, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE)
                .settlement();
    }

    private static List<MissionOffer.Entry> entries(SettlementData data, BlockPos board) {
        return data.settlementAt(board).flatMap(s -> s.block(board)).orElseThrow().missionOffer().entries();
    }

    /**
     * The first access fills the offer; within a day it stays; the next day unmarked entries are replaced and marked
     * ones stay. Other JobBlocks have no offer.
     */
    private static void dailyRefill(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        placeBoard(data, BOARD, 2);
        Map<Identifier, Mission> pool = distinctPool();
        RandomSource random = RandomSource.create(5L);
        List<MissionOffer.Entry> first = MissionOffers.refresh(data, BOARD, 10L, pool, random);
        helper.assertTrue(first.size() == 4, "A level-2 board offers 4 missions: " + first);
        helper.assertTrue(MissionOffers.setMarked(data, BOARD, 1, first.get(1).mission(), true), "Entry 1 is marked");
        helper.assertTrue(MissionOffers.setMarked(data, BOARD, 2, first.get(2).mission(), true), "Entry 2 is marked");
        helper.assertTrue(MissionOffers.setMarked(data, BOARD, 2, first.get(2).mission(), false), "Entry 2 is unmarked again");
        helper.assertFalse(MissionOffers.setMarked(data, BOARD, 3, first.get(0).mission(), true), "A stale mission id changes nothing");
        helper.assertFalse(entries(data, BOARD).get(3).marked(), "Entry 3 stays unmarked");
        helper.assertFalse(MissionOffers.setMarked(data, BOARD, 9, first.get(0).mission(), true), "No entry 9");
        List<MissionOffer.Entry> sameDay = MissionOffers.refresh(data, BOARD, 10L, pool, random);
        helper.assertTrue(sameDay.equals(entries(data, BOARD)) && sameDay.size() == 4 && sameDay.get(1).marked()
                && sameDay.get(0).mission().equals(first.get(0).mission()), "Unchanged within the day: " + sameDay);
        List<MissionOffer.Entry> nextDay = MissionOffers.refresh(data, BOARD, 11L, pool, random);
        helper.assertTrue(nextDay.size() == 4, "Refilled to 4: " + nextDay);
        helper.assertTrue(nextDay.get(0).equals(new MissionOffer.Entry(first.get(1).mission(), true)), "The marked entry stays: " + nextDay);
        helper.assertTrue(nextDay.subList(1, 4).stream().noneMatch(MissionOffer.Entry::marked), "New entries are unmarked: " + nextDay);
        List<Identifier> replaced = List.of(first.get(0).mission(), first.get(2).mission(), first.get(3).mission());
        helper.assertTrue(!nextDay.subList(1, 4).stream().map(MissionOffer.Entry::mission).toList().equals(replaced),
                "Unmarked entries are drawn again: " + nextDay);

        BlockPos lumberjack = new BlockPos(5, 64, 0);
        SettlementManager.place(data, lumberjack, ModJobs.LUMBERJACK.get(), 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        helper.assertTrue(MissionOffers.refresh(data, lumberjack, 11L, pool, random).isEmpty(), "A Lumberjack block has no offer");
        helper.succeed();
    }

    /** Several boards in one settlement each have their own offer and marks. */
    private static void perBoard(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = placeBoard(data, BOARD, 1);
        BlockPos second = new BlockPos(5, 64, 0);
        helper.assertTrue(placeBoard(data, second, 3) == settlement, "Both boards in one settlement");
        Map<Identifier, Mission> pool = distinctPool();
        RandomSource random = RandomSource.create(6L);
        List<MissionOffer.Entry> offer = MissionOffers.refresh(data, BOARD, 0L, pool, random);
        helper.assertTrue(offer.size() == 2, "Level 1: 2 missions");
        helper.assertTrue(MissionOffers.refresh(data, second, 0L, pool, random).size() == 10, "Level 3: 10 missions");
        MissionOffers.setMarked(data, BOARD, 0, offer.get(0).mission(), true);
        helper.assertTrue(entries(data, BOARD).get(0).marked(), "Marked on the first board");
        helper.assertTrue(entries(data, second).stream().noneMatch(MissionOffer.Entry::marked), "Nothing marked on the second board");
        helper.succeed();
    }

    /** The three sample missions load from the mod's datapack and fill a level-1 board for the Adventurer. */
    private static void samples(GameTestHelper helper) {
        Map<Identifier, Mission> missions = Missions.all(helper.getLevel().registryAccess());
        for (String name : List.of("dungeon_raid", "abandoned_mineshaft", "village_heist")) {
            Mission mission = missions.get(CivMod.id(name));
            helper.assertTrue(mission != null, "Sample mission " + name + " missing: " + missions.keySet());
            helper.assertTrue(mission.jobs().contains(ModJobs.ADVENTURER.get()), name + " is an Adventurer mission");
            helper.assertTrue(mission.lootTables().stream().allMatch(table -> table.identifier().getNamespace().equals("minecraft")),
                    name + " uses vanilla loot tables");
        }
        Mission dungeon = missions.get(CivMod.id("dungeon_raid"));
        helper.assertTrue(dungeon.lootTables().equals(List.of(BuiltInLootTables.SIMPLE_DUNGEON)), "Dungeon Raid loot: " + dungeon.lootTables());
        SettlementData data = new SettlementData();
        placeBoard(data, BOARD, 1);
        helper.assertTrue(MissionOffers.refresh(data, BOARD, 0L, missions, RandomSource.create(9L)).size() == 2, "A level-1 board fills its offer");
        helper.succeed();
    }

    /** Offer, marks and refill day survive a save / load round trip. */
    private static void persistence(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        placeBoard(data, BOARD, 2);
        List<MissionOffer.Entry> offer = MissionOffers.refresh(data, BOARD, 7L, distinctPool(), RandomSource.create(7L));
        MissionOffers.setMarked(data, BOARD, 3, offer.get(3).mission(), true);
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        MissionOffer saved = data.settlementAt(BOARD).flatMap(s -> s.block(BOARD)).orElseThrow().missionOffer();
        MissionOffer restored = loaded.settlementAt(BOARD).flatMap(s -> s.block(BOARD)).orElseThrow().missionOffer();
        helper.assertTrue(restored.equals(saved) && restored.day() == 7L && restored.entries().get(3).marked(), "Offer lost: " + restored);
        helper.assertTrue(MissionOffers.refresh(loaded, BOARD, 7L, distinctPool(), RandomSource.create(8L)).equals(saved.entries()),
                "No second refill on the same day after loading");
        helper.succeed();
    }
}
