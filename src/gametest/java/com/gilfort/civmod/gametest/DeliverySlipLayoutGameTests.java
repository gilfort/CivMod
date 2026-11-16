package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.menu.DeliverySlipMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the layout of the Delivery Slip window (spec "Player UX", story #291): the rows of the window sit below
 * each other and none reaches the "Inventory" label or the first slots.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DeliverySlipLayoutGameTests {

    /** Height of the "Inventory" label's text line. */
    private static final int LABEL_HEIGHT = 9;

    private DeliverySlipLayoutGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> register(helper, "delivery_slip_layout_no_overlap", DeliverySlipLayoutGameTests::noOverlap));
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** From the filter down to the inventory, each row ends above the top of the next element. */
    private static void noOverlap(GameTestHelper helper) {
        int filterBottom = DeliverySlipMenu.FILTER_Y + DeliverySlipMenu.SLOT_SIZE;
        int fillBottom = DeliverySlipMenu.FILL_Y + DeliverySlipMenu.STEP_ROW_OFFSET + DeliverySlipMenu.STEP_BUTTON_HEIGHT;
        int keepBottom = DeliverySlipMenu.KEEP_Y + DeliverySlipMenu.STEP_ROW_OFFSET + DeliverySlipMenu.STEP_BUTTON_HEIGHT;
        int firstSlotTop = DeliverySlipMenu.HEIGHT - 82;
        helper.assertTrue(filterBottom <= DeliverySlipMenu.FILL_Y, "The fill row starts below the filter: " + filterBottom + " / " + DeliverySlipMenu.FILL_Y);
        helper.assertTrue(fillBottom <= DeliverySlipMenu.KEEP_Y, "The keep row starts below the fill buttons: " + fillBottom + " / " + DeliverySlipMenu.KEEP_Y);
        helper.assertTrue(keepBottom <= DeliverySlipMenu.INVENTORY_LABEL_Y, "The keep buttons end above the Inventory label: " + keepBottom
                + " / " + DeliverySlipMenu.INVENTORY_LABEL_Y);
        helper.assertTrue(DeliverySlipMenu.INVENTORY_LABEL_Y + LABEL_HEIGHT <= firstSlotTop, "The label sits above the first slots");
        helper.succeed();
    }
}
