package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.registry.ModRecipes;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.level.block.Block;

/**
 * The innate recipes of the Producing Jobs (vault {@code Systems/Producing Jobs.md}): every {@code civmod:job_crafting}
 * recipe ({@link JobCraftingRecipe}) naming a Job in {@code jobs}, ordered by recipe id. They apply to every Producing
 * Job; Jobs of another category or unknown Jobs in {@code jobs} are ignored with a log warning (once per datapack load).
 * Resolved once per loaded recipe set.
 */
public final class InnateRecipes {

    /** The recipe set the cache was built from (replaced on every datapack reload). */
    private static @Nullable RecipeMap cachedFrom;
    private static Map<Job, List<RecipeHolder<JobCraftingRecipe>>> byJob = Map.of();

    private InnateRecipes() {
    }

    /**
     * @param level the level
     * @param job   a Job
     * @return the innate recipes of the Job, ordered by recipe id (none for a Job that is not Producing)
     */
    public static List<RecipeHolder<JobCraftingRecipe>> of(ServerLevel level, Job job) {
        return byJob(level).getOrDefault(job, List.of());
    }

    /**
     * @param level   the level
     * @param pattern a pattern
     * @return the innate recipe it follows, empty if it is no innate pattern or the recipe is gone
     */
    public static Optional<JobCraftingRecipe> recipe(ServerLevel level, Pattern pattern) {
        if (!pattern.innate()) {
            return Optional.empty();
        }
        return pattern.recipe().flatMap(id -> PatternRecipes.holder(level, id)).map(RecipeHolder::value)
                .filter(JobCraftingRecipe.class::isInstance).map(JobCraftingRecipe.class::cast);
    }

    /**
     * @param level the level
     * @param job   a Job
     * @return the station tags its innate recipes name (each once)
     */
    public static List<TagKey<Block>> stations(ServerLevel level, Job job) {
        return of(level, job).stream().flatMap(holder -> holder.value().station().stream()).distinct().toList();
    }

    /**
     * The library pattern of an innate recipe: its result as output, one item per ingredient in the grid.
     *
     * @param slot    the library slot
     * @param holder  the innate recipe
     * @param checked whether it is checked
     * @return the locked pattern
     */
    public static Pattern pattern(int slot, RecipeHolder<JobCraftingRecipe> holder, boolean checked) {
        JobCraftingRecipe recipe = holder.value();
        ItemStack result = recipe.result().create();
        List<Item> grid = recipe.ingredients().stream().map(ingredient -> ingredient.items().findFirst().map(Holder::value).orElse(Items.AIR)).toList();
        return new Pattern(slot, result.getItem(), result.getCount(), grid, Optional.of(holder.id()), false, checked, true);
    }

    /**
     * @param tool an innate recipe's tool
     * @return the state line of a recipe skipped without it ("Needs Shears")
     */
    public static Component needsTool(Ingredient tool) {
        Item item = tool.items().findFirst().map(Holder::value).orElse(Items.AIR);
        return Component.translatable("civmod.gui.patterns.needs_tool", new ItemStack(item).getHoverName());
    }

    /**
     * @param station an innate recipe's station
     * @return the state line of a recipe skipped without it ("Needs a Water Cauldron nearby")
     */
    public static Component needsStation(TagKey<Block> station) {
        Component name = BuiltInRegistries.BLOCK.get(station).flatMap(set -> set.stream().findFirst())
                .map(block -> block.value().getName()).orElse(Component.literal("#" + station.location()));
        return Component.translatable("civmod.gui.patterns.needs_station", name);
    }

    private static Map<Job, List<RecipeHolder<JobCraftingRecipe>>> byJob(ServerLevel level) {
        RecipeMap recipes = level.recipeAccess().recipeMap();
        if (recipes != cachedFrom) {
            byJob = build(recipes);
            cachedFrom = recipes;
        }
        return byJob;
    }

    private static Map<Job, List<RecipeHolder<JobCraftingRecipe>>> build(RecipeMap recipes) {
        Map<Job, List<RecipeHolder<JobCraftingRecipe>>> built = new HashMap<>();
        List<RecipeHolder<JobCraftingRecipe>> all = new ArrayList<>(recipes.byType(ModRecipes.JOB_CRAFTING.get()));
        all.sort(Comparator.comparing(holder -> holder.id().identifier().toString()));
        for (RecipeHolder<JobCraftingRecipe> holder : all) {
            for (Identifier id : holder.value().jobs()) {
                Job job = ModJobs.REGISTRY.getValue(id);
                if (job == null) {
                    CivMod.LOGGER.warn("Innate recipe {} names the unknown Job {}: ignored for it", holder.id().identifier(), id);
                } else if (job.category() != JobCategory.PRODUCING) {
                    CivMod.LOGGER.warn("Innate recipe {} names {}, which is no Producing Job: ignored for it", holder.id().identifier(), id);
                } else {
                    built.computeIfAbsent(job, key -> new ArrayList<>()).add(holder);
                }
            }
        }
        built.replaceAll((job, list) -> List.copyOf(list));
        return Map.copyOf(built);
    }
}
