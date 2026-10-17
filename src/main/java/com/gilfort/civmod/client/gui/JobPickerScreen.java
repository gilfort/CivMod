package com.gilfort.civmod.client.gui;

import java.util.List;

import com.gilfort.civmod.item.JobPicker;
import com.gilfort.civmod.settlement.JobPlaces;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The job picker (vault {@code Systems/Scroll of Assignment.md}): one button per Job of the JobBlock with taken /
 * places ("Deliveryman 2/4"); full Jobs are greyed out. A click assigns the Job and closes the window.
 */
public class JobPickerScreen extends CivScreen {

    private static final int WIDTH = 176;
    private static final int TOP = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int ICON_SIZE = 16;

    private final JobPicker.Open picker;

    /**
     * @param picker what the server sent
     */
    private JobPickerScreen(JobPicker.Open picker) {
        super(Component.translatable("civmod.gui.job_picker.title", picker.settlerName()), WIDTH, TOP + picker.lines().size() * ROW_HEIGHT + 6);
        this.picker = picker;
    }

    /**
     * Opens the picker (client payload handler).
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handle(JobPicker.Open payload, IPayloadContext context) {
        Minecraft.getInstance().gui.setScreen(new JobPickerScreen(payload));
    }

    @Override
    protected void initContent() {
        int x = leftPos + CivGui.PADDING + ICON_SIZE + 4;
        int width = imageWidth - 2 * CivGui.PADDING - ICON_SIZE - 4;
        for (int i = 0; i < picker.lines().size(); i++) {
            JobPlaces.Line line = picker.lines().get(i);
            Button button = addRenderableWidget(Button.builder(label(line), b -> choose(line))
                    .bounds(x, topPos + TOP + i * ROW_HEIGHT, width, 20).build());
            button.active = !line.full();
            if (line.full()) {
                button.setTooltip(Tooltip.create(Component.translatable("civmod.gui.job_picker.full")));
            }
        }
    }

    /**
     * @param line a Job of the block
     * @return the button label: the Job name with taken / places
     */
    static Component label(JobPlaces.Line line) {
        return Component.translatable("civmod.gui.job_picker.line", line.job().displayName(), line.taken(), line.places());
    }

    private void choose(JobPlaces.Line line) {
        ClientPacketDistributor.sendToServer(new JobPicker.Choose(picker.jobBlock(), picker.settlerId(), line.job()));
        onClose();
    }

    @Override
    protected void extractPanelContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (int i = 0; i < picker.lines().size(); i++) {
            graphics.item(picker.lines().get(i).job().icon(), leftPos + CivGui.PADDING, topPos + TOP + i * ROW_HEIGHT + 2);
        }
    }

    @Override
    protected List<Component> helpLines() {
        return List.of(Component.translatable("civmod.gui.job_picker.help.choose"), Component.translatable("civmod.gui.job_picker.help.full"),
                Component.translatable("civmod.gui.job_picker.help.change"));
    }
}
