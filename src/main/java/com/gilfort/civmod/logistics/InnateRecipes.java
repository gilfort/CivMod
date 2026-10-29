package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Collection;
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
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.registry.ModRecipes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.level.block.Block;

/**
 * The innate recipes of the Producing and Resource Collection Jobs (vault {@code Systems/Producing Jobs.md},
 * {@code Systems/Resource Collection Jobs.md}): every {@code civmod:job_crafting} recipe ({@link JobCraftingRecipe})
 * naming a Job in {@code jobs}, ordered by recipe id. A Producing Job's recipes ({@link #of}) are locked patterns of its
 * library; a Resource Collection Job's recipes ({@link #collecting}) join its roll. Jobs of another category or unknown
 * Jobs in {@code jobs} are ignored with a log warning (once per datapack load). Resolved once per loaded recipe set: the
 * recipes per Job, their station tags per Job and the recipes by id, so the lookups of the loops cost one map access.
 */
public final class InnateRecipes {

    /** The recipe set the cache was built from (replaced on every datapack reload). */
    private static @Nullable RecipeMap cachedFrom;
    private static Index index = Index.EMPTY;

    private InnateRecipes() {
    }

    /**
     * @param level the level
     * @param job   a Job
     * @return the innate recipes of the Job, ordered by recipe id (none for a Job that is not Producing). The same list
     *         instance until the datapacks change.
     */
    public static List<RecipeHolder<JobCraftingRecipe>> of(ServerLevel level, Job job) {
        return index(level).byJob().getOrDefault(job, List.of());
    }

    /**
     * @param level the level
     * @param job   a Job
     * @return the innate recipes of a Resource Collection Job, ordered by recipe id (none for a Job of another
     *         category). The same list instance until the datapacks change.
     */
    public static List<RecipeHolder<JobCraftingRecipe>> collecting(ServerLevel level, Job job) {
        return index(level).collecting().getOrDefault(job, List.of());
    }

