package com.gilfort.civmod.client;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.registry.ModRecipes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/**
 * The innate recipes ({@code civmod:job_crafting}) the server sent, for the recipe viewers' display-only "Job Recipes"
 * category (vault {@code Systems/Producing Jobs.md}). Read before the viewers reload on the same event.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class ClientInnateRecipes {

    private static List<RecipeHolder<JobCraftingRecipe>> recipes = List.of();

    private ClientInnateRecipes() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onRecipesReceived(RecipesReceivedEvent event) {
        if (event.getRecipeTypes().contains(ModRecipes.JOB_CRAFTING.get())) {
            recipes = event.getRecipeMap().byType(ModRecipes.JOB_CRAFTING.get()).stream()
                    .sorted(Comparator.comparing(holder -> holder.id().identifier().toString())).toList();
        }
    }

    /**
     * @return the innate recipes, ordered by recipe id
     */
    public static List<RecipeHolder<JobCraftingRecipe>> recipes() {
        return recipes;
    }

    /**
     * @param recipe an innate recipe
     * @return the Producing Jobs that know it (unknown or other Jobs left out)
     */
    public static List<Job> jobs(JobCraftingRecipe recipe) {
        return recipe.jobs().stream().map(ModJobs.REGISTRY::getValue).filter(Objects::nonNull)
                .filter(job -> job.category() == JobCategory.PRODUCING).toList();
    }

    /**
     * @return every Producing Job with an innate recipe, each once
     */
    public static List<Job> allJobs() {
        return recipes.stream().flatMap(holder -> jobs(holder.value()).stream()).distinct().toList();
    }

    /**
     * @param station a station block tag
     * @return the items of its blocks
     */
    public static List<ItemStack> stationItems(TagKey<Block> station) {
        return BuiltInRegistries.BLOCK.get(station).stream().flatMap(set -> set.stream()).map(block -> new ItemStack(block.value()))
                .filter(stack -> !stack.isEmpty()).toList();
    }
}
