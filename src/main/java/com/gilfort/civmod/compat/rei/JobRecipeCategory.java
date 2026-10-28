package com.gilfort.civmod.compat.rei;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.client.ClientInnateRecipes;
import com.gilfort.civmod.registry.ModItems;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * REI's display-only "Job Recipes" category (vault {@code Systems/Producing Jobs.md}): an innate recipe's inputs and
 * result, its tool and station, and the icons of the Jobs that know it. No transfer handler: innate recipes are no
 * patterns the player makes.
 */
final class JobRecipeCategory implements DisplayCategory<JobRecipeDisplay> {

    @Override
    public CategoryIdentifier<? extends JobRecipeDisplay> getCategoryIdentifier() {
        return JobRecipeDisplay.CATEGORY;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("civmod.jei.job_recipes");
    }

    @Override
    public Renderer getIcon() {
        return EntryStacks.of(ModItems.HAMMER.get());
    }

    @Override
    public int getDisplayHeight() {
        return 86;
    }

    @Override
    public List<Widget> setupDisplay(JobRecipeDisplay display, Rectangle bounds) {
        JobCraftingRecipe recipe = display.recipe();
        int x = bounds.x + 6;
        int y = bounds.y + 5;
        List<Widget> widgets = new ArrayList<>();
        widgets.add(Widgets.createRecipeBase(bounds));
        for (int i = 0; i < recipe.ingredients().size(); i++) {
            widgets.add(Widgets.createSlot(new Point(x + 1 + (i % 3) * 18, y + 1 + (i / 3) * 18))
                    .entries(EntryIngredients.ofIngredient(recipe.ingredients().get(i))).markInput());
        }
        widgets.add(Widgets.createArrow(new Point(x + 61, y + 19)));
        widgets.add(Widgets.createResultSlotBackground(new Point(x + 95, y + 19)));
        widgets.add(Widgets.createSlot(new Point(x + 95, y + 19)).entries(EntryIngredients.of(recipe.result())).disableBackground().markOutput());
        recipe.tool().ifPresent(tool -> widgets.add(Widgets.createSlot(new Point(x + 59, y + 38)).entries(EntryIngredients.ofIngredient(tool).stream()
                .map(stack -> stack.copy().tooltip(Component.translatable("civmod.jei.job_recipes.tool"))).toList())));
        recipe.station().ifPresent(station -> {
            List<EntryStack<ItemStack>> stations = ClientInnateRecipes.stationItems(station).stream().map(EntryStacks::of)
                    .map(stack -> stack.tooltip(Component.translatable("civmod.jei.job_recipes.station"))).toList();
            widgets.add(Widgets.createSlot(new Point(x + 77, y + 38)).entries(stations));
        });
        List<Job> jobs = ClientInnateRecipes.jobs(recipe);
        for (int i = 0; i < jobs.size() && i < 7; i++) {
            Job job = jobs.get(i);
            widgets.add(Widgets.createSlot(new Point(x + 1 + i * 18, y + 58)).entries(List.of(EntryStacks.of(job.icon()).tooltip(job.displayName())))
                    .unmarkInputOrOutput());
        }
        return widgets;
    }
}
