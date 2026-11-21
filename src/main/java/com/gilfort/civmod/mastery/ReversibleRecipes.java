package com.gilfort.civmod.mastery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.registry.ModRecipes;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;

/**
 * The recipes Mastery never touches (vault {@code Systems/Producing Jobs.md}): a recipe is reversible when its result is an
 * ingredient of another recipe whose result is one of its own ingredients (nine iron ingots make an iron block, an iron block
 * makes nine iron ingots). Saving material or making extra output there would make material out of nothing.
 *
 * <p>The set is built from every recipe of the server the first time a craft asks after a datapack reload, and kept until
 * the recipe set is replaced by the next reload, like {@code PatternRecipes.singleInput}. Recipe types the mod cannot lay
 * out in a pattern are not part of it.
 */
public final class ReversibleRecipes {

    /** The recipe set {@link #marked} was built for. */
    private static @Nullable RecipeMap builtFor;
    private static Set<ResourceKey<Recipe<?>>> marked = Set.of();

    private ReversibleRecipes() {
    }

    /**
     * One recipe as far as the reversible check needs it.
     *
     * @param id     the recipe id
     * @param result the item it makes
     * @param inputs every item any of its ingredients accepts
     */
    record Entry(ResourceKey<Recipe<?>> id, Item result, Set<Item> inputs) {
    }

    /**
     * @param level the level
     * @param id    a recipe id
     * @return whether the recipe is reversible: Mastery gives it neither saving nor extra output
     */
    public static boolean contains(ServerLevel level, ResourceKey<Recipe<?>> id) {
        RecipeMap recipes = level.recipeAccess().recipeMap();
        synchronized (ReversibleRecipes.class) {
            if (builtFor != recipes) {
                marked = mark(entries(level));
                builtFor = recipes;
            }
            return marked.contains(id);
        }
    }

    /**
     * @param entries the recipes to check
     * @return the ids of the reversible ones
     */
    static Set<ResourceKey<Recipe<?>>> mark(List<Entry> entries) {
        Map<Item, List<Entry>> byInput = new HashMap<>();
        for (Entry entry : entries) {
            for (Item input : entry.inputs()) {
                byInput.computeIfAbsent(input, key -> new ArrayList<>()).add(entry);
            }
        }
        Set<ResourceKey<Recipe<?>>> reversible = new HashSet<>();
        for (Entry recipe : entries) {
            for (Entry back : byInput.getOrDefault(recipe.result(), List.of())) {
                if (recipe.inputs().contains(back.result())) {
                    reversible.add(recipe.id());
                    break;
                }
            }
        }
        return Set.copyOf(reversible);
    }

    private static List<Entry> entries(ServerLevel level) {
        Stream<Entry> patterned = PatternRecipes.all(level).stream()
                .map(shape -> new Entry(shape.id(), shape.output().item(), items(shape.ingredients().stream())));
        Stream<Entry> innate = level.recipeAccess().recipeMap().byType(ModRecipes.JOB_CRAFTING.get()).stream()
                .filter(holder -> holder.value().result().isPresent())
                .map(ReversibleRecipes::innateEntry);
        return Stream.concat(patterned, innate).toList();
    }

    private static Entry innateEntry(RecipeHolder<JobCraftingRecipe> holder) {
        return new Entry(holder.id(), resultItem(holder.value()), items(holder.value().ingredients().stream()));
    }

    /** The item an innate recipe with a result makes. */
    private static Item resultItem(JobCraftingRecipe recipe) {
        return recipe.result().orElseThrow().item().value();
    }

    private static Set<Item> items(Stream<Ingredient> ingredients) {
        Set<Item> items = new HashSet<>();
        ingredients.flatMap(Ingredient::items).map(Holder::value).forEach(items::add);
        return items;
    }
}
