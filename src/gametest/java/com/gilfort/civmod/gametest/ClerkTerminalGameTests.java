package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ClerkTerminal;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.StorageTerminal;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * GameTests for the Clerk's storage terminal (#174): the list, taking and storing, the shift, the pause of the rule
 * checks and the deltas.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkTerminalGameTests {

    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private ClerkTerminalGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "storage_terminal_entries", ClerkTerminalGameTests::entries);
            register(helper, "storage_terminal_use", ClerkTerminalGameTests::use);
            register(helper, "storage_terminal_closed", ClerkTerminalGameTests::closed);
            register(helper, "storage_terminal_pauses_checks", ClerkTerminalGameTests::pausesChecks);
            register(helper, "storage_terminal_delta", ClerkTerminalGameTests::delta);
            register(helper, "storage_terminal_amount_text", ClerkTerminalGameTests::amountText);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Container chest(GameTestHelper helper) {
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
    }

    private static ItemStack named(String name, int count) {
        ItemStack stack = new ItemStack(Items.STICK, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    /** Opens the Storage tab of the Clerk for the viewer and returns the first sync. */
    private static StorageTerminal.Sync open(GameTestHelper helper, StorageTerminal terminal, BlockPos clerk, long dayTime) {
        return terminal.update(helper.getLevel(), VIEWER, Optional.of(clerk), true, 1, dayTime).orElseThrow();
    }

    /** Stacks with different components are separate entries; the same resource in several slots adds up. */
    private static void entries(GameTestHelper helper) {
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Container chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.STICK, 3));
        chest.setItem(1, new ItemStack(Items.STICK, 4));
        chest.setItem(2, named("Wand", 2));
        StorageTerminal terminal = new StorageTerminal(true);
        StorageTerminal.Sync sync = open(helper, terminal, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(sync.full() && sync.open(), "The first sync is the full list of a Clerk on shift");
        helper.assertTrue(sync.entries().size() == 2, "A plain and a named stick are two entries: " + sync.entries().size());
        long plain = sync.entries().stream().filter(entry -> !entry.icon().has(DataComponents.CUSTOM_NAME)).mapToLong(StorageTerminal.Entry::amount).sum();
        helper.assertTrue(plain == 7, "Two slots of plain sticks add up: " + plain);
        // Leaving the tab and coming back at once sends the full list at once.
        terminal.update(helper.getLevel(), VIEWER, Optional.of(clerk), false, 1, ClerkGameTests.MORNING);
        StorageTerminal.Sync again = terminal.update(helper.getLevel(), VIEWER, Optional.of(clerk), true, 1, ClerkGameTests.MORNING).orElse(null);
        helper.assertTrue(again != null && again.full() && again.entries().size() == 2, "Reopening sends the full list again");
        terminal.stop(VIEWER);
        helper.succeed();
    }

    /** Take a stack, half a stack, store one and all; what does not fit stays with the player. */
    private static void use(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Container chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(1, new ItemStack(Items.COBBLESTONE, 36));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        StorageTerminal terminal = new StorageTerminal(true);
        open(helper, terminal, clerk, ClerkGameTests.MORNING);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE);

        terminal.click(player, level, ClerkGameTests.MORNING, cobble, StorageTerminal.Action.TAKE_STACK);
        helper.assertTrue(player.containerMenu.getCarried().getCount() == 64, "A left click takes a stack: " + player.containerMenu.getCarried());
        terminal.click(player, level, ClerkGameTests.MORNING, cobble, StorageTerminal.Action.TAKE_STACK);
        helper.assertTrue(player.containerMenu.getCarried().getCount() == 64, "With an item on the cursor nothing is taken");

        terminal.click(player, level, ClerkGameTests.MORNING, ItemStack.EMPTY, StorageTerminal.Action.STORE_ONE);
        helper.assertTrue(player.containerMenu.getCarried().getCount() == 63, "A right click with an item stores one");
        terminal.click(player, level, ClerkGameTests.MORNING, ItemStack.EMPTY, StorageTerminal.Action.STORE);
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "A click with an item stores it all");
        // Storing follows the delivery order: the block's own slots first.
        helper.assertTrue(ClerkStorage.stock(level, clerk, true).count(Items.COBBLESTONE) == 100, "Everything is back");

        terminal.click(player, level, ClerkGameTests.MORNING, cobble, StorageTerminal.Action.TAKE_HALF);
        helper.assertTrue(player.containerMenu.getCarried().getCount() == 32, "A right click takes half a stack");
        player.containerMenu.setCarried(ItemStack.EMPTY);

        terminal.click(player, level, ClerkGameTests.MORNING, cobble, StorageTerminal.Action.TAKE_TO_INVENTORY);
        helper.assertTrue(player.getInventory().countItem(Items.COBBLESTONE) == 64, "A shift-click moves a stack into the inventory");
        terminal.stop(VIEWER);
        helper.succeed();
    }

    /** Outside the Clerk's shift the list stays but is closed; clicks do nothing, and the flag flips when the shift ends. */
    private static void closed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        chest(helper).setItem(0, new ItemStack(Items.BREAD, 5));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        StorageTerminal terminal = new StorageTerminal(true);
        helper.assertTrue(open(helper, terminal, clerk, ClerkGameTests.MORNING).open(), "On shift: open");

        // The next sync is due after a second: the ticks before it send nothing.
        for (int i = 0; i < StorageTerminal.SYNC_INTERVAL - 1; i++) {
            terminal.update(level, VIEWER, Optional.of(clerk), true, 1, ClerkGameTests.EVENING);
        }
        StorageTerminal.Sync evening = terminal.update(level, VIEWER, Optional.of(clerk), true, 1, ClerkGameTests.EVENING).orElse(null);
        helper.assertTrue(evening != null && !evening.open() && !terminal.onShift(), "The shift ended: the closed state is sent");
        terminal.click(player, level, ClerkGameTests.EVENING, new ItemStack(Items.BREAD), StorageTerminal.Action.TAKE_STACK);
        helper.assertTrue(player.containerMenu.getCarried().isEmpty() && count(chest(helper), Items.BREAD) == 5, "Closed: nothing is taken");
        helper.assertTrue(terminal.usable(level, ClerkGameTests.EVENING).isEmpty(), "Closed: no usable storage");
        terminal.stop(VIEWER);
        helper.succeed();
    }

    /** An open Storage tab pauses the Clerk's rule checks; closing it checks once. */
    private static void pausesChecks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.TORCH, 10, 0)), "Rule set");

        ClerkTerminal.open(level, clerk, VIEWER);
        helper.assertTrue(ClerkTerminal.viewed(level, clerk), "Viewed");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Open tab: no request");

        ClerkTerminal.close(level, clerk, VIEWER);
        helper.assertFalse(ClerkTerminal.viewed(level, clerk), "Not viewed any more");
        helper.assertTrue(Requests.byPriority(settlement).size() == 1, "Closing checks once: " + Requests.byPriority(settlement));
        helper.succeed();
    }

    /** After the full list only what changed is sent, and a vanished item is sent with amount 0. */
    private static void delta(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Container chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.BREAD, 5));
        chest.setItem(1, new ItemStack(Items.APPLE, 2));
        StorageTerminal terminal = new StorageTerminal(true);
        open(helper, terminal, clerk, ClerkGameTests.MORNING);

        chest.setItem(0, new ItemStack(Items.BREAD, 9));
        chest.setItem(1, ItemStack.EMPTY);
        chest.setItem(2, new ItemStack(Items.CARROT, 1));
        List<StorageTerminal.Entry> changed = null;
        for (int i = 0; i <= StorageTerminal.SYNC_INTERVAL && changed == null; i++) {
            Optional<StorageTerminal.Sync> sync = terminal.update(level, VIEWER, Optional.of(clerk), true, 1, ClerkGameTests.MORNING);
            changed = sync.map(StorageTerminal.Sync::entries).orElse(null);
            helper.assertTrue(sync.isEmpty() || !sync.get().full(), "A later sync is a delta");
        }
        helper.assertTrue(changed != null && changed.size() == 3, "Bread changed, apple gone, carrot new: " + changed);
        helper.assertTrue(changed.stream().anyMatch(entry -> entry.icon().is(Items.APPLE) && entry.amount() == 0L), "The apple is gone");
        helper.assertTrue(changed.stream().noneMatch(entry -> entry.icon().is(Items.COBBLESTONE)), "Unchanged items are not sent");
        helper.assertTrue(terminal.entries().get(ItemResource.of(new ItemStack(Items.BREAD))) == 9L, "The sent state is kept");
        terminal.stop(VIEWER);
        helper.succeed();
    }

    /** Amounts below 1,000 as they are, then "1.2k", "12k", "1.2M". */
    private static void amountText(GameTestHelper helper) {
        helper.assertValueEqual(StorageTerminal.shortAmount(7L), "7", "7");
        helper.assertValueEqual(StorageTerminal.shortAmount(999L), "999", "999");
        helper.assertValueEqual(StorageTerminal.shortAmount(1000L), "1k", "1,000");
        helper.assertValueEqual(StorageTerminal.shortAmount(1234L), "1.2k", "1,234");
        helper.assertValueEqual(StorageTerminal.shortAmount(12345L), "12k", "12,345");
        helper.assertValueEqual(StorageTerminal.shortAmount(1_234_567L), "1.2M", "1,234,567");
        helper.succeed();
    }

    private static int count(Container container, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                count += container.getItem(i).getCount();
            }
        }
        return count;
    }
}
