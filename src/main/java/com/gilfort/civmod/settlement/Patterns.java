package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.logistics.CraftingRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/**
 * The Craftsperson's pattern library (vault {@code Jobs/Craftsperson.md}), kept in its {@link SettlementBlock}: 54
 * pattern slots, of which {@link #checkLimit} can be checked. A pattern is made from its output alone (the first matching
 * crafting recipe fills the grid, arrows cycle the alternatives) or from exact inputs (the output follows the recipe they
 * match), or by a recipe viewer's transfer button.
 */
public final class Patterns {

    /** Checkmarks below skill level 5. */
    public static final int BASE_CHECKMARKS = 6;
    /** One more checkmark per this many skill levels. */
    private static final int LEVELS_PER_CHECKMARK = 5;

    private Patterns() {
    }

    /**
     * @param effectiveLevel the settler's effective Craftsperson skill
     * @return how many patterns it can use: 6 + 1 per 5 levels
     */
    public static int checkLimit(int effectiveLevel) {
        return BASE_CHECKMARKS + Math.max(0, effectiveLevel) / LEVELS_PER_CHECKMARK;
    }

    /**
     * @param block a JobBlock
     * @return whether it is a Craftsperson JobBlock
     */
    public static boolean isCraftsperson(SettlementBlock block) {
        return block.jobType() == ModJobs.CRAFTSPERSON.get();
    }

    /**
     * @param block a Craftsperson JobBlock
     * @param slot  a library slot
     * @return the pattern in that slot
     */
    public static Optional<Pattern> pattern(SettlementBlock block, int slot) {
        return block.patterns().stream().filter(pattern -> pattern.slot() == slot).findFirst();
    }

    /**
     * @param block a Craftsperson JobBlock
     * @return its checked patterns that make something, in list order
     */
    public static List<Pattern> checked(SettlementBlock block) {
        return block.patterns().stream().filter(pattern -> pattern.checked() && pattern.valid()).toList();
    }

    /**
     * Makes the pattern in a slot from its output: the first crafting recipe making the item fills the grid (tag
     * ingredients stay flexible). The checkmark stays.
     *
     * @param level  the level
     * @param pos    the Craftsperson JobBlock
     * @param slot   the library slot
     * @param output the item to craft
     * @return the new pattern, empty if no crafting recipe makes the item
     */
    public static Optional<Pattern> setOutput(ServerLevel level, BlockPos pos, int slot, Item output) {
        List<CraftingRecipes.Shape> shapes = CraftingRecipes.producing(level, output);
        return shapes.isEmpty() ? Optional.empty() : put(level, pos, slot, recipePattern(slot, shapes.getFirst()));
    }

    /**
     * Cycles a recipe pattern to the next (or previous) crafting recipe making its output.
     *
     * @param level     the level
     * @param pos       the Craftsperson JobBlock
     * @param slot      the library slot
     * @param direction +1 next, -1 previous
     * @return the new pattern, empty if the slot holds no recipe pattern
     */
    public static Optional<Pattern> cycle(ServerLevel level, BlockPos pos, int slot, int direction) {
        Optional<Pattern> current = block(level, pos).flatMap(block -> pattern(block, slot)).filter(found -> found.valid());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        List<CraftingRecipes.Shape> shapes = CraftingRecipes.producing(level, current.get().output());
        if (shapes.isEmpty()) {
            return Optional.empty();
        }
        int index = 0;
        for (int i = 0; i < shapes.size(); i++) {
            if (current.get().recipe().filter(shapes.get(i).id()::equals).isPresent()) {
                index = i;
            }
        }
        CraftingRecipes.Shape next = shapes.get(Math.floorMod(index + Integer.signum(direction), shapes.size()));
        return put(level, pos, slot, recipePattern(slot, next));
    }

    /**
     * Sets one input of a pattern: the pattern uses exactly its grid items from now on, its output follows the recipe
     * they match ({@link Items#AIR} while they match none).
     *
     * @param level the level
     * @param pos   the Craftsperson JobBlock
     * @param slot  the library slot
     * @param index the grid cell (0–8)
     * @param item  the item, {@link Items#AIR} to empty the cell
     * @return the new pattern, empty if the JobBlock is no Craftsperson
     */
    public static Optional<Pattern> setInput(ServerLevel level, BlockPos pos, int slot, int index, Item item) {
        if (index < 0 || index >= Pattern.GRID) {
            return Optional.empty();
        }
        List<Item> grid = new ArrayList<>(block(level, pos).flatMap(block -> pattern(block, slot)).map(Pattern::grid)
                .orElse(List.of()));
        while (grid.size() < Pattern.GRID) {
            grid.add(Items.AIR);
        }
        grid.set(index, item);
        return put(level, pos, slot, exactPattern(level, slot, grid));
    }

    /**
     * Fills a pattern from a recipe viewer's transfer button: the recipe if the server knows it as a crafting recipe,
     * otherwise exactly the shown items.
     *
     * @param level  the level
     * @param pos    the Craftsperson JobBlock
     * @param slot   the library slot
     * @param recipe the recipe id the viewer showed
     * @param grid   the shown items, row by row
     * @return the new pattern
     */
    public static Optional<Pattern> transfer(ServerLevel level, BlockPos pos, int slot, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid) {
        Optional<CraftingRecipes.Shape> shape = recipe.flatMap(id -> CraftingRecipes.byKey(level, id));
        return put(level, pos, slot, shape.map(found -> recipePattern(slot, found)).orElseGet(() -> exactPattern(level, slot, grid)));
    }

