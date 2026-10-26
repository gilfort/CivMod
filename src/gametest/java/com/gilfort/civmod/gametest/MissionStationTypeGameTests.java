package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.MissionsTab;
import com.gilfort.civmod.menu.StationTypesTab;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StationTypes;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Station Types of the Mission Board and the Bard (#115, vault {@code Systems/Mission Jobs.md},
 * {@code Jobs/Bard.md}): Mission Specializations of the Adventurer are unlocked by their unlock item on the board, not
 * by its level.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionStationTypeGameTests {

    private static final BlockPos BOARD = new BlockPos(1, 1, 1);
    private static final Identifier SCOUT = Identifier.fromNamespaceAndPath("civmod_example", "scout");
    private static final Identifier BARD_MISSION = CivMod.id("gametest_bard_mission");

    private MissionStationTypeGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_board_station_types_tab", MissionStationTypeGameTests::tab);
            register(helper, "mission_board_note_block_unlocks_bard", MissionStationTypeGameTests::noteBlockUnlocksBard);
            register(helper, "mission_board_bard_falls_back", MissionStationTypeGameTests::bardFallsBack);
            register(helper, "mission_board_addon_job_by_item", MissionStationTypeGameTests::addonJobByItem);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static BlockPos board(GameTestHelper helper) {
        helper.setBlock(BOARD, ModBlocks.MISSION_BOARD.get());
        return helper.absolutePos(BOARD);
    }

    private static List<Job> offered(ServerLevel level, BlockPos pos) {
        return JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList();
    }

    /**
     * The board shows the Station Types tab after its Missions tab: slot 1 is the Adventurer (the board itself), locked;
     * one more slot per level. A note block is refused in a closed slot and in the first one.
     */
    private static void tab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = board(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, player, pos);
        StationTypesTab stations = menu.tab(StationTypesTab.class).orElseThrow();
        helper.assertTrue(stations.shown(), "The Mission Board shows the Station Types tab");
        helper.assertTrue(menu.tab(MissionsTab.class).orElseThrow().index() < stations.index(), "Station Types after the Missions tab");
        StationTypesTabData data = StationTypesTabData.of(level, pos);
        helper.assertTrue(data.item(0) == ModItems.MISSION_BOARD.get() && data.open() == 1 && data.unlocked().equals(List.of(ModJobs.ADVENTURER.get())),
                "Level 1: only the Adventurer slot: " + data);

        menu.setCarried(new ItemStack(Items.NOTE_BLOCK, 2));
        helper.assertFalse(menu.clickMenuButton(player, stations.slotButtonId(1)), "Level 1: slot 2 is closed");
        helper.assertTrue(menu.getCarried().getCount() == 2, "A refused note block is kept");

        // Level 2 only: a higher board level would exclude the neighbouring tests' JobBlocks from their settlements.
        StationTypeGameTests.setLevel(helper, BOARD, 2);
        helper.assertTrue(StationTypesTabData.of(level, pos).open() == 2, "Level 2 opens 2 slots");
        menu = StationTypeGameTests.menu(helper, player, pos);
        stations = menu.tab(StationTypesTab.class).orElseThrow();
        menu.setCarried(new ItemStack(Items.NOTE_BLOCK, 2));
        helper.assertFalse(menu.clickMenuButton(player, stations.slotButtonId(0)), "The Adventurer slot is locked");
        helper.assertTrue(menu.getCarried().getCount() == 2, "Still both note blocks");
        helper.succeed();
    }

    /**
     * Without a note block a level-2 board offers only the Adventurer; a note block unlocks the Bard: the job picker
     * offers it and the job switcher switches between Adventurer and Bard. The Bard takes the sample Villager
     * missions.
     */
    private static void noteBlockUnlocksBard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job adventurer = ModJobs.ADVENTURER.get();
        Job bard = ModJobs.BARD.get();
        helper.assertTrue(bard.category() == JobCategory.MISSION && bard.parent().equals(Optional.of(adventurer)), "Bard: Mission Specialization");
        helper.assertTrue(bard.defaultBaseExp() == 10 && bard.defaultHungerFactor() == 1.0, "Bard: base EXP 10, hunger factor 1.0");
        helper.assertTrue(bard.unlockItem().equals(Optional.of(Items.NOTE_BLOCK)), "Bard: unlocked by a note block");
        helper.assertTrue(bard.displayName().getString().equals("Bard"), "Lang key: " + bard.displayName().getString());

        BlockPos pos = board(helper);
        StationTypeGameTests.setLevel(helper, BOARD, 2);
        helper.assertTrue(offered(level, pos).equals(List.of(adventurer)), "Level 2 without Station Types: only the Adventurer " + offered(level, pos));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        UUID first = UUID.randomUUID();
        helper.assertTrue(TestAssignments.assign(data, pos, first, "Bert", settlement.id(), List.of(), bard) == AssignResult.NOT_OFFERED,
                "No Bard before the note block");

        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.NOTE_BLOCK), "The note block is taken");
        helper.assertTrue(offered(level, pos).equals(List.of(adventurer, bard)), "The board offers the Bard: " + offered(level, pos));
        helper.assertTrue(TestAssignments.assign(data, pos, first, "Bert", settlement.id(), List.of(), bard) == AssignResult.OK, "Joins as Bard");
        helper.assertTrue(JobAssignments.switchOptions(data, pos, first, List.of()).equals(List.of(adventurer)), "Switch to the Adventurer");
        helper.assertTrue(TestAssignments.switchJob(data, pos, first, List.of(), adventurer), "Switched to the Adventurer");
        helper.assertTrue(JobAssignments.switchOptions(data, pos, first, List.of()).equals(List.of(bard)), "And back to the Bard");

        Map<Identifier, Mission> pool = Missions.all(level.registryAccess());
        List<MissionOffer.Entry> marked = pool.keySet().stream().map(id -> new MissionOffer.Entry(id, true)).toList();
        OptionalInt pick = MissionRules.pick(marked, pool, bard, 100, first, RandomSource.create(0L));
        helper.assertTrue(pick.isPresent() && pool.get(marked.get(pick.getAsInt()).mission()).villagers().isPresent(),
                "The Bard takes a sample Villager mission: " + pick);
        helper.succeed();
    }

    /**
     * Overwriting the note block makes Bards fall back to the Adventurer; like a job switch their mission assignments
     * on the board are removed and the missions stay locked.
     */
    private static void bardFallsBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job adventurer = ModJobs.ADVENTURER.get();
        Job bard = ModJobs.BARD.get();
        BlockPos pos = board(helper);
        StationTypeGameTests.setLevel(helper, BOARD, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.NOTE_BLOCK), "The note block is taken");
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        UUID settler = UUID.randomUUID();
        helper.assertTrue(TestAssignments.assign(data, pos, settler, "Bert", settlement.id(), List.of(), bard) == AssignResult.OK, "Joins as Bard");
        Map<Identifier, Mission> pool = Map.of(BARD_MISSION, MissionOfferGameTests.mission(bard, 1, 1));
        List<MissionOffer.Entry> offer = MissionOffers.refresh(data, pos, 3L, pool, RandomSource.create(3L));
        helper.assertTrue(offer.size() == 1 && offer.getFirst().mission().equals(BARD_MISSION), "The Bard's mission is drawn: " + offer);
        helper.assertTrue(MissionOffers.setMarked(data, pos, 0, BARD_MISSION, true)
                && MissionOffers.assign(data, pos, 0, BARD_MISSION, settler, bard, pool), "Assigned to the Bard");

        Job other = ModJobs.REGISTRY.getValue(CivMod.id("gametest_mission_specialization"));
        // Directly, not through the window: opening it refills the offer for the real day, without the test mission.
        helper.assertTrue(StationTypes.put(level, pos, 1, new ItemStack(other.unlockItem().orElseThrow())) == StationTypes.PutResult.REPLACED,
                "The note block is overwritten");
        helper.assertTrue(StationTypeGameTests.block(level, pos).assignee(settler).map(assignee -> assignee.job()).equals(Optional.of(adventurer)),
                "The Bard falls back to the Adventurer");
        MissionOffer.Entry entry = MissionOffers.refresh(data, pos, 3L, pool, RandomSource.create(3L)).getFirst();
        helper.assertTrue(entry.assignee().isEmpty() && entry.locked() && entry.marked(), "Assignment removed, mission still locked: " + entry);
        helper.succeed();
    }

    /** The example addon's Scout is unlocked on the board by its spyglass, not by the board level. */
    private static void addonJobByItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job scout = ModJobs.REGISTRY.getValue(SCOUT);
        BlockPos pos = board(helper);
        StationTypeGameTests.setLevel(helper, BOARD, 2);
        helper.assertFalse(offered(level, pos).contains(scout), "Not offered by the board level");
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.SPYGLASS), "The spyglass is taken");
        helper.assertTrue(offered(level, pos).contains(scout), "The spyglass unlocks the Scout: " + offered(level, pos));
        helper.succeed();
    }
}
