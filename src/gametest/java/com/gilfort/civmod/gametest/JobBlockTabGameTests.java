package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
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
 * GameTests for the tabs of the JobBlock window (#101, vault {@code Systems/JobBlock.md}, {@code Systems/GUI.md}): every
 * JobBlock shows its tabs in the same order, with the same labels, icons and panel as before the tabs became modules.
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

    private JobBlockTabGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "job_block_tabs_resource_collection", JobBlockTabGameTests::resourceCollection);
            register(helper, "job_block_tabs_craftsperson", JobBlockTabGameTests::craftsperson);
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

    /** The indices of the tabs that show the player inventory. */
    private static List<Integer> inventoryTabs(JobBlockMenu menu) {
        // The first player inventory slot follows the 27 storage slots.
        return menu.tabs().stream().filter(tab -> {
            menu.setActiveTab(tab.index());
            return menu.slots.get(JobBlockEntity.SLOTS).isActive();
        }).map(JobBlockTab::index).toList();
    }

    /** Asserts the tabs of a window without tabs below the panel. */
    private static void assertTabs(GameTestHelper helper, Job job, List<String> keys, List<Item> icons, boolean tall, List<Integer> inventory) {
        JobBlockMenu menu = menu(helper, job);
        String name = job.displayName().getString();
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

    /** Lumberjack, Miner and Fisherman: Storage, Workers, Found. */
    private static void resourceCollection(GameTestHelper helper) {
        for (Job job : List.of(ModJobs.LUMBERJACK.get(), ModJobs.MINER.get(), ModJobs.FISHERMAN.get())) {
            assertTabs(helper, job, List.of(STORAGE, WORKERS, FOUND), List.of(Items.CHEST, Items.NAME_TAG, Items.SPYGLASS), false, List.of(0));
        }
        helper.succeed();
    }

    /**
     * Craftsperson: Storage, Workers, its Patterns tab, Station Types; one Patterns tab per Specialization below the panel,
     * hidden until it is unlocked.
     */
    private static void craftsperson(GameTestHelper helper) {
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        JobBlockMenu menu = menu(helper, craftsperson);
        List<JobBlockTab> top = top(menu);
        helper.assertValueEqual(keys(top), List.of(STORAGE, WORKERS, PATTERNS, STATION_TYPES), "Top tab labels");
        helper.assertValueEqual(icons(top), List.of(Items.CHEST, Items.NAME_TAG, Items.CRAFTING_TABLE, Items.SMITHING_TABLE), "Top tab icons");
        List<JobBlockTab> below = menu.tabs().stream().filter(JobBlockTab::below).toList();
        helper.assertValueEqual(menu.tabs().subList(0, top.size()), top, "The tabs below the panel come last");
        List<Job> specializations = List.of(StationTypeGameTests.job(CivMod.id("gametest_mason")), StationTypeGameTests.job(CivMod.id("smith")));
        helper.assertTrue(menu.patternJobs().containsAll(specializations) && menu.patternJobs().getFirst() == craftsperson,
                "The Craftsperson first, then its Specializations: " + menu.patternJobs());
        helper.assertValueEqual(below.size(), menu.patternJobs().size() - 1, "One tab below per Specialization");
        for (int i = 0; i < below.size(); i++) {
            Job job = menu.patternJobs().get(i + 1);
            helper.assertValueEqual(key(below.get(i).label()), PATTERNS, "Specialization tab label");
            helper.assertTrue(below.get(i).icon().is(job.icon().getItem()), "Specialization tab icon: " + job.icon());
            helper.assertFalse(below.get(i).shown(), "Hidden until unlocked");
        }
        helper.assertTrue(top.stream().allMatch(JobBlockTab::shown), "Storage, Workers, Patterns and Station Types are shown");
        helper.assertValueEqual(menu.getTabCount(), menu.tabs().size(), "Tab count");
        helper.assertTrue(menu.isTall(), "Tall panel");
        List<Integer> inventory = new java.util.ArrayList<>(List.of(0, 2, 3));
        for (int i = 0; i < below.size(); i++) {
            inventory.add(4 + i);
        }
        helper.assertValueEqual(inventoryTabs(menu), inventory, "Inventory on Storage, Patterns and Station Types");
        // A Producing Job without Specializations: no Station Types tab is shown.
        JobBlockMenu mechanic = menu(helper, ModJobs.MECHANIC.get());
        helper.assertValueEqual(keys(mechanic.tabs()), List.of(STORAGE, WORKERS, PATTERNS, STATION_TYPES), "Mechanic tab labels");
        helper.assertFalse(mechanic.tabs().get(3).shown(), "Without Specializations the Station Types tab is hidden");
        helper.succeed();
    }

    /** Clerk: Storage, Workers, Stock, Requests, Deliveries in the tall panel. */
    private static void clerk(GameTestHelper helper) {
        assertTabs(helper, ModJobs.CLERK.get(), List.of(STORAGE, WORKERS, STOCK, REQUESTS, DELIVERIES),
                List.of(Items.CHEST, Items.NAME_TAG, Items.WRITABLE_BOOK, Items.PAPER, Items.BUNDLE), true, List.of(0, 2));
        helper.succeed();
    }

    /** Mission Board: Storage, Workers, Missions, Station Types (tall, with the player inventory). */
    private static void missionBoard(GameTestHelper helper) {
        assertTabs(helper, ModJobs.ADVENTURER.get(), List.of(STORAGE, WORKERS, MISSIONS, STATION_TYPES),
                List.of(Items.CHEST, Items.NAME_TAG, Items.MAP, Items.SMITHING_TABLE), true, List.of(0, 3));
        helper.succeed();
    }

    /** A Service JobBlock and a Logistics JobBlock other than the Clerk: Storage and Workers only. */
    private static void standard(GameTestHelper helper) {
        Job service = ModJobs.REGISTRY.getValue(Identifier.fromNamespaceAndPath(CivMod.MOD_ID, "gametest_no_costs"));
        for (Job job : List.of(service, ModJobs.DELIVERYMAN.get())) {
            assertTabs(helper, job, List.of(STORAGE, WORKERS), List.of(Items.CHEST, Items.NAME_TAG), false, List.of(0));
        }
        helper.succeed();
    }
}
