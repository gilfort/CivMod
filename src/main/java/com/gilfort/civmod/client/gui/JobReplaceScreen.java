package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.item.JobPicker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
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

    private final JobPicker.OpenReplace replace;

    private JobReplaceScreen(JobPicker.OpenReplace replace) {
        super(Component.translatable("civmod.gui.job_replace.title", replace.settlerName(), replace.job().displayName()), WIDTH,
                TOP + replace.holders().size() * ROW_HEIGHT + 6);
        this.replace = replace;
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
            int y = topPos + TOP + i * ROW_HEIGHT;
            Button name = addRenderableWidget(Button.builder(Component.literal(holder.name()), b -> {
            }).bounds(leftPos + CivGui.PADDING, y, nameWidth, 20).build());
            name.active = false;
            List<Component> jobs = new ArrayList<>();
            jobs.add(Component.translatable("civmod.gui.job_replace.jobs", holder.name()));
            jobs.addAll(holder.jobs());
            name.setTooltip(Tooltip.create(CommonComponents.joinLines(jobs)));
            Button swap = addRenderableWidget(Button.builder(Component.literal("⇄"), b -> swap(holder))
                    .bounds(leftPos + imageWidth - CivGui.PADDING - SWAP_WIDTH, y, SWAP_WIDTH, 20).build());
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
