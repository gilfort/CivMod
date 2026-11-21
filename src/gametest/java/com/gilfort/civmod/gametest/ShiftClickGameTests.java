package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.menu.DeliveryOrdersTab;
import com.gilfort.civmod.menu.DeliverySlipMenu;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StationTypesTab;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DeliveryOrders;
import com.gilfort.civmod.settlement.StationTypes;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Shift + left click in the JobBlock tabs and the Delivery Slip (spec #294, ticket #289): the click goes
 * to the slot of the open tab only, and the Station Types tab says which inventory items fit.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ShiftClickGameTests {

    /** Inventory slot (container index) the tests put their item into. */
    private static final int INVENTORY_SLOT = 10;

    private ShiftClickGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "shift_click_station_types", ShiftClickGameTests::stationTypes);
            register(helper, "shift_click_other_tab", ShiftClickGameTests::otherTab);
            register(helper, "shift_click_slip_filter", ShiftClickGameTests::slipFilter);
            register(helper, "shift_click_station_types_fits", ShiftClickGameTests::fits);
            register(helper, "shift_click_clerk_orders", ShiftClickGameTests::clerkOrders);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static int menuSlot(AbstractContainerMenu menu, Player player) {
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && slot.getContainerSlot() == INVENTORY_SLOT) {
                return slot.index;
            }
        }
        throw new IllegalStateException("No menu slot for inventory slot " + INVENTORY_SLOT);
    }

    /** With the Station Types tab open an unlock item goes into the free slot; an item that unlocks nothing stays. */
    private static void stationTypes(GameTestHelper helper) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, player, pos);
        menu.setActiveTab(menu.tab(StationTypesTab.class).orElseThrow().index());
        int slot = menuSlot(menu, player);

        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.DIRT, 4));
        menu.quickMoveStack(player, slot);
        helper.assertTrue(StationTypeGameTests.block(helper.getLevel(), pos).stationTypes().getFirst() == Items.AIR
                && player.getInventory().getItem(INVENTORY_SLOT).getCount() == 4, "Dirt unlocks nothing: refused, stack kept");

        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.STONECUTTER, 2));
        menu.quickMoveStack(player, slot);
        helper.assertTrue(StationTypeGameTests.block(helper.getLevel(), pos).stationTypes().getFirst() == Items.STONECUTTER,
                "The stonecutter is in the first slot");
        helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).getCount() == 1, "One stonecutter consumed");
        helper.succeed();
    }

    /** The same click with another tab open does not touch the Station Types slot. */
    private static void otherTab(GameTestHelper helper) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, player, pos);
        helper.assertTrue(!(menu.activeTab() instanceof StationTypesTab), "The window opens on another tab");
        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.STONECUTTER, 2));
        menu.quickMoveStack(player, menuSlot(menu, player));
        helper.assertTrue(StationTypeGameTests.block(helper.getLevel(), pos).stationTypes().getFirst() == Items.AIR
                && player.getInventory().getItem(INVENTORY_SLOT).getCount() == 2, "Nothing moved");
        helper.succeed();
    }

    /** A shift click sets a ghost entry in the first empty filter slot and keeps the stack; the same item twice adds nothing. */
    private static void slipFilter(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack slip = new ItemStack(ModItems.DELIVERY_SLIP.get());
        slip.set(ModDataComponents.DELIVERY_SLIP.get(), DeliverySlip.blank());
        player.setItemInHand(InteractionHand.MAIN_HAND, slip);
        DeliverySlipMenu menu = new DeliverySlipMenu(1, player.getInventory(), InteractionHand.MAIN_HAND);
        int slot = menuSlot(menu, player);

        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.COAL, 16));
        menu.quickMoveStack(player, slot);
        menu.quickMoveStack(player, slot);
        helper.assertTrue(menu.slip().orElseThrow().filter().equals(List.of(ItemKey.of(new ItemStack(Items.COAL)))),
                "One coal entry: " + menu.slip().orElseThrow().filter());
        helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).getCount() == 16, "The stack is kept");

        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.IRON_INGOT));
        menu.quickMoveStack(player, slot);
        helper.assertTrue(menu.slip().orElseThrow().filter().size() == 2, "A second item takes the next slot");
        helper.succeed();
    }

    /**
     * The Clerk's Delivery Orders tab (#299): a filled slip goes into the first free slot in use, anything else stays, a
     * full tab keeps the slip in the inventory, and a click on a slip slot gives the slip back to the inventory.
     */
    private static void clerkOrders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = new JobBlockMenu(1, player.getInventory(), (JobBlockEntity) level.getBlockEntity(clerk), JobBlockInfo.of(ModJobs.CLERK.get(), 1));
        DeliveryOrdersTab tab = menu.tab(DeliveryOrdersTab.class).orElseThrow();
        menu.setActiveTab(tab.index());
        int inUse = tab.slots();
        List<Slot> orders = menu.slots.stream().filter(slot -> slot.container.getContainerSize() == DeliveryOrders.MAX_SLOTS).toList();
        int inventorySlot = menuSlot(menu, player);

        ItemStack first = filledSlip(clerk, 1);
        player.getInventory().setItem(INVENTORY_SLOT, first.copy());
        menu.quickMoveStack(player, inventorySlot);
        helper.assertTrue(sameSlip(orders.get(0).getItem(), first) && player.getInventory().getItem(INVENTORY_SLOT).isEmpty(),
                "A filled slip goes into the first slip slot");
        player.getInventory().setItem(INVENTORY_SLOT, filledSlip(clerk, 2));
        menu.quickMoveStack(player, inventorySlot);
        helper.assertTrue(orders.get(1).hasItem() && !orders.get(2).hasItem(), "The next slip takes the next free slot");

        ItemStack blank = new ItemStack(ModItems.DELIVERY_SLIP.get());
        blank.set(ModDataComponents.DELIVERY_SLIP.get(), DeliverySlip.blank());
        for (ItemStack other : List.of(new ItemStack(Items.PAPER), blank)) {
            player.getInventory().setItem(INVENTORY_SLOT, other);
            menu.quickMoveStack(player, inventorySlot);
            helper.assertTrue(!orders.get(2).hasItem() && player.getInventory().getItem(INVENTORY_SLOT) == other,
                    "Not a filled slip, stays in the inventory: " + other);
        }

        for (int slot = 2; slot < inUse; slot++) {
            orders.get(slot).set(filledSlip(clerk, slot + 1));
        }
        ItemStack extra = filledSlip(clerk, inUse + 1);
        player.getInventory().setItem(INVENTORY_SLOT, extra.copy());
        menu.quickMoveStack(player, inventorySlot);
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(INVENTORY_SLOT), extra) && !orders.get(inUse).hasItem(),
                "Every slot in use taken: the slip stays in the inventory");

        player.getInventory().setItem(INVENTORY_SLOT, ItemStack.EMPTY);
        menu.quickMoveStack(player, orders.get(0).index);
        helper.assertTrue(!orders.get(0).hasItem() && slipsInInventory(player, first) == 1, "A click on a slip slot gives the slip back");

        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        menu.quickMoveStack(player, orders.get(1).index);
        helper.assertTrue(orders.get(1).hasItem(), "A full inventory keeps the slip in its slot");
        helper.succeed();
    }

    /** A slip with a source and a target, so it counts as an order; {@code seed} makes it differ from the others. */
    private static ItemStack filledSlip(BlockPos source, int seed) {
        DeliverySlip slip = new DeliverySlip(new UUID(0L, seed), Optional.of(source), List.of(source.above(seed)), false, List.of(),
                DeliverySlip.Variant.NEAREST, 0, 0, DeliverySlip.Status.OK);
        ItemStack stack = new ItemStack(ModItems.DELIVERY_SLIP.get());
        stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip);
        return stack;
    }

    /** Whether both stacks hold the same slip; the Clerk may update a slip's status, so only the id counts. */
    private static boolean sameSlip(ItemStack stack, ItemStack slip) {
        return DeliveryOrders.slip(stack).map(DeliverySlip::id).equals(DeliveryOrders.slip(slip).map(DeliverySlip::id));
    }

    private static int slipsInInventory(Player player, ItemStack slip) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (sameSlip(player.getInventory().getItem(slot), slip)) {
                count++;
            }
        }
        return count;
    }

    /** The Station Types tab accepts the unlock items that are not in a slot yet, and nothing else. */
    private static void fits(GameTestHelper helper) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, player, pos);
        StationTypesTab tab = menu.tab(StationTypesTab.class).orElseThrow();
        tab.data().set(StationTypesTabData.of(helper.getLevel(), pos));
        var accepts = tab.accepts().orElseThrow();
        helper.assertTrue(accepts.test(new ItemStack(Items.STONECUTTER)) && !accepts.test(new ItemStack(Items.DIRT))
                && !accepts.test(new ItemStack(Items.CRAFTING_TABLE)), "Only unlock items fit");
        helper.assertTrue(StationTypes.put(helper.getLevel(), pos, 0, new ItemStack(Items.STONECUTTER)) == StationTypes.PutResult.UNLOCKED, "Unlocked");
        tab.data().set(StationTypesTabData.of(helper.getLevel(), pos));
        helper.assertFalse(tab.accepts().orElseThrow().test(new ItemStack(Items.STONECUTTER)), "An item in a slot no longer fits");
        helper.assertTrue(tab.data().get().firstFreeSlot().isEmpty() && !tab.accepts().orElseThrow().test(new ItemStack(Items.SMITHING_TABLE)),
                "With the only open slot full, no item fits: " + tab.data().get().firstFreeSlot());
        helper.assertTrue(menu.tabs().stream().filter(t -> t.accepts().isPresent()).count() == 1, "Only tabs where not everything fits take part");
        helper.succeed();
    }
}
