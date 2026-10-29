package com.gilfort.civmod.compat.jei;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.client.ClientInnateRecipes;
import com.gilfort.civmod.compat.InnateRecipeLayout;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.registry.ModItems;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI's display-only "Job Recipes" category (vault {@code Systems/Producing Jobs.md}): an innate recipe's inputs and
 * result, its tool and station (without an own station: the main station of its Jobs), what a Resource Collection
 * recipe requires, and the icons of the Jobs that know it ({@link InnateRecipeLayout}). No transfer button: innate recipes are no patterns the player makes.
 */
final class InnateRecipeCategory extends AbstractRecipeCategory<RecipeHolder<JobCraftingRecipe>> {

    /** The category. */
    static final IRecipeHolderType<JobCraftingRecipe> TYPE = IRecipeHolderType.create(CivMod.id("job_crafting"));

    InnateRecipeCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable(InnateRecipeLayout.TITLE), gui.createDrawableItemLike(ModItems.HAMMER.get()), InnateRecipeLayout.WIDTH,
                InnateRecipeLayout.HEIGHT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<JobCraftingRecipe> holder, IFocusGroup focuses) {
        JobCraftingRecipe recipe = holder.value();
        List<Ingredient> inputs = recipe.ingredients();
        for (int i = 0; i < inputs.size(); i++) {
            builder.addInputSlot(InnateRecipeLayout.inputX(i), InnateRecipeLayout.inputY(i)).setStandardSlotBackground().add(inputs.get(i));
        }
        builder.addOutputSlot(InnateRecipeLayout.OUTPUT_X, InnateRecipeLayout.MIDDLE_Y).setOutputSlotBackground().add(recipe.result());
        builder.setShapeless();
        recipe.tool().ifPresent(tool -> builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, InnateRecipeLayout.TOOL_X, InnateRecipeLayout.EXTRAS_Y)
                .setStandardSlotBackground().add(tool)
                .addRichTooltipCallback((view, tooltip) -> tooltip.add(Component.translatable(InnateRecipeLayout.TOOL_LINE))));
        List<ItemStack> stations = ClientInnateRecipes.stationItems(recipe);
        if (!stations.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, InnateRecipeLayout.STATION_X, InnateRecipeLayout.EXTRAS_Y).setStandardSlotBackground()
                    .addItemStacks(stations)
                    .addRichTooltipCallback((view, tooltip) -> tooltip.add(Component.translatable(InnateRecipeLayout.STATION_LINE)));
        }
        recipe.requires().ifPresent(requires -> builder.addSlot(RecipeIngredientRole.RENDER_ONLY, InnateRecipeLayout.REQUIRES_X, InnateRecipeLayout.EXTRAS_Y)
                .setStandardSlotBackground().addItemStacks(InnateRecipeLayout.requiresIcons(requires))
                .addRichTooltipCallback((view, tooltip) -> InnateRecipeLayout.requiresLines(requires).forEach(tooltip::add)));
        List<Job> jobs = InnateRecipes.jobs(recipe);
        for (int i = 0; i < jobs.size() && i < InnateRecipeLayout.MAX_JOBS; i++) {
            Job job = jobs.get(i);
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, InnateRecipeLayout.jobX(i), InnateRecipeLayout.JOBS_Y).add(job.icon())
                    .addRichTooltipCallback((view, tooltip) -> tooltip.add(job.displayName()));
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RecipeHolder<JobCraftingRecipe> holder, IFocusGroup focuses) {
        builder.addRecipeArrow().setPosition(InnateRecipeLayout.ARROW_X, InnateRecipeLayout.MIDDLE_Y);
    }
}
