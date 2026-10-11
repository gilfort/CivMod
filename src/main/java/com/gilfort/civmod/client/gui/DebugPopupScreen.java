package com.gilfort.civmod.client.gui;

import com.gilfort.civmod.network.CivNetwork;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Development-only popup showcasing {@link CivScreen}. "Back" asks the server to reopen the debug GUI.
 */
public class DebugPopupScreen extends CivScreen {

    /**
     * Creates the popup.
     */
    public DebugPopupScreen() {
        super(Component.translatable("civmod.gui.debug.popup.title"), 176, 90);
    }

    @Override
    protected void initContent() {
        int buttonWidth = (imageWidth - 2 * CivGui.PADDING - 4) / 2;
        int y = topPos + imageHeight - 28;
        addRenderableWidget(Button.builder(Component.translatable("civmod.gui.debug.popup.back"),
                button -> ClientPacketDistributor.sendToServer(CivNetwork.OpenDebugMenu.INSTANCE))
                .bounds(leftPos + CivGui.PADDING, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(leftPos + CivGui.PADDING + buttonWidth + 4, y, buttonWidth, 20).build());
    }

    @Override
    protected void extractPanelContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.textWithWordWrap(font, Component.translatable("civmod.gui.debug.popup.text"),
                leftPos + CivGui.PADDING, topPos + 20, imageWidth - 2 * CivGui.PADDING, CivGui.LABEL_COLOR, false);
    }
}
