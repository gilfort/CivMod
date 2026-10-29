package com.gilfort.civmod.compat.jei;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.client.ClientJobDrops;
import com.gilfort.civmod.compat.GatheringLayout;
import com.gilfort.civmod.job.JobDrops;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * JEI's display-only "Settler Gathering" category (vault {@code Systems/Resource Collection Jobs.md}, decisions
 * 2026-10-29): one NPC drop pool entry ({@link GatheringLayout}), with its Jobs as catalysts. No transfer button.
 */
final class GatheringCategory extends AbstractRecipeCategory<JobDrops.Drop> {

    /** The category. */
    static final IRecipeType<JobDrops.Drop> TYPE = IRecipeType.create(CivMod.id("settler_gathering"), JobDrops.Drop.class);

    GatheringCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable(GatheringLayout.TITLE), gui.createDrawableItemLike(Items.WHEAT_SEEDS), GatheringLayout.WIDTH,
                GatheringLayout.HEIGHT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, JobDrops.Drop drop, IFocusGroup focuses) {
        List<Component> fromLines = GatheringLayout.fromLines(drop);
        builder.addInputSlot(GatheringLayout.FROM_X, GatheringLayout.ROW_Y).setStandardSlotBackground().addItemStacks(GatheringLayout.fromIcons(drop))
                .addRichTooltipCallback((view, tooltip) -> fromLines.forEach(tooltip::add));
        List<Component> resultLines = GatheringLayout.resultLines(drop);
        builder.addOutputSlot(GatheringLayout.RESULT_X, GatheringLayout.ROW_Y).setOutputSlotBackground().add(GatheringLayout.result(drop))
                .addRichTooltipCallback((view, tooltip) -> resultLines.forEach(tooltip::add));
        List<ItemStack> tools = GatheringLayout.toolIcons(drop);
        if (!tools.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, GatheringLayout.TOOL_X, GatheringLayout.ROW_Y).setStandardSlotBackground()
                    .addItemStacks(tools).addRichTooltipCallback((view, tooltip) -> tooltip.add(Component.translatable(GatheringLayout.TOOL_LINE)));
        }
        List<Job> jobs = ClientJobDrops.jobs(drop);
        for (int i = 0; i < jobs.size() && i < GatheringLayout.MAX_JOBS; i++) {
            Job job = jobs.get(i);
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, GatheringLayout.jobX(i), GatheringLayout.JOBS_Y).add(job.icon())
                    .addRichTooltipCallback((view, tooltip) -> tooltip.add(job.displayName()));
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, JobDrops.Drop drop, IFocusGroup focuses) {
        builder.addRecipeArrow().setPosition(GatheringLayout.ARROW_X, GatheringLayout.ROW_Y);
        builder.addText(GatheringLayout.chance(drop), GatheringLayout.TOOL_X - GatheringLayout.CHANCE_X, GatheringLayout.SLOT)
                .setPosition(GatheringLayout.CHANCE_X, GatheringLayout.ROW_Y + GatheringLayout.TEXT_Y).setColor(GatheringLayout.TEXT_COLOR);
        GatheringLayout.note(drop).forEach(note -> builder.addText(note, GatheringLayout.WIDTH, GatheringLayout.SLOT)
                .setPosition(0, GatheringLayout.NOTE_Y).setColor(GatheringLayout.TEXT_COLOR));
    }
}
