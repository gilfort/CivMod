package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Collection;
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
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * The recipes a Producing Job's pattern can follow (vault {@code Systems/Producing Jobs.md}): every recipe of the
 * profile's recipe types, vanilla or modded, except special recipes and armor trims ({@code smithing_trim}, which keep
 * their base item). Crafting recipes keep their shape in the 3×3 grid; recipes of other types
 * fill the cells in order with their ingredients ({@link Recipe#placementInfo()}) and name their result through their
 * recipe book display. Recipes live on the server only; patterns are resolved here and the client sees the result in the
 * pattern's grid.
 */
public final class PatternRecipes {

    private PatternRecipes() {
    }

    /**
     * A recipe laid out in the 3×3 grid.
     *
     * @param id     the recipe id
     * @param type   the recipe type
     * @param cells  the 9 cells, row by row (shaped recipes keep their shape, others fill the cells in order)
     * @param output the made item
     * @param count  how many one execution makes
     */
    public record Shape(ResourceKey<Recipe<?>> id, RecipeType<?> type, List<Optional<Ingredient>> cells, Item output, int count) {

        /**
         * @return one item per cell: the first item of each ingredient, {@link Items#AIR} for empty cells
         */
        public List<Item> grid() {
            return cells.stream().map(cell -> cell.flatMap(PatternRecipes::first).orElse(Items.AIR)).toList();
        }

        /**
         * @return the ingredients of one execution (empty cells left out)
         */
        public List<Ingredient> ingredients() {
            return cells.stream().flatMap(Optional::stream).toList();
        }
    }

    /**
     * @param level the level
     * @param types the recipe types, in order
     * @param item  an item
     * @return the recipes of those types making it; the first one is the default: the first type's, the recipe named like
     *         the item, then by id
     */
    public static List<Shape> producing(ServerLevel level, List<RecipeType<?>> types, Item item) {
        List<Shape> shapes = new ArrayList<>();
        for (RecipeType<?> type : types) {
            for (RecipeHolder<?> holder : recipes(level, type)) {
                shape(level, holder).filter(shape -> shape.output() == item).ifPresent(shapes::add);
            }
        }
        Identifier name = BuiltInRegistries.ITEM.getKey(item);
        shapes.sort(Comparator.<Shape>comparingInt(shape -> types.indexOf(shape.type()))
                .thenComparingInt(shape -> shape.id().identifier().equals(name) ? 0 : 1)
                .thenComparing(shape -> shape.id().identifier().toString()));
        return shapes;
    }

    /**
     * @param level the level
     * @param types the recipe types
     * @param id    a recipe id
     * @return that recipe in the grid, empty if it is gone or of another type
     */
    public static Optional<Shape> byKey(ServerLevel level, List<RecipeType<?>> types, ResourceKey<Recipe<?>> id) {
        return holder(level, id).filter(holder -> types.contains(holder.value().getType())).flatMap(holder -> shape(level, holder));
    }

    /**
     * @param level the level
     * @param id    a recipe id
     * @return the recipe, empty if it is gone
     */
    public static Optional<RecipeHolder<?>> holder(ServerLevel level, ResourceKey<Recipe<?>> id) {
        return level.recipeAccess().byKey(id);
    }

    /**
     * @param level the level
     * @param types the recipe types, in order
     * @param grid  9 items, row by row ({@link Items#AIR} for empty cells)
     * @return the recipe of the first type these exact items match, with its output
     */
    public static Optional<Shape> matching(ServerLevel level, List<RecipeType<?>> types, List<Item> grid) {
        if (grid.stream().allMatch(item -> item == Items.AIR)) {
            return Optional.empty();
        }
        for (RecipeType<?> type : types) {
            Optional<Shape> found = type == RecipeType.CRAFTING ? matchingCrafting(level, grid) : matchingInOrder(level, type, grid);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Shape> matchingCrafting(ServerLevel level, List<Item> grid) {
        CraftingInput input = CraftingInput.of(3, 3, grid.stream().map(ItemStack::new).toList());
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).flatMap(holder -> {
            ItemStack result = holder.value().assemble(input);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Shape(holder.id(), RecipeType.CRAFTING, exactCells(grid), result.getItem(), result.getCount()));
        });
    }

    /** A recipe of another type matches when its ingredients accept the grid items in order. */
    private static Optional<Shape> matchingInOrder(ServerLevel level, RecipeType<?> type, List<Item> grid) {
        List<Item> items = grid.stream().filter(item -> item != Items.AIR).toList();
        return recipes(level, type).stream().sorted(Comparator.comparing(holder -> holder.id().identifier().toString()))
                .map(holder -> shape(level, holder)).flatMap(Optional::stream)
                .filter(shape -> fits(shape.ingredients(), items)).findFirst()
                .map(shape -> new Shape(shape.id(), type, exactCells(grid), shape.output(), shape.count()));
    }

    private static boolean fits(List<Ingredient> ingredients, List<Item> items) {
        if (ingredients.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!ingredients.get(i).test(new ItemStack(items.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static List<Optional<Ingredient>> exactCells(List<Item> grid) {
        return grid.stream().map(item -> item == Items.AIR ? Optional.<Ingredient>empty() : Optional.of(Ingredient.of(item))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends RecipeHolder<?>> recipes(ServerLevel level, RecipeType<?> type) {
        return level.recipeAccess().recipeMap().byType((RecipeType<Recipe<RecipeInput>>) type);
    }

    private static Optional<Shape> shape(ServerLevel level, RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        // An armor trim keeps its base item and changes only its components: no pattern (vault Jobs/Smith.md).
        if (recipe.isSpecial() || recipe instanceof SmithingTrimRecipe) {
            return Optional.empty();
        }
        return recipe instanceof CraftingRecipe crafting ? craftingShape(level, holder.id(), crafting) : inOrderShape(level, holder.id(), recipe);
    }

    private static Optional<Shape> craftingShape(ServerLevel level, ResourceKey<Recipe<?>> id, CraftingRecipe recipe) {
        List<Optional<Ingredient>> cells = emptyCells();
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
        } else if (!fillInOrder(cells, recipe.placementInfo())) {
            return Optional.empty();
        }
        List<ItemStack> sample = new ArrayList<>();
        for (Optional<Ingredient> cell : cells) {
            Optional<Item> item = cell.flatMap(PatternRecipes::first);
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
        return result.isEmpty() ? Optional.empty() : Optional.of(new Shape(id, RecipeType.CRAFTING, cells, result.getItem(), result.getCount()));
    }

    /** A recipe of another type: its ingredients in order, its result from its first recipe book display. */
    private static Optional<Shape> inOrderShape(ServerLevel level, ResourceKey<Recipe<?>> id, Recipe<?> recipe) {
        List<Optional<Ingredient>> cells = emptyCells();
        List<RecipeDisplay> displays = recipe.display();
        if (displays.isEmpty() || !fillInOrder(cells, recipe.placementInfo()) || cells.stream().allMatch(Optional::isEmpty)
                || cells.stream().flatMap(Optional::stream).anyMatch(ingredient -> first(ingredient).isEmpty())) {
            return Optional.empty();
        }
        ItemStack result = displays.getFirst().result().resolveForFirstStack(SlotDisplayContext.fromLevel(level));
        return result.isEmpty() ? Optional.empty() : Optional.of(new Shape(id, recipe.getType(), cells, result.getItem(), result.getCount()));
    }

    private static boolean fillInOrder(List<Optional<Ingredient>> cells, PlacementInfo placement) {
        if (placement.isImpossibleToPlace() || placement.ingredients().size() > cells.size()) {
            return false;
        }
        for (int i = 0; i < placement.ingredients().size(); i++) {
            cells.set(i, Optional.of(placement.ingredients().get(i)));
        }
        return true;
    }

    private static List<Optional<Ingredient>> emptyCells() {
        List<Optional<Ingredient>> cells = new ArrayList<>();
        for (int i = 0; i < Pattern.GRID; i++) {
            cells.add(Optional.empty());
        }
        return cells;
    }

    private static Optional<Item> first(Ingredient ingredient) {
        return ingredient.items().findFirst().map(Holder::value);
    }

    /**
     * What stays after one execution (empty buckets, bottles, ...).
     *
     * @param consumed the items used in one execution
     * @return the remainders (empty stacks left out)
     */
    public static List<ItemStack> remainders(List<ItemStack> consumed) {
        List<ItemStack> padded = new ArrayList<>(consumed);
        while (padded.size() < Pattern.GRID) {
            padded.add(ItemStack.EMPTY);
        }
        return CraftingRecipe.defaultCraftingReminder(CraftingInput.of(3, 3, padded.subList(0, Pattern.GRID))).stream()
                .filter(stack -> !stack.isEmpty()).toList();
    }
}
