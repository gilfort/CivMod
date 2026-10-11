package com.gilfort.civmod.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base class for CivMod screens without a container (popups, selections): a centred vanilla-style panel with a
 * title.
 */
public abstract class CivScreen extends Screen {

    /** Panel width. */
    protected final int imageWidth;
    /** Panel height. */
    protected final int imageHeight;
    /** Left edge of the panel, set in {@link #init()}. */
    protected int leftPos;
    /** Top edge of the panel, set in {@link #init()}. */
    protected int topPos;

    /**
     * @param title       the title
     * @param imageWidth  the panel width
     * @param imageHeight the panel height
     */
    protected CivScreen(Component title, int imageWidth, int imageHeight) {
        super(title);
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    @Override
    protected void init() {
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight) / 2;
        initContent();
    }

    /**
     * Adds the widgets of the screen; called from {@link #init()} after the layout is known.
     */
    protected abstract void initContent();

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        CivGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        graphics.text(font, title, leftPos + CivGui.PADDING, topPos + 6, CivGui.LABEL_COLOR, false);
        extractPanelContent(graphics, mouseX, mouseY);
    }

    /**
     * Draws static panel content below the widgets (absolute coordinates).
     *
     * @param graphics the graphics extractor
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     */
    protected void extractPanelContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