    /**
     * @param level   the level
     * @param pattern a pattern
     * @return the innate recipe it follows, empty if it is no innate pattern or the recipe is gone
     */
    public static Optional<JobCraftingRecipe> recipe(ServerLevel level, Pattern pattern) {
        if (!pattern.innate() || pattern.recipe().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(index(level).byId().get(pattern.recipe().get()));
    }

    /**
     * @param level   the level
     * @param pattern a pattern
     * @return the tool of the innate recipe it follows, empty if it needs none (or is no innate pattern)
     */
    public static Optional<Ingredient> tool(ServerLevel level, Pattern pattern) {
        return recipe(level, pattern).flatMap(JobCraftingRecipe::tool);
    }

    /**
     * @param level the level
     * @param job   a Job
     * @return the station tags its innate recipes name (each once)
     */
    public static List<TagKey<Block>> stations(ServerLevel level, Job job) {
        return index(level).stations().getOrDefault(job, List.of());
    }

    /**
     * @param recipe an innate recipe
     * @return the station tags it is worked at: its own station, else the main recipe type's station of each Job that
     *         knows it (each once)
     */
    public static List<TagKey<Block>> stations(JobCraftingRecipe recipe) {
        if (recipe.station().isPresent()) {
            return List.of(recipe.station().get());
        }
        return jobs(recipe).stream().flatMap(job -> job.profile(ProducingProfile.class).stream())
                .map(profile -> profile.station(profile.recipeTypes().getFirst())).distinct().toList();
    }

    /**
     * @param recipe an innate recipe
     * @return the Producing and Resource Collection Jobs it names (unknown Jobs and Jobs of another category left out)
     */
    public static List<Job> jobs(JobCraftingRecipe recipe) {
        return recipe.jobs().stream().map(ModJobs.REGISTRY::getValue).filter(job -> producing(job) || collecting(job)).toList();
    }

    /**
     * @param recipes innate recipes
     * @return them ordered by recipe id
     */
    public static List<RecipeHolder<JobCraftingRecipe>> sorted(Collection<RecipeHolder<JobCraftingRecipe>> recipes) {
        return recipes.stream().sorted(Comparator.comparing(holder -> holder.id().identifier().toString())).toList();
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
        List<Item> grid = recipe.ingredients().stream().map(PatternRecipes::representative).toList();
        return new Pattern(slot, result.getItem(), result.getCount(), grid, Optional.of(holder.id()), false, checked, true);
    }

    /**
     * @param tool an innate recipe's tool
     * @return the state line of a recipe skipped without it ("Needs Shears")
     */
    public static Component needsTool(Ingredient tool) {
        Component first = new ItemStack(PatternRecipes.representative(tool)).getHoverName();
        Optional<TagKey<Item>> tag = tool.isCustom() ? Optional.empty() : tool.getValues().unwrapKey();
        return Component.translatable("civmod.gui.patterns.needs_tool", tag.map(found -> PatternRecipes.tagName(found, first)).orElse(first));
    }

    /**
     * @param input an ingredient of a Resource Collection Job's innate recipe
     * @return the Found tab line of the recipe without it ("Needs: Bucket"), a tag named by its translation
     */
    public static Component needsInput(Ingredient input) {
        Component first = new ItemStack(PatternRecipes.representative(input)).getHoverName();
        Optional<TagKey<Item>> tag = input.isCustom() ? Optional.empty() : input.getValues().unwrapKey();
        return Component.translatable("civmod.gui.found.needs_input", tag.map(found -> PatternRecipes.tagName(found, first)).orElse(first));
    }

    /**
     * @param station an innate recipe's station
     * @return the state line of a recipe skipped without it ("Needs a station nearby: Water Cauldron"), the tag named
     *         by its translation, else by its first block
     */
    public static Component needsStation(TagKey<Block> station) {
        Component first = BuiltInRegistries.BLOCK.get(station).flatMap(set -> set.stream().findFirst())
                .map(block -> block.value().getName()).orElse(Component.literal("#" + station.location()));
        return Component.translatable("civmod.gui.patterns.needs_station", PatternRecipes.tagName(station, first));
    }

    private static boolean producing(@Nullable Job job) {
        return job != null && job.category() == JobCategory.PRODUCING;
    }

    private static boolean collecting(@Nullable Job job) {
        return job != null && job.category() == JobCategory.RESOURCE_COLLECTION;
    }

    private static Index index(ServerLevel level) {
        RecipeMap recipes = level.recipeAccess().recipeMap();
        if (recipes != cachedFrom) {
            index = build(recipes);
            cachedFrom = recipes;
        }
        return index;
    }

    private static Index build(RecipeMap recipes) {
        Map<Job, List<RecipeHolder<JobCraftingRecipe>>> byJob = new HashMap<>();
        Map<Job, List<RecipeHolder<JobCraftingRecipe>>> collecting = new HashMap<>();
        Map<ResourceKey<Recipe<?>>, JobCraftingRecipe> byId = new HashMap<>();
        for (RecipeHolder<JobCraftingRecipe> holder : sorted(recipes.byType(ModRecipes.JOB_CRAFTING.get()))) {
            byId.put(holder.id(), holder.value());
            for (Identifier id : holder.value().jobs()) {
                Job job = ModJobs.REGISTRY.getValue(id);
                if (producing(job)) {
                    byJob.computeIfAbsent(job, key -> new ArrayList<>()).add(holder);
                } else if (collecting(job)) {
                    collecting.computeIfAbsent(job, key -> new ArrayList<>()).add(holder);
                } else {
                    CivMod.LOGGER.warn("Innate recipe {} names {}, which is no known Producing or Resource Collection Job: ignored for it",
                            holder.id().identifier(), id);
                }
            }
        }
        Map<Job, List<TagKey<Block>>> stations = new HashMap<>();
        byJob.forEach((job, list) -> stations.put(job, list.stream().flatMap(holder -> holder.value().station().stream()).distinct().toList()));
        byJob.replaceAll((job, list) -> List.copyOf(list));
        collecting.replaceAll((job, list) -> List.copyOf(list));
        return new Index(Map.copyOf(byJob), Map.copyOf(collecting), Map.copyOf(stations), Map.copyOf(byId));
    }

    /**
     * The innate recipes of one loaded recipe set.
     *
     * @param byJob      the recipes per Producing Job, ordered by recipe id
     * @param collecting the recipes per Resource Collection Job, ordered by recipe id
     * @param stations   the station tags the recipes of each Producing Job name, each once
     * @param byId       every innate recipe by id
     */
    private record Index(Map<Job, List<RecipeHolder<JobCraftingRecipe>>> byJob, Map<Job, List<RecipeHolder<JobCraftingRecipe>>> collecting,
            Map<Job, List<TagKey<Block>>> stations, Map<ResourceKey<Recipe<?>>, JobCraftingRecipe> byId) {

        static final Index EMPTY = new Index(Map.of(), Map.of(), Map.of(), Map.of());
    }
}
