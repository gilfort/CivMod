package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.item.JobPicker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The replace window (vault {@code Systems/Scroll of Assignment.md}): opens instead of an error when the player picks a
 * Job whose places are all taken. Lists every settler holding the Job; "⇄" behind a holder swaps at once. Mouseover on
 * a holder shows all of its Jobs.
 */
public class JobReplaceScreen extends CivScreen {

    private static final int WIDTH = 176;
    private static final int TOP = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int SWAP_WIDTH = 24;
    private static final int BUTTON_HEIGHT = 20;
    /** Space below the last row. */
    private static final int BOTTOM_MARGIN = 6;

    private final JobPicker.OpenReplace replace;
    /** The title wrapped to the panel width: the settler's name comes last, so it must not be cut. */
    private final List<FormattedCharSequence> titleLines;

    private JobReplaceScreen(JobPicker.OpenReplace replace) {
        this(replace, Component.translatable("civmod.gui.job_replace.title", replace.settlerName(), replace.job().displayName()));
    }

    private JobReplaceScreen(JobPicker.OpenReplace replace, Component title) {
        this(replace, title, Minecraft.getInstance().font.split(title, WIDTH - 2 * CivGui.PADDING));
    }

    private JobReplaceScreen(JobPicker.OpenReplace replace, Component title, List<FormattedCharSequence> titleLines) {
        super(title, WIDTH, TOP + (titleLines.size() - 1) * Minecraft.getInstance().font.lineHeight + replace.holders().size() * ROW_HEIGHT + BOTTOM_MARGIN);
        this.replace = replace;
        this.titleLines = titleLines;
    }

    /** Draws the title over as many lines as it needs; the rows and the panel height make room for them. */
    @Override
    protected void extractTitle(GuiGraphicsExtractor graphics) {
        for (int line = 0; line < titleLines.size(); line++) {
            graphics.text(font, titleLines.get(line), leftPos + CivGui.PADDING, topPos + TITLE_Y + line * font.lineHeight, CivGui.LABEL_COLOR, false);
        }
    }

    /**
     * Opens the replace window (client payload handler).
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handle(JobPicker.OpenReplace payload, IPayloadContext context) {
        Minecraft.getInstance().gui.setScreen(new JobReplaceScreen(payload));
    }

    @Override
    protected void initContent() {
        int nameWidth = imageWidth - 2 * CivGui.PADDING - SWAP_WIDTH - 2;
        for (int i = 0; i < replace.holders().size(); i++) {
            JobPicker.Holder holder = replace.holders().get(i);
            int y = topPos + TOP + (titleLines.size() - 1) * font.lineHeight + i * ROW_HEIGHT;
            Button name = addRenderableWidget(Button.builder(Component.literal(holder.name()), b -> {
            }).bounds(leftPos + CivGui.PADDING, y, nameWidth, BUTTON_HEIGHT).build());
            name.active = false;
            List<Component> jobs = new ArrayList<>();
            jobs.add(Component.translatable("civmod.gui.job_replace.jobs", holder.name()));
            jobs.addAll(holder.jobs());
            name.setTooltip(Tooltip.create(CommonComponents.joinLines(jobs)));
            Button swap = addRenderableWidget(Button.builder(Component.literal("⇄"), b -> swap(holder))
                    .bounds(leftPos + imageWidth - CivGui.PADDING - SWAP_WIDTH, y, SWAP_WIDTH, BUTTON_HEIGHT).build());
            swap.setTooltip(Tooltip.create(Component.translatable("civmod.gui.job_replace.swap", holder.name(), replace.settlerName())));
        }
    }

    private void swap(JobPicker.Holder holder) {
        ClientPacketDistributor.sendToServer(new JobPicker.Replace(replace.jobBlock(), replace.settlerId(), replace.job(), holder.id()));
        onClose();
    }

    @Override
    public void tick() {
        super.tick();
        var level = Minecraft.getInstance().level;
        // Closes when the settler or the JobBlock is gone; the server validates the holder on the click.
        if (level == null || level.getEntity(replace.settlerId()) == null || !(level.getBlockState(replace.jobBlock()).getBlock() instanceof JobBlock)) {
            onClose();
        }
    }
}
