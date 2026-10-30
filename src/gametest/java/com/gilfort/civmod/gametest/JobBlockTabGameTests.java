package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.PatternsTab;
import com.gilfort.civmod.menu.WorkersTab;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the tabs of the JobBlock window (#101, #144, vault {@code Systems/JobBlock.md}, {@code Systems/GUI.md}):
 * every JobBlock shows Workers first, the block settings on top and every Patterns tab below the panel, with the same
 * labels, icons and panel, and opens on Workers.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobBlockTabGameTests {

    private static final String STORAGE = "civmod.gui.job_block.tab.storage";
    private static final String WORKERS = "civmod.gui.job_block.tab.workers";
    private static final String FOUND = "civmod.gui.job_block.tab.found";
    private static final String MISSIONS = "civmod.gui.job_block.tab.missions";
    private static final String STOCK = "civmod.gui.clerk.tab.stock";
    private static final String REQUESTS = "civmod.gui.clerk.tab.requests";
    private static final String DELIVERIES = "civmod.gui.clerk.tab.deliveries";
    private static final String PATTERNS = "civmod.gui.patterns.tab_job";
    private static final String STATION_TYPES = "civmod.gui.station_types.tab";
    private static final String FUEL = "civmod.gui.fuel.tab";

    private JobBlockTabGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "job_block_tabs_resource_collection", JobBlockTabGameTests::resourceCollection);
            register(helper, "job_block_tabs_craftsperson", JobBlockTabGameTests::craftsperson);
            register(helper, "job_block_tabs_fuel", JobBlockTabGameTests::fuel);
            register(helper, "job_block_tabs_clerk", JobBlockTabGameTests::clerk);
            register(helper, "job_block_tabs_mission_board", JobBlockTabGameTests::missionBoard);
            register(helper, "job_block_tabs_standard", JobBlockTabGameTests::standard);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The window of a JobBlock of that Job as its client opens it (no settlement data). */
    static JobBlockMenu menu(GameTestHelper helper, Job job) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        return new JobBlockMenu(1, player.getInventory(), new SimpleContainer(JobBlockEntity.SLOTS), JobBlockInfo.unknown(job, 1));
    }

    /** The translation keys of the tab labels, in tab order. */
    static List<String> keys(List<JobBlockTab> tabs) {
        return tabs.stream().map(tab -> key(tab.label())).toList();
    }

    private static String key(Component label) {
        return label.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : label.getString();
    }

    private static List<Item> icons(List<JobBlockTab> tabs) {
        return tabs.stream().map(tab -> tab.icon().getItem()).toList();
    }

    private static List<JobBlockTab> top(JobBlockMenu menu) {
        return menu.tabs().stream().filter(tab -> !tab.below()).toList();
    }

    private static List<JobBlockTab> below(JobBlockMenu menu) {
        return menu.tabs().stream().filter(JobBlockTab::below).toList();
    }

    /** The indices of the tabs that show the player inventory. */
    private static List<Integer> inventoryTabs(JobBlockMenu menu) {
        // The first player inventory slot follows the 27 storage slots.
        return menu.tabs().stream().filter(tab -> {
            menu.setActiveTab(tab.index());
            return menu.slots.get(JobBlockEntity.SLOTS).isActive();
        }).map(JobBlockTab::index).toList();
    }

    /** Asserts that a new window opens on its first tab, the Workers tab. */
    private static void assertOpensOnWorkers(GameTestHelper helper, JobBlockMenu menu, String name) {
        helper.assertValueEqual(menu.getActiveTab(), 0, name + ": opens on the first tab");
        helper.assertTrue(menu.activeTab() instanceof WorkersTab, name + ": opens on Workers");
    }

    /** Asserts the tabs of a window without tabs below the panel. */
    private static void assertTabs(GameTestHelper helper, Job job, List<String> keys, List<Item> icons, boolean tall, List<Integer> inventory) {
        JobBlockMenu menu = menu(helper, job);
        String name = job.displayName().getString();
        assertOpensOnWorkers(helper, menu, name);
        helper.assertValueEqual(keys(menu.tabs()), keys, name + ": tab labels");
        helper.assertValueEqual(icons(menu.tabs()), icons, name + ": tab icons");
        helper.assertValueEqual(menu.getTabCount(), keys.size(), name + ": tab count");
        helper.assertTrue(menu.tabs().stream().noneMatch(JobBlockTab::below), name + ": no tab below the panel");
        helper.assertTrue(menu.tabs().stream().allMatch(JobBlockTab::shown), name + ": every tab is shown");
        for (int i = 0; i < menu.tabs().size(); i++) {
            helper.assertValueEqual(menu.tabs().get(i).index(), i, name + ": tab index");
        }
        helper.assertValueEqual(menu.isTall(), tall, name + ": tall panel");
        helper.assertValueEqual(inventoryTabs(menu), inventory, name + ": tabs with the player inventory");
    }

    /**
     * Lumberjack, Miner and Fisherman: Workers, Storage, Found; the Gatherer (a Base Job with Specializations) adds
     * Station Types.
     */
    private static void resourceCollection(GameTestHelper helper) {
        for (Job job : List.of(ModJobs.LUMBERJACK.get(), ModJobs.MINER.get(), ModJobs.FISHERMAN.get())) {
            assertTabs(helper, job, List.of(WORKERS, STORAGE, FOUND), List.of(Items.NAME_TAG, Items.CHEST, Items.SPYGLASS), false, List.of(1));
        }
        JobBlockMenu gatherer = menu(helper, ModJobs.GATHERER.get());
        assertOpensOnWorkers(helper, gatherer, "Gatherer");
        helper.assertValueEqual(keys(gatherer.tabs()), List.of(WORKERS, STORAGE, FOUND, STATION_TYPES), "Gatherer tab labels");
        helper.assertTrue(below(gatherer).isEmpty(), "Gatherer: no tab below the panel");
        helper.succeed();
    }

    /**
     * Craftsperson: Workers, Storage, Station Types on top; below the panel its own Patterns tab first, then one per
     * Producing Specialization in registry order, hidden until it is unlocked.
     */
    private static void craftsperson(GameTestHelper helper) {
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        JobBlockMenu menu = menu(helper, craftsperson);
        assertOpensOnWorkers(helper, menu, "Craftsperson");
        List<JobBlockTab> top = top(menu);
        helper.assertValueEqual(keys(top), List.of(WORKERS, STORAGE, STATION_TYPES), "Top tab labels");
        helper.assertValueEqual(icons(top), List.of(Items.NAME_TAG, Items.CHEST, Items.SMITHING_TABLE), "Top tab icons");
        helper.assertValueEqual(menu.tabs().subList(0, top.size()), top, "The tabs below the panel come last");
        List<JobBlockTab> below = below(menu);
        helper.assertTrue(below.stream().allMatch(PatternsTab.class::isInstance), "Only Patterns tabs below the panel");
        helper.assertValueEqual(below.size(), menu.patternJobs().size(), "Every Patterns tab is below the panel");
        List<Job> specializations = List.of(StationTypeGameTests.job(CivMod.id("gametest_mason")), StationTypeGameTests.job(CivMod.id("smith")));
        helper.assertTrue(menu.patternJobs().containsAll(specializations) && menu.patternJobs().getFirst() == craftsperson,
                "The Craftsperson first, then its Specializations: " + menu.patternJobs());
        List<Job> registryOrder = ModJobs.REGISTRY.stream().filter(job -> job != craftsperson && menu.patternJobs().contains(job)).toList();
        helper.assertValueEqual(menu.patternJobs().subList(1, menu.patternJobs().size()), registryOrder, "Specializations in registry order");
        helper.assertValueEqual(key(below.getFirst().label()), PATTERNS, "Base Job tab label");
        helper.assertTrue(below.getFirst().icon().is(Items.CRAFTING_TABLE), "Base Job tab icon: crafting table");
        helper.assertTrue(below.getFirst().shown(), "The Base Job's Patterns tab is shown");
        for (int i = 1; i < below.size(); i++) {
            Job job = menu.patternJobs().get(i);
            helper.assertValueEqual(key(below.get(i).label()), PATTERNS, "Specialization tab label");
            helper.assertTrue(below.get(i).icon().is(job.icon().getItem()), "Specialization tab icon: " + job.icon());
            helper.assertFalse(below.get(i).shown(), "Hidden until unlocked");
        }
        helper.assertTrue(top.stream().allMatch(JobBlockTab::shown), "Workers, Storage and Station Types are shown");
        helper.assertValueEqual(menu.getTabCount(), menu.tabs().size(), "Tab count");
        helper.assertTrue(menu.isTall(), "Tall panel");
        List<Integer> inventory = new ArrayList<>(List.of(1, 2));
        for (int i = 0; i < below.size(); i++) {
            inventory.add(3 + i);
        }
        helper.assertValueEqual(inventoryTabs(menu), inventory, "Inventory on Storage, Station Types and Patterns");
        // A Producing Job without Specializations: the Station Types tab is hidden, its Patterns tab is below.
        JobBlockMenu mechanic = menu(helper, ModJobs.MECHANIC.get());
        assertOpensOnWorkers(helper, mechanic, "Mechanic");
        helper.assertValueEqual(keys(mechanic.tabs()), List.of(WORKERS, STORAGE, STATION_TYPES, PATTERNS), "Mechanic tab labels");
        helper.assertFalse(mechanic.tabs().get(2).shown(), "Without Specializations the Station Types tab is hidden");
        helper.assertTrue(mechanic.tabs().get(3).below() && mechanic.tabs().get(3).shown(), "Mechanic: its Patterns tab is shown below the panel");
        helper.succeed();
    }

    /** A Producing Job burning fuel (Smelter): Workers, Storage, Station Types, Fuel on top; every Patterns tab below. */
    private static void fuel(GameTestHelper helper) {
        Job smelter = ModJobs.SMELTER.get();
        JobBlockMenu menu = menu(helper, smelter);
        assertOpensOnWorkers(helper, menu, "Smelter");
        helper.assertValueEqual(keys(top(menu)), List.of(WORKERS, STORAGE, STATION_TYPES, FUEL), "Smelter top tab labels");
        helper.assertTrue(below(menu).stream().allMatch(PatternsTab.class::isInstance), "Only Patterns tabs below the panel");
        helper.assertValueEqual(below(menu).size(), menu.patternJobs().size(), "Every Patterns tab is below the panel");
        helper.assertTrue(menu.patternJobs().getFirst() == smelter, "The Smelter's Patterns tab first: " + menu.patternJobs());
        helper.succeed();
    }

    /** Clerk: Workers, Storage, Stock, Requests, Deliveries in the tall panel. */
    private static void clerk(GameTestHelper helper) {
        assertTabs(helper, ModJobs.CLERK.get(), List.of(WORKERS, STORAGE, STOCK, REQUESTS, DELIVERIES),
                List.of(Items.NAME_TAG, Items.CHEST, Items.WRITABLE_BOOK, Items.PAPER, Items.BUNDLE), true, List.of(1, 2));
        helper.succeed();
    }

    /** Mission Board: Workers, Storage, Missions, Station Types (tall, with the player inventory). */
    private static void missionBoard(GameTestHelper helper) {
        assertTabs(helper, ModJobs.ADVENTURER.get(), List.of(WORKERS, STORAGE, MISSIONS, STATION_TYPES),
                List.of(Items.NAME_TAG, Items.CHEST, Items.MAP, Items.SMITHING_TABLE), true, List.of(1, 3));
        helper.succeed();
    }

    /** A Service JobBlock and a Logistics JobBlock other than the Clerk: Workers and Storage only. */
    private static void standard(GameTestHelper helper) {
        Job service = ModJobs.REGISTRY.getValue(Identifier.fromNamespaceAndPath(CivMod.MOD_ID, "gametest_no_costs"));
        for (Job job : List.of(service, ModJobs.DELIVERYMAN.get())) {
            assertTabs(helper, job, List.of(WORKERS, STORAGE), List.of(Items.NAME_TAG, Items.CHEST), false, List.of(1));
        }
        helper.succeed();
    }
}
