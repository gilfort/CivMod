package com.gilfort.civmod.compat.jei;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.client.ClientInnateRecipes;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI's display-only "Job Recipes" category (vault {@code Systems/Producing Jobs.md}): an innate recipe's inputs and
 * result, its tool and station, and the icons of the Jobs that know it. No transfer button: innate recipes are no
 * patterns the player makes.
 */
final class JobRecipeCategory extends AbstractRecipeCategory<RecipeHolder<JobCraftingRecipe>> {

    /** The category. */
    static final IRecipeHolderType<JobCraftingRecipe> TYPE = IRecipeHolderType.create(CivMod.id("job_crafting"));

    private static final int WIDTH = 116;
    private static final int HEIGHT = 76;
    private static final int OUTPUT_X = 95;
    private static final int MIDDLE_Y = 19;
    private static final int EXTRAS_Y = 38;
    private static final int JOBS_Y = 58;

    JobRecipeCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable("civmod.jei.job_recipes"), gui.createDrawableItemLike(ModItems.HAMMER.get()), WIDTH, HEIGHT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<JobCraftingRecipe> holder, IFocusGroup focuses) {
        JobCraftingRecipe recipe = holder.value();
        List<Ingredient> inputs = recipe.ingredients();
        for (int i = 0; i < inputs.size(); i++) {
            builder.addInputSlot(1 + (i % 3) * 18, 1 + (i / 3) * 18).setStandardSlotBackground().add(inputs.get(i));
        }
        builder.addOutputSlot(OUTPUT_X, MIDDLE_Y).setOutputSlotBackground().add(recipe.result());
        builder.setShapeless();
        recipe.tool().ifPresent(tool -> builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, 59, EXTRAS_Y).setStandardSlotBackground().add(tool)
                .addRichTooltipCallback((view, tooltip) -> tooltip.add(Component.translatable("civmod.jei.job_recipes.tool"))));
        recipe.station().ifPresent(station -> builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, 77, EXTRAS_Y).setStandardSlotBackground()
                .addItemStacks(ClientInnateRecipes.stationItems(station))
                .addRichTooltipCallback((view, tooltip) -> tooltip.add(Component.translatable("civmod.jei.job_recipes.station"))));
        List<Job> jobs = InnateRecipes.jobs(recipe);
        for (int i = 0; i < jobs.size() && i < WIDTH / 18; i++) {
            Job job = jobs.get(i);
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, 1 + i * 18, JOBS_Y).add(job.icon())
                    .addRichTooltipCallback((view, tooltip) -> tooltip.add(job.displayName()));
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RecipeHolder<JobCraftingRecipe> holder, IFocusGroup focuses) {
        builder.addRecipeArrow().setPosition(61, MIDDLE_Y);
    }
}
