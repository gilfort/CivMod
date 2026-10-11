package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.command.CivModCommand;
import com.gilfort.civmod.menu.CivMenu;
import com.gilfort.civmod.menu.DebugMenu;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Story 1 (project base and GUI foundation).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FoundationGameTests {

    private FoundationGameTests() {
    }

    /**
     * Registers the test functions referenced by the test instance JSON files.
     *
     * @param event the registry event
     */
    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("debug_command_registered")),
                    (Consumer<GameTestHelper>) FoundationGameTests::debugCommandRegistered);
            helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("menu_tabs_toggle_slots")),
                    (Consumer<GameTestHelper>) FoundationGameTests::menuTabsToggleSlots);
        });
    }

    /**
     * In development environments, {@code /civmod debug gui} exists.
     */
    private static void debugCommandRegistered(GameTestHelper helper) {
        CommandNode<CommandSourceStack> root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot()
                .getChild(CivModCommand.ROOT);
        helper.assertTrue(root != null, "Root command /civmod is missing");
        CommandNode<CommandSourceStack> debug = root.getChild("debug");
        helper.assertTrue(debug != null && debug.getChild("gui") != null, "/civmod debug gui is missing in dev environment");
        helper.succeed();
    }

    /**
     * Tab-bound slots are only active on their tab; tab button clicks switch tabs, invalid ids are rejected.
     */
    private static void menuTabsToggleSlots(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        DebugMenu menu = new DebugMenu(1, player.getInventory());
        helper.assertTrue(menu.getActiveTab() == DebugMenu.TAB_WIDGETS, "Menu should start on the first tab");
        helper.assertFalse(menu.getSlot(0).isActive(), "Debug slot must be hidden on the widgets tab");
        helper.assertFalse(menu.getSlot(DebugMenu.DEBUG_SLOTS).isActive(), "Player inventory must be hidden on the widgets tab");

        helper.assertTrue(menu.clickMenuButton(player, CivMenu.tabButtonId(DebugMenu.TAB_SLOTS)), "Tab button was not accepted");
        helper.assertTrue(menu.getSlot(0).isActive(), "Debug slot must be visible on the slots tab");
        helper.assertTrue(menu.getSlot(DebugMenu.DEBUG_SLOTS).isActive(), "Player inventory must be visible on the slots tab");

        helper.assertFalse(menu.clickMenuButton(player, CivMenu.tabButtonId(menu.getTabCount())), "Out-of-range tab must be rejected");
        helper.assertTrue(menu.getActiveTab() == DebugMenu.TAB_SLOTS, "Invalid tab button changed the tab");
        helper.succeed();
    }
}
