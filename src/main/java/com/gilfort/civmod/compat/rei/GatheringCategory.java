package com.gilfort.civmod.compat.rei;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.client.ClientJobDrops;
import com.gilfort.civmod.compat.GatheringLayout;
import com.gilfort.civmod.job.JobDrops;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/**
 * REI's display-only "Settler Gathering" category (vault {@code Systems/Resource Collection Jobs.md}, decisions
 * 2026-10-29): one NPC drop pool entry ({@link GatheringLayout}), with its Jobs as workstations. No transfer handler.
 */
final class GatheringCategory implements DisplayCategory<GatheringDisplay> {

    /** REI's padding around the layout. */
    private static final int PADDING_X = 6;
    private static final int PADDING_Y = 5;

    @Override
    public CategoryIdentifier<? extends GatheringDisplay> getCategoryIdentifier() {
        return GatheringDisplay.CATEGORY;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(GatheringLayout.TITLE);
    }

    @Override
    public Renderer getIcon() {
        return EntryStacks.of(Items.WHEAT_SEEDS);
    }

    @Override
    public int getDisplayHeight() {
        return GatheringLayout.HEIGHT + 2 * PADDING_Y;
    }

    @Override
    public List<Widget> setupDisplay(GatheringDisplay display, Rectangle bounds) {
        JobDrops.Drop drop = display.drop();
        int x = bounds.x + PADDING_X;
        int y = bounds.y + PADDING_Y;
        List<Widget> widgets = new ArrayList<>();
        widgets.add(Widgets.createRecipeBase(bounds));
        Component[] fromLines = GatheringLayout.fromLines(drop).toArray(Component[]::new);
        widgets.add(Widgets.createSlot(new Point(x + GatheringLayout.FROM_X, y + GatheringLayout.ROW_Y))
                .entries(GatheringLayout.fromIcons(drop).stream().map(EntryStacks::of).map(stack -> stack.tooltip(fromLines)).toList()).markInput());
        widgets.add(Widgets.createArrow(new Point(x + GatheringLayout.ARROW_X, y + GatheringLayout.ROW_Y)));
        Point result = new Point(x + GatheringLayout.RESULT_X, y + GatheringLayout.ROW_Y);
        Component[] resultLines = GatheringLayout.resultLines(drop).toArray(Component[]::new);
        widgets.add(Widgets.createResultSlotBackground(result));
        widgets.add(Widgets.createSlot(result).entries(List.of(EntryStacks.of(GatheringLayout.result(drop)).tooltip(resultLines)))
                .disableBackground().markOutput());
        widgets.add(Widgets.createLabel(new Point(x + GatheringLayout.CHANCE_X, y + GatheringLayout.ROW_Y + GatheringLayout.TEXT_Y),
                GatheringLayout.chance(drop)).leftAligned().noShadow().color(GatheringLayout.TEXT_COLOR));
        if (!GatheringLayout.toolIcons(drop).isEmpty()) {
            widgets.add(Widgets.createSlot(new Point(x + GatheringLayout.TOOL_X, y + GatheringLayout.ROW_Y))
                    .entries(GatheringLayout.toolIcons(drop).stream().map(EntryStacks::of)
                            .map(stack -> stack.tooltip(Component.translatable(GatheringLayout.TOOL_LINE))).toList()).unmarkInputOrOutput());
        }
        List<Job> jobs = ClientJobDrops.jobs(drop);
        for (int i = 0; i < jobs.size() && i < GatheringLayout.MAX_JOBS; i++) {
            Job job = jobs.get(i);
            widgets.add(Widgets.createSlot(new Point(x + GatheringLayout.jobX(i), y + GatheringLayout.JOBS_Y))
                    .entries(List.of(EntryStacks.of(job.icon()).tooltip(job.displayName()))).unmarkInputOrOutput());
        }
        GatheringLayout.note(drop).forEach(note -> widgets.add(Widgets.createLabel(new Point(x, y + GatheringLayout.NOTE_Y), note)
                .leftAligned().noShadow().color(GatheringLayout.TEXT_COLOR)));
        return widgets;
    }
}
