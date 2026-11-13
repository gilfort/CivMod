package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.menu.CivMenu;
import com.gilfort.civmod.menu.DeliverySlipMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The window of a Delivery Slip (vault {@code Jobs/Deliveryman.md}); the client side of {@link DeliverySlipMenu}: the
 * filter as ghost slots with the mode button, the variant button and the two limits with their step buttons.
 */
public final class DeliverySlipScreen extends CivContainerScreen<DeliverySlipMenu> {

    private static final int BUTTON_HEIGHT = 18;
    private static final int STEP_BUTTON_WIDTH = 24;
    private static final int STEP_BUTTON_HEIGHT = 14;
    private static final int STEP_GAP = 2;
    /** Panel y of the first row of buttons. */
    private static final int CONTENT_TOP = 17;
    /** Panel y of the step buttons below the label of their limit. */
    private static final int STEP_ROW_OFFSET = 12;
    /** Pixels the variant name keeps free inside its button. */
    private static final int LABEL_MARGIN = 8;
    /** Top of the "fill target up to" row (panel coordinates): its text line, the buttons follow below. */
    private static final int FILL_Y = 62;
    /** Top of the "keep at the source" row (panel coordinates). */
    private static final int KEEP_Y = 96;
    private static final int MODE_WIDTH = 72;

    private Button mode;
    private Button variant;

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public DeliverySlipScreen(DeliverySlipMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, DeliverySlipMenu.HEIGHT, List.of());
    }

    @Override
    protected void initContent() {
        int x = leftPos + CivGui.PADDING;
        int y = topPos + CONTENT_TOP;
        mode = addTabWidget(CivMenu.ALL_TABS, Button.builder(Component.empty(), button -> press(DeliverySlipMenu.BUTTON_MODE))
                .bounds(x, y, MODE_WIDTH, BUTTON_HEIGHT).build());
        int variantX = x + MODE_WIDTH + STEP_GAP;
        variant = addTabWidget(CivMenu.ALL_TABS, Button.builder(Component.empty(), button -> press(DeliverySlipMenu.BUTTON_VARIANT))
                .bounds(variantX, y, imageWidth - 2 * CivGui.PADDING - MODE_WIDTH - STEP_GAP, BUTTON_HEIGHT).build());
        addSteps(DeliverySlipMenu.BUTTON_FILL, FILL_Y + STEP_ROW_OFFSET);
        addSteps(DeliverySlipMenu.BUTTON_KEEP, KEEP_Y + STEP_ROW_OFFSET);
    }

    private void addSteps(int firstButton, int panelY) {
        int x = leftPos + (imageWidth - (DeliverySlipMenu.LIMIT_STEPS.length * (STEP_BUTTON_WIDTH + STEP_GAP) - STEP_GAP)) / 2;
        for (int step = 0; step < DeliverySlipMenu.LIMIT_STEPS.length; step++) {
            int amount = DeliverySlipMenu.LIMIT_STEPS[step];
            int id = firstButton + step;
            addTabWidget(CivMenu.ALL_TABS, Button.builder(Component.literal(amount > 0 ? "+" + amount : String.valueOf(amount)), button -> press(id))
                    .bounds(x + step * (STEP_BUTTON_WIDTH + STEP_GAP), topPos + panelY, STEP_BUTTON_WIDTH, STEP_BUTTON_HEIGHT).build());
        }
    }

    private void press(int buttonId) {
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        mode.setMessage(Component.translatable(menu.blacklist() ? "civmod.gui.slip.blacklist" : "civmod.gui.slip.whitelist"));
        mode.setTooltip(Tooltip.create(Component.translatable("civmod.gui.slip.mode")));
        Component name = Component.translatable("civmod.gui.slip.variant." + menu.variant().getSerializedName());
        variant.setMessage(CivGui.fit(font, name, variant.getWidth() - LABEL_MARGIN));
        variant.setTooltip(Tooltip.create(Component.translatable("civmod.gui.slip.variant.tooltip", name)));
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        graphics.text(font, limit("civmod.gui.slip.fill", menu.fillTo()), CivGui.PADDING, FILL_Y, CivGui.LABEL_COLOR, false);
        graphics.text(font, limit("civmod.gui.slip.keep", menu.keep()), CivGui.PADDING, KEEP_Y, CivGui.LABEL_COLOR, false);
    }

    /** "Fill target up to: 64", or "off" for 0. */
    private static Component limit(String key, int value) {
        return Component.translatable(key, value == 0 ? Component.translatable("civmod.gui.slip.off") : Component.literal(String.valueOf(value)));
    }

    @Override
    protected List<Component> helpLines(int tab) {
        return List.of(Component.translatable("civmod.gui.slip.help.filter"), Component.translatable("civmod.gui.slip.help.filter_clear"),
                Component.translatable("civmod.gui.slip.help.source"),
                Component.translatable("civmod.gui.slip.help.target"), Component.translatable("civmod.gui.slip.help.orders"),
                Component.translatable("civmod.gui.slip.help.limits", DeliverySlip.MAX_LIMIT));
    }
}
