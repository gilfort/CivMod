package com.gilfort.civmod.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Non-interactive progress bar in the style of the vanilla experience bar.
 * <p>
 * Used for EXP, saturation and similar values. The message is used for narration; set a tooltip for details.
 */
public class ProgressBar extends AbstractWidget {

    /** Height of the vanilla experience bar. */
    public static final int HEIGHT = 5;

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier PROGRESS = Identifier.withDefaultNamespace("hud/experience_bar_progress");

    private float progress;

    /**
     * @param x       the left edge
     * @param y       the top edge
     * @param width   the width
     * @param message the narration message
     */
    public ProgressBar(int x, int y, int width, Component message) {
        super(x, y, width, HEIGHT, message);
        this.active = false;
    }

    /**
     * @return the progress between {@code 0} and {@code 1}
     */
    public float getProgress() {
        return progress;
    }

    /**
     * @param progress the progress, clamped to {@code 0..1}
     */
    public void setProgress(float progress) {
        this.progress = Mth.clamp(progress, 0.0F, 1.0F);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, getX(), getY(), width, height);
        int filled = Math.round(width * progress);
        if (filled > 0) {
            graphics.enableScissor(getX(), getY(), getX() + filled, getY() + height);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PROGRESS, getX(), getY(), width, height);
            graphics.disableScissor();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
