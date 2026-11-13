package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModMenuTypes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The window of a Delivery Slip (vault {@code Jobs/Deliveryman.md}): the filter as ghost slots (an item is copied, not
 * taken), the filter mode, the delivery variant and the two optional limits "fill target up to" and "keep at the
 * source". The slip stays in the player's hand and is changed there; this menu only edits its
 * {@link ModDataComponents#DELIVERY_SLIP data component}. The numbers reach the client as menu data.
 */
public final class DeliverySlipMenu extends CivMenu {

    /** Button id: switch between whitelist and blacklist. */
    public static final int BUTTON_MODE = 0;
    /** Button id: next delivery variant. */
    public static final int BUTTON_VARIANT = 1;
    /** Steps of the limit buttons, left to right. */
    public static final int[] LIMIT_STEPS = {-64, -8, -1, 1, 8, 64};
    /** First button id of "fill target up to" (one per step). */
    public static final int BUTTON_FILL = 2;
    /** First button id of "keep at the source" (one per step). */
    public static final int BUTTON_KEEP = BUTTON_FILL + LIMIT_STEPS.length;

    /** Panel height. */
    public static final int HEIGHT = 206;
    private static final int DATA_MODE = 0;
    private static final int DATA_VARIANT = 1;
    private static final int DATA_FILL = 2;
    private static final int DATA_KEEP = 3;
    private static final int DATA_COUNT = 4;
    /** Left edge of the filter slots (panel coordinates). */
    private static final int FILTER_X = 8;
    /** Top edge of the filter slots (panel coordinates). */
    private static final int FILTER_Y = 38;

    private final Player player;
    private final InteractionHand hand;
    private final Container filter = new SimpleContainer(DeliverySlip.FILTER_SLOTS);
    private final ContainerData numbers = new SimpleContainerData(DATA_COUNT);

    /**
     * Client constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param buffer      extra data written by the server: the hand holding the slip (main hand: true)
     */
    public DeliverySlipMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBoolean() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
    }

    /**
     * Server constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param hand        the hand holding the slip (main hand: true)
     */
    public DeliverySlipMenu(int containerId, Inventory inventory, InteractionHand hand) {
        super(ModMenuTypes.DELIVERY_SLIP.get(), containerId, 1);
        this.player = inventory.player;
        this.hand = hand;
        for (int slot = 0; slot < DeliverySlip.FILTER_SLOTS; slot++) {
            addTabSlot(filter, slot, FILTER_X + slot * 18, FILTER_Y, ALL_TABS);
        }
        addPlayerInventory(inventory, 8, HEIGHT - 82, ALL_TABS);
        addDataSlots(numbers);
        readSlip();
    }

    /**
     * @return the slip in the player's hand, empty once the player holds something else
     */
    public Optional<DeliverySlip> slip() {
        return Optional.ofNullable(player.getItemInHand(hand).get(ModDataComponents.DELIVERY_SLIP.get()));
    }

    /**
     * @return whether the filter is a blacklist
     */
    public boolean blacklist() {
        return numbers.get(DATA_MODE) == 1;
    }

    /**
     * @return the delivery variant
     */
    public DeliverySlip.Variant variant() {
        return DeliverySlip.Variant.values()[Math.clamp(numbers.get(DATA_VARIANT), 0, DeliverySlip.Variant.values().length - 1)];
    }

    /**
     * @return the "fill target up to" limit, 0: none
     */
    public int fillTo() {
        return numbers.get(DATA_FILL);
    }

    /**
     * @return how many of an item stay at the source, 0: none
     */
    public int keep() {
        return numbers.get(DATA_KEEP);
    }

    /** Shows the slip's data: the filter in the ghost slots and the numbers. */
    private void readSlip() {
        DeliverySlip slip = slip().orElse(DeliverySlip.blank());
        for (int slot = 0; slot < DeliverySlip.FILTER_SLOTS; slot++) {
            filter.setItem(slot, slot < slip.filter().size() ? new ItemStack(slip.filter().get(slot)) : ItemStack.EMPTY);
        }
        numbers.set(DATA_MODE, slip.blacklist() ? 1 : 0);
        numbers.set(DATA_VARIANT, slip.variant().ordinal());
        numbers.set(DATA_FILL, slip.fillTo());
        numbers.set(DATA_KEEP, slip.keep());
    }

    private void edit(UnaryOperator<DeliverySlip> change) {
        ItemStack held = player.getItemInHand(hand);
        DeliverySlip slip = held.get(ModDataComponents.DELIVERY_SLIP.get());
        if (slip != null) {
            held.set(ModDataComponents.DELIVERY_SLIP.get(), change.apply(slip));
        }
    }

    @Override
    public boolean clickMenuButton(Player clicker, int buttonId) {
        if (buttonId == BUTTON_MODE) {
            edit(DeliverySlip::withBlacklistToggled);
        } else if (buttonId == BUTTON_VARIANT) {
            edit(slip -> slip.withVariant(slip.variant().next()));
        } else if (buttonId >= BUTTON_FILL && buttonId < BUTTON_KEEP) {
            int step = LIMIT_STEPS[buttonId - BUTTON_FILL];
            edit(slip -> slip.withFillTo(slip.fillTo() + step));
        } else if (buttonId >= BUTTON_KEEP && buttonId < BUTTON_KEEP + LIMIT_STEPS.length) {
            int step = LIMIT_STEPS[buttonId - BUTTON_KEEP];
            edit(slip -> slip.withKeep(slip.keep() + step));
        } else {
            return super.clickMenuButton(clicker, buttonId);
        }
        readSlip();
        return true;
    }

    /** A click on a filter slot copies the carried item into it (one of it, the stack is not taken) or empties it. */
    @Override
    public void clicked(int slotId, int button, ContainerInput containerInput, Player clicker) {
        if (slotId < 0 || slotId >= DeliverySlip.FILTER_SLOTS) {
            super.clicked(slotId, button, containerInput, clicker);
            return;
        }
        ItemStack carried = getCarried();
        filter.setItem(slotId, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
        List<Item> items = new ArrayList<>();
        for (int slot = 0; slot < DeliverySlip.FILTER_SLOTS; slot++) {
            Item item = filter.getItem(slot).getItem();
            if (!filter.getItem(slot).isEmpty() && !items.contains(item)) {
                items.add(item);
            }
        }
        edit(slip -> slip.withFilter(items));
        readSlip();
    }

    /** Nothing moves by shift-click: the player inventory is only there to pick items from. */
    @Override
    public ItemStack quickMoveStack(Player clicker, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player clicker) {
        return slip().isPresent();
    }
}
