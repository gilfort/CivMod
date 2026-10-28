package com.gilfort.civmod.compat.rei;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.client.ClientInnateRecipes;
import com.gilfort.civmod.compat.InnateRecipeLayout;
import com.gilfort.civmod.logistics.InnateRecipes;
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
 * result, its tool and station (without an own station: the main station of its Jobs), and the icons of the Jobs that
 * know it ({@link InnateRecipeLayout}). No transfer handler: innate recipes are no patterns the player makes.
 */
final class InnateRecipeCategory implements DisplayCategory<InnateRecipeDisplay> {

    /** REI's padding around the layout. */
    private static final int PADDING_X = 6;
    private static final int PADDING_Y = 5;

    @Override
    public CategoryIdentifier<? extends InnateRecipeDisplay> getCategoryIdentifier() {
        return InnateRecipeDisplay.CATEGORY;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(InnateRecipeLayout.TITLE);
    }

    @Override
    public Renderer getIcon() {
        return EntryStacks.of(ModItems.HAMMER.get());
    }

    @Override
    public int getDisplayHeight() {
        return InnateRecipeLayout.HEIGHT + 2 * PADDING_Y;
    }

    @Override
    public List<Widget> setupDisplay(InnateRecipeDisplay display, Rectangle bounds) {
        JobCraftingRecipe recipe = display.recipe();
        int x = bounds.x + PADDING_X;
        int y = bounds.y + PADDING_Y;
        List<Widget> widgets = new ArrayList<>();
        widgets.add(Widgets.createRecipeBase(bounds));
        for (int i = 0; i < recipe.ingredients().size(); i++) {
            widgets.add(Widgets.createSlot(new Point(x + InnateRecipeLayout.inputX(i), y + InnateRecipeLayout.inputY(i)))
                    .entries(EntryIngredients.ofIngredient(recipe.ingredients().get(i))).markInput());
        }
        widgets.add(Widgets.createArrow(new Point(x + InnateRecipeLayout.ARROW_X, y + InnateRecipeLayout.MIDDLE_Y)));
        Point output = new Point(x + InnateRecipeLayout.OUTPUT_X, y + InnateRecipeLayout.MIDDLE_Y);
        widgets.add(Widgets.createResultSlotBackground(output));
        widgets.add(Widgets.createSlot(output).entries(EntryIngredients.of(recipe.result())).disableBackground().markOutput());
        recipe.tool().ifPresent(tool -> widgets.add(Widgets.createSlot(new Point(x + InnateRecipeLayout.TOOL_X, y + InnateRecipeLayout.EXTRAS_Y))
                .entries(EntryIngredients.ofIngredient(tool).stream()
                        .map(stack -> stack.copy().tooltip(Component.translatable(InnateRecipeLayout.TOOL_LINE))).toList())));
        List<EntryStack<ItemStack>> stations = ClientInnateRecipes.stationItems(recipe).stream().map(EntryStacks::of)
                .map(stack -> stack.tooltip(Component.translatable(InnateRecipeLayout.STATION_LINE))).toList();
        if (!stations.isEmpty()) {
            widgets.add(Widgets.createSlot(new Point(x + InnateRecipeLayout.STATION_X, y + InnateRecipeLayout.EXTRAS_Y)).entries(stations));
        }
        List<Job> jobs = InnateRecipes.jobs(recipe);
        for (int i = 0; i < jobs.size() && i < InnateRecipeLayout.MAX_JOBS; i++) {
            Job job = jobs.get(i);
            widgets.add(Widgets.createSlot(new Point(x + InnateRecipeLayout.jobX(i), y + InnateRecipeLayout.JOBS_Y))
                    .entries(List.of(EntryStacks.of(job.icon()).tooltip(job.displayName()))).unmarkInputOrOutput());
        }
        return widgets;
    }
}