    /**
     * Checks or unchecks a pattern; checking needs a pattern that makes something and a free checkmark.
     *
     * @param data the dimension's settlement data
     * @param pos  the Craftsperson JobBlock
     * @param slot the library slot
     * @return whether the pattern is checked afterwards
     */
    public static boolean toggleCheck(SettlementData data, BlockPos pos, int slot) {
        Optional<SettlementBlock> found = block(data, pos);
        Optional<Pattern> pattern = found.flatMap(block -> pattern(block, slot));
        if (pattern.isEmpty()) {
            return false;
        }
        SettlementBlock block = found.get();
        boolean check = !pattern.get().checked();
        if (check && (!pattern.get().valid() || checked(block).size() >= block.checkLimit())) {
            return false;
        }
        replace(data, block, pattern.get().withChecked(check));
        return check;
    }

    /**
     * Empties a library slot.
     *
     * @param data the dimension's settlement data
     * @param pos  the Craftsperson JobBlock
     * @param slot the library slot
     */
    public static void clear(SettlementData data, BlockPos pos, int slot) {
        block(data, pos).ifPresent(block -> {
            if (block.mutablePatterns().removeIf(pattern -> pattern.slot() == slot)) {
                block.setTrim(Optional.empty());
                data.setDirty();
            }
        });
    }

    /**
     * Applies the checkmark limit of the settler working the JobBlock: if it dropped, the last checked patterns in list
     * order lose their checkmark until the count fits, and the GUI shows how many were removed.
     *
     * @param data  the dimension's settlement data
     * @param pos   the Craftsperson JobBlock
     * @param limit the checkmark limit
     * @return how many checkmarks were removed
     */
    public static int enforceLimit(SettlementData data, BlockPos pos, int limit) {
        Optional<SettlementBlock> found = block(data, pos);
        if (found.isEmpty()) {
            return 0;
        }
        SettlementBlock block = found.get();
        if (block.checkLimit() != limit) {
            block.setCheckLimit(limit);
            data.setDirty();
        }
        List<Pattern> checked = checked(block);
        int removed = Math.max(0, checked.size() - limit);
        for (Pattern pattern : checked.subList(checked.size() - removed, checked.size())) {
            replace(data, block, pattern.withChecked(false));
        }
        if (removed > 0) {
            block.setTrim(Optional.of(new SettlementBlock.Trim(removed, limit)));
            data.setDirty();
        }
        return removed;
    }

    /**
     * The inputs of one craft of a pattern: exactly its grid items, or the ingredients of its recipe (tags flexible). A
     * recipe that is gone falls back to the grid items.
     *
     * @param level   the level
     * @param pattern a pattern
     * @return the ingredients, one per used grid cell
     */
    public static List<Ingredient> inputs(ServerLevel level, Pattern pattern) {
        if (!pattern.exact()) {
            Optional<CraftingRecipes.Shape> shape = pattern.recipe().flatMap(id -> CraftingRecipes.byKey(level, id));
            if (shape.isPresent()) {
                return shape.get().ingredients();
            }
        }
        return pattern.grid().stream().filter(item -> item != Items.AIR).map(item -> Ingredient.of(item)).toList();
    }

    private static Pattern recipePattern(int slot, CraftingRecipes.Shape shape) {
        return new Pattern(slot, shape.output(), shape.count(), shape.grid(), Optional.of(shape.id()), false, false);
    }

    private static Pattern exactPattern(ServerLevel level, int slot, List<Item> grid) {
        Optional<CraftingRecipes.Shape> shape = CraftingRecipes.matching(level, grid);
        return new Pattern(slot, shape.map(CraftingRecipes.Shape::output).orElse(Items.AIR), shape.map(CraftingRecipes.Shape::count).orElse(1),
                grid, shape.map(CraftingRecipes.Shape::id), true, false);
    }

    /** Puts a pattern into its slot, keeping the slot's checkmark if the new pattern makes something. */
    private static Optional<Pattern> put(ServerLevel level, BlockPos pos, int slot, Pattern pattern) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos);
        if (found.isEmpty() || slot < 0 || slot >= Pattern.SLOTS) {
            return Optional.empty();
        }
        boolean checked = pattern(found.get(), slot).map(Pattern::checked).orElse(false) && pattern.valid();
        Pattern placed = pattern.withChecked(checked);
        replace(data, found.get(), placed);
        found.get().setTrim(Optional.empty());
        return Optional.of(placed);
    }

    private static void replace(SettlementData data, SettlementBlock block, Pattern pattern) {
        List<Pattern> patterns = block.mutablePatterns();
        patterns.removeIf(existing -> existing.slot() == pattern.slot());
        patterns.add(pattern);
        patterns.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        data.setDirty();
    }

    private static Optional<SettlementBlock> block(ServerLevel level, BlockPos pos) {
        return block(SettlementData.get(level), pos);
    }

    private static Optional<SettlementBlock> block(SettlementData data, BlockPos pos) {
        return data.settlementAt(pos).flatMap(settlement -> settlement.block(pos)).filter(Patterns::isCraftsperson);
    }
}
