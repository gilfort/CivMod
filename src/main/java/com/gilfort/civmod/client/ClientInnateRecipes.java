package com.gilfort.civmod.client;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.registry.ModRecipes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/**
 * The innate recipes ({@code civmod:job_crafting}) the server sent, for the recipe viewers' display-only "Job Recipes"
 * category and the Patterns tab (vault {@code Systems/Producing Jobs.md}). Read before the viewers reload on the same
 * event.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class ClientInnateRecipes {

    private static List<RecipeHolder<JobCraftingRecipe>> recipes = List.of();

    private ClientInnateRecipes() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onRecipesReceived(RecipesReceivedEvent event) {
        if (event.getRecipeTypes().contains(ModRecipes.JOB_CRAFTING.get())) {
            recipes = InnateRecipes.sorted(event.getRecipeMap().byType(ModRecipes.JOB_CRAFTING.get()));
        }
    }

    /**
     * @return the innate recipes, ordered by recipe id
     */
    public static List<RecipeHolder<JobCraftingRecipe>> recipes() {
        return recipes;
    }

    /**
     * @param id a recipe id
     * @return the innate recipe with that id, empty if the server sent none
     */
    public static Optional<JobCraftingRecipe> recipe(ResourceKey<Recipe<?>> id) {
        return recipes.stream().filter(holder -> holder.id().equals(id)).map(RecipeHolder::value).findFirst();
    }

    /**
     * @return every Producing Job with an innate recipe, each once
     */
    public static List<Job> allJobs() {
        return recipes.stream().flatMap(holder -> InnateRecipes.jobs(holder.value()).stream()).distinct().toList();
    }

    /**
     * @param recipe an innate recipe
     * @return the items of the stations it is worked at ({@link InnateRecipes#stations(JobCraftingRecipe)}), each once
     */
    public static List<ItemStack> stationItems(JobCraftingRecipe recipe) {
        Set<Item> items = new LinkedHashSet<>();
        InnateRecipes.stations(recipe).forEach(station -> BuiltInRegistries.BLOCK.get(station)
                .ifPresent(blocks -> blocks.forEach(block -> items.add(block.value().asItem()))));
        items.remove(Items.AIR);
        return items.stream().map(ItemStack::new).toList();
    }
}
