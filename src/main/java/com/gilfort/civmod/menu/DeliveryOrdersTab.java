package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.settlement.DeliveryOrders;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Clerk's "Delivery Orders" tab (vault {@code Jobs/Deliveryman.md}): slots for filled Delivery Slips, {@code (level +
 * Ledgers) x 9} of them in use, with the player inventory to take slips from. The slips are standing orders and are not
 * consumed; the slot order, top left to bottom right, is their priority. One button switches "Auto" of the Deliverymen
 * of this Warehouse. The slot count and the switch reach the client as menu data; the slips themselves are slot items.
 */
public final class DeliveryOrdersTab extends JobBlockTab {

    /** Panel x of the first slot. */
    static final int SLOTS_X = 8;
    /** Panel y of the first slot. */
    static final int SLOTS_Y = 36;
    /** Slots per row. */
    static final int COLUMNS = 9;

    private static final int DATA_SLOTS = 0;
    private static final int DATA_AUTO = 1;
    private static final int DATA_COUNT = 2;
    private static final int BUTTON_AUTO = 0;

    private final ContainerData data = new SimpleContainerData(DATA_COUNT);

    DeliveryOrdersTab() {
    }

    /** A slot of the tab: only the slots in use are shown, and only a filled slip goes in. */
    private final class OrderSlot extends CivSlot {

        OrderSlot(Container container, int index, int x, int y, int tab) {
            super(menu(), container, index, x, y, tab);
        }

        @Override
        public boolean isActive() {
            return super.isActive() && getContainerSlot() < slots();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return getContainerSlot() < slots() && DeliveryOrders.slip(stack).isPresent();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /**
     * @param container the slips: the Clerk's orders on the server, a plain container on the client
     * @return the slots of the tab, in priority order
     */
    List<Slot> createSlots(Container container) {
        List<Slot> slots = new ArrayList<>();
        for (int slot = 0; slot < DeliveryOrders.MAX_SLOTS; slot++) {
            slots.add(new OrderSlot(container, slot, SLOTS_X + slot % COLUMNS * 18, SLOTS_Y + slot / COLUMNS * 18, index()));
        }
        return slots;
    }

    /**
     * @return a container for the client, which only mirrors what the server sends
     */
    static Container clientContainer() {
        return new SimpleContainer(DeliveryOrders.MAX_SLOTS) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.clerk.tab.orders");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(ModItems.DELIVERY_SLIP.get());
    }

    @Override
    boolean tall() {
        return true;
    }

    @Override
    boolean showsInventory() {
        return true;
    }

    @Override
    int buttons() {
        return 1;
    }

    @Override
    boolean clickButton(Player player, int button) {
        if (button != BUTTON_AUTO) {
            return false;
        }
        menu().editSettlement((settlementData, pos) -> settlementData.settlementAt(pos).flatMap(settlement -> settlement.block(pos)).ifPresent(block -> {
            block.orders().setAuto(!block.orders().auto());
            settlementData.setDirty();
        }));
        return true;
    }

    /**
     * Server: reads the slot count and the "Auto" switch of the Clerk into the menu data.
     *
     * @param block the Clerk JobBlock of the window
     */
    void refresh(SettlementBlock block) {
        data.set(DATA_SLOTS, block.orderSlots());
        data.set(DATA_AUTO, block.orders().auto() ? 1 : 0);
    }

    /**
     * @return the menu data to register with the menu
     */
    ContainerData data() {
        return data;
    }

    /**
     * @return how many slots are in use: (level + Ledgers) × 9
     */
    public int slots() {
        return data.get(DATA_SLOTS);
    }

    /**
     * @return whether the Deliverymen of this Warehouse also take the automatic tasks
     */
    public boolean auto() {
        return data.get(DATA_AUTO) == 1;
    }

    /**
     * @return the menu button id of the "Auto" switch
     */
    public int autoButtonId() {
        return buttonId(BUTTON_AUTO);
    }
}
