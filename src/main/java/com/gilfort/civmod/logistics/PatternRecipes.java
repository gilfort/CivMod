package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.BrewingInput;
import net.minecraft.world.item.crafting.BrewingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.PotionIngredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * The recipes a Producing Job's pattern can follow (vault {@code Systems/Producing Jobs.md}): every recipe of the
 * profile's recipe types, vanilla or modded, except special recipes and armor trims ({@code smithing_trim}, which keep
 * their base item). A brewing recipe is special for the recipe book but a pattern like any other: its cells are the
 * ingredient and the potion, its output keeps the potion. Crafting recipes keep their shape in the 3×3 grid; recipes of
 * other types fill the cells in order with their ingredients ({@link Recipe#placementInfo()}) and name their result through their
 * recipe book display. Recipes live on the server only; patterns are resolved here and the client sees the result in the
 * pattern's grid.
 */
public final class PatternRecipes {

    /** {@link #singleInput} by recipe type, for the recipe set {@link #singleInputOf}. */
    private static final Map<RecipeType<?>, Boolean> SINGLE_INPUT = new HashMap<>();
    private static @Nullable RecipeMap singleInputOf;

    private PatternRecipes() {
    }

    /**
     * A recipe laid out in the 3×3 grid.
     *
     * @param id     the recipe id
     * @param type   the recipe type
     * @param cells  the 9 cells, row by row (shaped recipes keep their shape, others fill the cells in order)
     * @param output the made thing (item and relevant components)
     * @param count  how many one execution makes
     */
    public record Shape(ResourceKey<Recipe<?>> id, RecipeType<?> type, List<Optional<Ingredient>> cells, ItemKey output, int count) {

        /**
         * @return one item per cell: the first item of each ingredient, {@link ItemKey#EMPTY} for empty cells
         */
        public List<ItemKey> grid() {
            return cells.stream().map(cell -> cell.map(PatternRecipes::representativeKey).orElse(ItemKey.EMPTY)).toList();
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
    public static List<Shape> producing(ServerLevel level, List<RecipeType<?>> types, ItemKey item) {
        List<Shape> shapes = new ArrayList<>();
        for (RecipeType<?> type : types) {
            for (RecipeHolder<?> holder : recipes(level, type)) {
                shape(level, holder).filter(shape -> shape.output().equals(item)).ifPresent(shapes::add);
            }
        }
        Identifier name = BuiltInRegistries.ITEM.getKey(item.item());
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
    public static Optional<Shape> matching(ServerLevel level, List<RecipeType<?>> types, List<ItemKey> grid) {
        if (grid.stream().allMatch(ItemKey::isEmpty)) {
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

    /**
     * @param level  the level
     * @param layout the pattern's layout
     * @param grid   9 items, row by row ({@link Items#AIR} for empty cells)
     * @return the recipe of the layout's type the items in its cells (the first 1, 2, 3 or 9) match exactly
     */
    public static Optional<Shape> matching(ServerLevel level, PatternLayout layout, List<ItemKey> grid) {
        List<ItemKey> cells = new ArrayList<>(grid);
        for (int i = layout.kind().cells(); i < cells.size(); i++) {
            cells.set(i, ItemKey.EMPTY);
        }
        return matching(level, List.of(layout.type()), cells);
    }

    /**
     * Whether a recipe type without a known layout gets the single-input layout: each of its recipes has exactly one
     * ingredient (and it has at least one). Cached per recipe set.
     *
     * @param level the level
     * @param type  a recipe type
     * @return whether its patterns get one input
     */
    static boolean singleInput(ServerLevel level, RecipeType<?> type) {
        RecipeMap map = level.recipeAccess().recipeMap();
        synchronized (SINGLE_INPUT) {
            if (singleInputOf != map) {
                SINGLE_INPUT.clear();
                singleInputOf = map;
            }
            return SINGLE_INPUT.computeIfAbsent(type, key -> {
                List<Shape> shapes = recipes(level, key).stream().map(holder -> shape(level, holder)).flatMap(Optional::stream).toList();
                return !shapes.isEmpty() && shapes.stream().allMatch(shape -> shape.ingredients().size() == 1);
            });
        }
    }

    private static Optional<Shape> matchingCrafting(ServerLevel level, List<ItemKey> grid) {
        CraftingInput input = CraftingInput.of(3, 3, grid.stream().map(ItemKey::toStack).toList());
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).flatMap(holder -> {
            ItemStack result = holder.value().assemble(input);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Shape(holder.id(), RecipeType.CRAFTING, exactCells(grid), ItemKey.of(result), result.getCount()));
        });
    }

    /** A recipe of another type matches when its ingredients accept the grid items in order. */
    private static Optional<Shape> matchingInOrder(ServerLevel level, RecipeType<?> type, List<ItemKey> grid) {
        if (type == RecipeType.BREWING) {
            return matchingBrewing(level, grid);
        }
        List<ItemKey> items = grid.stream().filter(item -> !item.isEmpty()).toList();
        return recipes(level, type).stream().sorted(Comparator.comparing(holder -> holder.id().identifier().toString()))
                .map(holder -> shape(level, holder)).flatMap(Optional::stream)
                .filter(shape -> fits(shape.ingredients(), items)).findFirst()
                .map(shape -> new Shape(shape.id(), type, exactCells(grid), shape.output(), shape.count()));
    }

    /** A brewing recipe matches when its own test accepts the ingredient (cell 0) and the potion (cell 1). */
    private static Optional<Shape> matchingBrewing(ServerLevel level, List<ItemKey> grid) {
        if (grid.get(0).isEmpty() || grid.get(1).isEmpty()) {
            return Optional.empty();
        }
        BrewingInput input = new BrewingInput(grid.get(1).toStack(), grid.get(0).toStack());
        return recipes(level, RecipeType.BREWING).stream().sorted(Comparator.comparing(holder -> holder.id().identifier().toString()))
                .filter(holder -> holder.value() instanceof BrewingRecipe brewing && brewing.matches(input))
                .map(holder -> shape(level, holder)).flatMap(Optional::stream).findFirst()
                .map(shape -> new Shape(shape.id(), RecipeType.BREWING, exactCells(grid), shape.output(), shape.count()));
    }

    private static boolean fits(List<Ingredient> ingredients, List<ItemKey> items) {
        if (ingredients.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!ingredients.get(i).test(items.get(i).toStack())) {
                return false;
            }
        }
        return true;
    }

    private static List<Optional<Ingredient>> exactCells(List<ItemKey> grid) {
        return grid.stream().map(item -> item.isEmpty() ? Optional.<Ingredient>empty() : Optional.of(ItemKeyIngredient.of(item))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends RecipeHolder<?>> recipes(ServerLevel level, RecipeType<?> type) {
        return level.recipeAccess().recipeMap().byType((RecipeType<Recipe<RecipeInput>>) type);
    }

    private static Optional<Shape> shape(ServerLevel level, RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        if (recipe instanceof BrewingRecipe brewing) {
            return brewingShape(holder.id(), brewing);
        }
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
            Optional<ItemKey> key = cell.map(PatternRecipes::representativeKey).filter(found -> !found.isEmpty());
            if (cell.isPresent() && key.isEmpty()) {
                return Optional.empty();
            }
            sample.add(key.map(ItemKey::toStack).orElse(ItemStack.EMPTY));
        }
        CraftingInput input = CraftingInput.of(3, 3, sample);
        if (!recipe.matches(input, level)) {
            return Optional.empty();
        }
        ItemStack result = recipe.assemble(input);
        return result.isEmpty() ? Optional.empty() : Optional.of(new Shape(id, RecipeType.CRAFTING, cells, ItemKey.of(result), result.getCount()));
    }

    /**
     * A brewing recipe: the ingredient in cell 0, the potion in cell 1 (the first potion of a mix that accepts several), its
     * output with the potion it makes.
     */
    private static Optional<Shape> brewingShape(ResourceKey<Recipe<?>> id, BrewingRecipe recipe) {
        Optional<ItemKey> potion = potionInput(recipe.getInput());
        ItemKey output = ItemKey.of(recipe.getOutput().create());
        if (first(recipe.getReagent().ingredient()).isEmpty() || potion.isEmpty() || output.isEmpty()) {
            return Optional.empty();
        }
        List<Optional<Ingredient>> cells = emptyCells();
        cells.set(0, Optional.of(recipe.getReagent().ingredient()));
        cells.set(1, Optional.of(ItemKeyIngredient.of(potion.get())));
        return Optional.of(new Shape(id, RecipeType.BREWING, cells, output, 1));
    }

    private static Optional<ItemKey> potionInput(PotionIngredient input) {
        Optional<Holder<Potion>> type = input.potions().flatMap(predicate -> predicate.potions()).flatMap(set -> set.stream().findFirst());
        return first(input.ingredient()).map(item -> type.map(potion -> ItemKey.of(PotionContents.createItemStack(item, potion))).orElse(ItemKey.of(item)));
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
        return result.isEmpty() ? Optional.empty() : Optional.of(new Shape(id, recipe.getType(), cells, ItemKey.of(result), result.getCount()));
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
     * @param ingredient an ingredient
     * @return the item standing for it where one item is shown or counted: its first alternative, {@link Items#AIR} if
     *         it has none
     */
    public static Item representative(Ingredient ingredient) {
        return first(ingredient).orElse(Items.AIR);
    }

    /**
     * @param ingredient an ingredient
     * @return the thing standing for it: the key of an {@link ItemKeyIngredient} with its components, else its
     *         {@link #representative item} without components, {@link ItemKey#EMPTY} if it has none
     */
    public static ItemKey representativeKey(Ingredient ingredient) {
        if (ingredient.getCustomIngredient() instanceof ItemKeyIngredient exact) {
            return exact.key();
        }
        return ItemKey.of(representative(ingredient));
    }

    /**
     * @param ingredient an ingredient
     * @return its name: a tag ingredient as "Any Wool" ({@link #tagName}), else the name of its first alternative
     */
    public static Component name(Ingredient ingredient) {
        Component first = representativeKey(ingredient).toStack().getHoverName();
        Optional<TagKey<Item>> tag = ingredient.isCustom() ? Optional.empty() : ingredient.getValues().unwrapKey();
        return tag.<Component>map(found -> Component.translatable("civmod.gui.patterns.any_of", tagName(found, first))).orElse(first);
    }

    /**
     * @param tag      a tag
     * @param fallback its name when the tag has no translation (e.g. its first member's)
     * @return its translation {@code tag.<registry>.<namespace>.<path>} (the convention of NeoForge and the vanilla
     *         tag names), else the fallback
     */
    public static Component tagName(TagKey<?> tag, Component fallback) {
        Identifier id = tag.location();
        String key = "tag." + tag.registry().identifier().getPath().replace('/', '.') + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        return Component.translatableWithFallback(key, fallback.getString());
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
