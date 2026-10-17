package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * The {@code minecraft:crafting} recipes a Craftsperson pattern can follow (vault {@code Jobs/Craftsperson.md}),
 * including modded recipes of that type. Recipes live on the server only; patterns are resolved here and the client
 * sees the result in the pattern's grid.
 */
public final class CraftingRecipes {

    private CraftingRecipes() {
    }

    /**
     * A recipe laid out in the 3×3 grid.
     *
     * @param id     the recipe id
     * @param cells  the 9 cells, row by row (shaped recipes keep their shape, others fill the cells in order)
     * @param output the crafted item
     * @param count  how many one craft makes
     */
    public record Shape(ResourceKey<Recipe<?>> id, List<Optional<Ingredient>> cells, Item output, int count) {

        /**
         * @return one item per cell: the first item of each ingredient, {@link Items#AIR} for empty cells
         */
        public List<Item> grid() {
            return cells.stream().map(cell -> cell.flatMap(CraftingRecipes::first).orElse(Items.AIR)).toList();
        }

        /**
         * @return the ingredients of one craft (empty cells left out)
         */
        public List<Ingredient> ingredients() {
            return cells.stream().flatMap(Optional::stream).toList();
        }
    }

    /**
     * @param level the level
     * @param item  an item
     * @return the crafting recipes making it; the first one is the default: the recipe named like the item, then by id
     */
    public static List<Shape> producing(ServerLevel level, Item item) {
        List<Shape> shapes = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.recipeAccess().recipeMap().byType(RecipeType.CRAFTING)) {
            shape(level, holder).filter(shape -> shape.output() == item).ifPresent(shapes::add);
        }
        Identifier name = BuiltInRegistries.ITEM.getKey(item);
        shapes.sort(Comparator.<Shape>comparingInt(shape -> shape.id().identifier().equals(name) ? 0 : 1)
                .thenComparing(shape -> shape.id().identifier().toString()));
        return shapes;
    }

    /**
     * @param level the level
     * @param id    a recipe id
     * @return that crafting recipe in the grid, empty if it is gone or no crafting recipe
     */
    public static Optional<Shape> byKey(ServerLevel level, ResourceKey<Recipe<?>> id) {
        return level.recipeAccess().byKey(id).flatMap(holder -> holder.value() instanceof CraftingRecipe recipe
                ? shape(level, new RecipeHolder<>(holder.id(), recipe)) : Optional.empty());
    }

    /**
     * @param level the level
     * @param grid  9 items, row by row ({@link Items#AIR} for empty cells)
     * @return the crafting recipe these exact items match, with its output
     */
    public static Optional<Shape> matching(ServerLevel level, List<Item> grid) {
        if (grid.stream().allMatch(item -> item == Items.AIR)) {
            return Optional.empty();
        }
        CraftingInput input = CraftingInput.of(3, 3, grid.stream().map(ItemStack::new).toList());
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).flatMap(holder -> {
            ItemStack result = holder.value().assemble(input);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            List<Optional<Ingredient>> cells = grid.stream().map(item -> item == Items.AIR ? Optional.<Ingredient>empty()
                    : Optional.of(Ingredient.of(item))).toList();
            return Optional.of(new Shape(holder.id(), cells, result.getItem(), result.getCount()));
        });
    }

    private static Optional<Shape> shape(ServerLevel level, RecipeHolder<CraftingRecipe> holder) {
        CraftingRecipe recipe = holder.value();
        if (recipe.isSpecial()) {
            return Optional.empty();
        }
        List<Optional<Ingredient>> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            cells.add(Optional.empty());
        }
        if (recipe instanceof ShapedRecipe shaped) {
            if (shaped.getWidth() > 3 || shaped.getHeight() > 3) {
                return Optional.empty();
            }
            List<Optional<Ingredient>> ingredients = shaped.pattern.ingredients();
            for (int y = 0; y < shaped.getHeight(); y++) {
                for (int x = 0; x < shaped.getWidth(); x++) {
                    cells.set(y * 3 + x, ingredients.get(y * shaped.getWidth() + x));
                }
            }
        } else {
            PlacementInfo placement = recipe.placementInfo();
            if (placement.isImpossibleToPlace() || placement.ingredients().size() > 9) {
                return Optional.empty();
            }
            for (int i = 0; i < placement.ingredients().size(); i++) {
                cells.set(i, Optional.of(placement.ingredients().get(i)));
            }
        }
        List<ItemStack> sample = new ArrayList<>();
        for (Optional<Ingredient> cell : cells) {
            Optional<Item> item = cell.flatMap(CraftingRecipes::first);
            if (cell.isPresent() && item.isEmpty()) {
                return Optional.empty();
            }
            sample.add(item.map(ItemStack::new).orElse(ItemStack.EMPTY));
        }
        CraftingInput input = CraftingInput.of(3, 3, sample);
        if (!recipe.matches(input, level)) {
            return Optional.empty();
        }
        ItemStack result = recipe.assemble(input);
        return result.isEmpty() ? Optional.empty() : Optional.of(new Shape(holder.id(), cells, result.getItem(), result.getCount()));
    }

    private static Optional<Item> first(Ingredient ingredient) {
        return ingredient.items().findFirst().map(Holder::value);
    }

    /**
     * What stays in the grid after one craft (empty buckets, bottles, ...).
     *
     * @param consumed the items used in one craft
     * @return the remainders (empty stacks left out)
     */
    public static List<ItemStack> remainders(List<ItemStack> consumed) {
        List<ItemStack> padded = new ArrayList<>(consumed);
        while (padded.size() < 9) {
            padded.add(ItemStack.EMPTY);
        }
        return CraftingRecipe.defaultCraftingReminder(CraftingInput.of(3, 3, padded.subList(0, 9))).stream()
                .filter(stack -> !stack.isEmpty()).toList();
    }
}
