package com.gilfort.civmod.gametest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.MissionsTab;
import com.gilfort.civmod.menu.MissionsTabData;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestSettlements;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the auto-enable of missions (#288, vault {@code Systems/Mission Jobs.md}): the Auto view lists the missions
 * the board's unlocked Mission Jobs may take, ticking marks the entries on offer, every refill marks new entries of ticked
 * missions, unticking leaves marks, the ticks are kept per board and survive a save.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionAutoGameTests {

    private static final BlockPos BOARD = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = new BlockPos(5, 64, 0);
    private static final Identifier COMMON = CivMod.id("test_common");
    private static final Identifier OTHER = CivMod.id("test_other");
    private static final Identifier SPECIAL = CivMod.id("test_special");

    private MissionAutoGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_auto_lists_unlocked", MissionAutoGameTests::listsUnlocked);
            register(helper, "mission_auto_tick_marks_offer", MissionAutoGameTests::tickMarksOffer);
            register(helper, "mission_auto_refill", MissionAutoGameTests::refill);
            register(helper, "mission_auto_untick_keeps_marks", MissionAutoGameTests::untickKeepsMarks);
            register(helper, "mission_auto_per_board_persistence", MissionAutoGameTests::perBoardPersistence);
            register(helper, "mission_auto_pick_rule", MissionAutoGameTests::pickRule);
            register(helper, "mission_auto_tab", MissionAutoGameTests::tab);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Two Adventurer missions (up to three copies each) and one of a Specialization the board has not unlocked. */
    private static Map<Identifier, Mission> pool() {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(COMMON, MissionOfferGameTests.mission(ModJobs.ADVENTURER.get(), 1, 3));
        pool.put(OTHER, MissionOfferGameTests.mission(ModJobs.ADVENTURER.get(), 1, 3));
        pool.put(SPECIAL, MissionOfferGameTests.mission(ModJobs.REGISTRY.getValue(CivMod.id("gametest_mission_specialization")), 1, 3));
        return pool;
    }

    private static SettlementData board(BlockPos pos, int level) {
        SettlementData data = new SettlementData();
        TestSettlements.place(data, pos, ModJobs.ADVENTURER.get(), level);
        return data;
    }

    private static MissionOffer offer(SettlementData data, BlockPos pos) {
        return data.settlementAt(pos).flatMap(s -> s.block(pos)).orElseThrow().missionOffer();
    }

    private static long marked(List<MissionOffer.Entry> entries, Identifier mission) {
        return entries.stream().filter(entry -> entry.mission().equals(mission) && entry.marked()).count();
    }

    private static long count(List<MissionOffer.Entry> entries, Identifier mission) {
        return entries.stream().filter(entry -> entry.mission().equals(mission)).count();
    }

    /** The Auto view lists exactly the missions the unlocked Jobs may take; a locked Job's mission is missing and cannot be ticked. */
    private static void listsUnlocked(GameTestHelper helper) {
        SettlementData data = board(BOARD, 2);
        Map<Identifier, Mission> pool = pool();
        helper.assertTrue(MissionOffers.available(data, BOARD, pool).equals(List.of(COMMON, OTHER)),
                "Only the Adventurer's missions: " + MissionOffers.available(data, BOARD, pool));
        helper.assertFalse(MissionOffers.setAuto(data, BOARD, SPECIAL, true, pool), "A mission of a locked Job cannot be ticked");
        helper.assertFalse(MissionOffers.setAuto(data, BOARD, CivMod.id("no_such_mission"), true, pool), "An unknown mission cannot be ticked");
        helper.assertTrue(offer(data, BOARD).auto().isEmpty(), "Nothing ticked");
        helper.succeed();
    }

    /** Ticking marks the entries of that mission on offer now and leaves the others alone. */
    private static void tickMarksOffer(GameTestHelper helper) {
        SettlementData data = board(BOARD, 2);
        Map<Identifier, Mission> pool = pool();
        List<MissionOffer.Entry> first = MissionOffers.refresh(data, BOARD, 10L, pool, RandomSource.create(3L));
        Identifier ticked = first.getFirst().mission();
        Identifier other = ticked.equals(COMMON) ? OTHER : COMMON;
        helper.assertTrue(first.stream().noneMatch(MissionOffer.Entry::marked), "Nothing marked before");
        helper.assertTrue(MissionOffers.setAuto(data, BOARD, ticked, true, pool), "Ticking is taken");
        List<MissionOffer.Entry> now = offer(data, BOARD).entries();
        helper.assertTrue(marked(now, ticked) == count(now, ticked) && count(now, ticked) > 0, "Every entry of the ticked mission is marked: " + now);
        helper.assertTrue(marked(now, other) == 0, "Entries of other missions stay unmarked: " + now);
        helper.assertTrue(offer(data, BOARD).auto().contains(ticked), "The tick is kept");
        helper.succeed();
    }

    /** After the daily refill new entries of ticked missions are marked, entries of unticked ones are not. */
    private static void refill(GameTestHelper helper) {
        SettlementData data = board(BOARD, 3);
        Map<Identifier, Mission> pool = pool();
        MissionOffers.refresh(data, BOARD, 10L, pool, RandomSource.create(4L));
        helper.assertTrue(MissionOffers.setAuto(data, BOARD, COMMON, true, pool), "Ticking is taken");
        for (long day = 11L; day < 16L; day++) {
            List<MissionOffer.Entry> entries = MissionOffers.refresh(data, BOARD, day, pool, RandomSource.create(day));
            helper.assertTrue(marked(entries, COMMON) == count(entries, COMMON), "Day " + day + ": ticked entries marked: " + entries);
            helper.assertTrue(marked(entries, OTHER) == 0, "Day " + day + ": unticked entries not marked: " + entries);
            helper.assertTrue(entries.stream().noneMatch(entry -> entry.mission().equals(SPECIAL)), "No locked Job's mission is drawn");
        }
        helper.succeed();
    }

    /** Unticking leaves the marked entries as they are; the next refill no longer marks that mission. */
    private static void untickKeepsMarks(GameTestHelper helper) {
        SettlementData data = board(BOARD, 3);
        Map<Identifier, Mission> pool = pool();
        MissionOffers.refresh(data, BOARD, 10L, pool, RandomSource.create(4L));
        MissionOffers.setAuto(data, BOARD, COMMON, true, pool);
        MissionOffers.setAuto(data, BOARD, OTHER, true, pool);
        List<MissionOffer.Entry> ticked = offer(data, BOARD).entries();
        helper.assertTrue(MissionOffers.setAuto(data, BOARD, COMMON, false, pool), "Unticking is taken");
        helper.assertTrue(offer(data, BOARD).entries().equals(ticked), "The entries are as they were: " + offer(data, BOARD).entries());
        helper.assertFalse(offer(data, BOARD).auto().contains(COMMON), "The tick is gone");
        // The next day only the other mission is still marked on its own; the old marks of COMMON stay (they are marked entries).
        List<MissionOffer.Entry> next = MissionOffers.refresh(data, BOARD, 11L, pool, RandomSource.create(11L));
        long keptCommon = ticked.stream().filter(entry -> entry.mission().equals(COMMON) && entry.marked()).count();
        helper.assertTrue(marked(next, COMMON) == keptCommon, "Marked entries of the unticked mission stay, new ones are not marked: " + next);
        helper.assertTrue(marked(next, OTHER) == count(next, OTHER), "The still ticked mission is marked: " + next);
        helper.succeed();
    }

    /** The ticks belong to one board and survive a save and load. */
    private static void perBoardPersistence(GameTestHelper helper) {
        SettlementData data = board(BOARD, 2);
        TestSettlements.place(data, SECOND, ModJobs.ADVENTURER.get(), 2);
        Map<Identifier, Mission> pool = pool();
        helper.assertTrue(MissionOffers.setAuto(data, BOARD, COMMON, true, pool), "Ticked at the first board");
        helper.assertTrue(offer(data, SECOND).auto().isEmpty(), "The second board has no tick");
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        helper.assertTrue(offer(loaded, BOARD).auto().equals(offer(data, BOARD).auto()) && offer(loaded, BOARD).auto().contains(COMMON),
                "The tick survives a save: " + offer(loaded, BOARD).auto());
        helper.assertTrue(offer(loaded, SECOND).auto().isEmpty(), "Still none at the second board");
        helper.succeed();
    }

    /** An auto-marked mission above a settler's skill is still never taken on its own; the pick rule is unchanged. */
    private static void pickRule(GameTestHelper helper) {
        SettlementData data = board(BOARD, 2);
        Map<Identifier, Mission> pool = pool();
        MissionOffers.refresh(data, BOARD, 10L, pool, RandomSource.create(3L));
        MissionOffers.setAuto(data, BOARD, COMMON, true, pool);
        MissionOffers.setAuto(data, BOARD, OTHER, true, pool);
        List<MissionOffer.Entry> entries = offer(data, BOARD).entries();
        helper.assertTrue(entries.stream().allMatch(MissionOffer.Entry::marked), "Everything is marked: " + entries);
        // The sample missions are level 10: a skill-9 settler picks nothing, a skill-10 settler picks one.
        UUID settler = UUID.randomUUID();
        helper.assertTrue(MissionRules.pick(entries, pool, ModJobs.ADVENTURER.get(), 9, settler, RandomSource.create(1L)).isEmpty(),
                "A mission above the skill is not taken");
        helper.assertTrue(MissionRules.pick(entries, pool, ModJobs.ADVENTURER.get(), 10, settler, RandomSource.create(1L)).isPresent(),
                "A mission at the skill is taken");
        helper.assertTrue(entries.stream().allMatch(entry -> entry.assignee().isEmpty() && !entry.locked()), "Ticking assigns nothing");
        helper.succeed();
    }

    /** The window offers the ticks through the Missions tab: the data lists the missions and the ticks, a toggle changes them. */
    private static void tab(GameTestHelper helper) {
        var settler = MissionRunGameTests.adventurer(helper);
        JobBlockMenu menu = MissionsTabGameTests.menu(helper);
        MissionsTab tab = menu.tab(MissionsTab.class).orElseThrow();
        MissionsTabData start = MissionsTabGameTests.sees(menu);
        helper.assertFalse(start.available().isEmpty(), "The Auto view lists missions: " + start.available());
        Identifier mission = start.available().getFirst();
        helper.assertTrue(start.auto().isEmpty(), "Nothing ticked");
        helper.assertTrue(tab.setAuto(mission, true), "Ticking is taken");
        helper.assertTrue(MissionsTabGameTests.shown(menu).auto().contains(mission), "The window shows the tick");
        helper.assertTrue(MissionsTabGameTests.shown(menu).offer().stream().filter(entry -> entry.mission().equals(mission))
                .allMatch(MissionOffer.Entry::marked), "The offer shows the marks");
        helper.assertFalse(tab.setAuto(CivMod.id("no_such_mission"), true), "An unknown mission is refused");
        helper.assertTrue(tab.setAuto(mission, false), "Unticking is taken");
        helper.assertTrue(MissionsTabGameTests.shown(menu).auto().isEmpty(), "The window shows no tick");
        settler.discard();
        helper.succeed();
    }
}
