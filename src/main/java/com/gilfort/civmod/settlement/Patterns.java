package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * The pattern library of a Producing JobBlock (vault {@code Systems/Producing Jobs.md}, {@code Jobs/Craftsperson.md}),
 * kept in its {@link SettlementBlock}: 54 pattern slots, of which {@link SettlementBlock#checkLimit()} can be checked. A
 * pattern is made from its output alone (the first matching recipe of the profile's recipe types fills the grid, arrows
 * cycle the alternatives) or from exact inputs (the output follows the recipe they match), or by a recipe viewer's
 * transfer button.
 * <p>
 * The library follows the {@link ProducingProfile} of the Job working the JobBlock (its assignee, else the JobBlock's
 * own Job): a pattern is usable if its recipe is of one of the profile's types and the profile's filters let it through
 * (the Craftsperson's filter denies metal, item tag {@code civmod:metals}); rejected alternatives of a tag input are
 * skipped. A pattern of another recipe type ("foreign", e.g. a crafting pattern while a Stonecutter works the JobBlock)
 * keeps its checkmark: it is skipped and does not count against the limit, and works again once its Job does. A
 * filtered pattern loses its checkmark. A new pattern is checked automatically while the limit allows.
 */
public final class Patterns {

    /** Checkmarks below skill level 5. */
    public static final int BASE_CHECKMARKS = 6;
    /** Items the Craftsperson never crafts with: ingots, nuggets, raw materials and metal blocks. */
    public static final TagKey<Item> METALS = TagKey.create(Registries.ITEM, CivMod.id("metals"));
    /** One more checkmark per this many skill levels. */
    private static final int LEVELS_PER_CHECKMARK = 5;

    private Patterns() {
    }

    /**
     * @param effectiveLevel the settler's effective skill
     * @return the default checkmark limit of a Producing Job: 6 + 1 per 5 levels
     */
    public static int checkLimit(int effectiveLevel) {
        return BASE_CHECKMARKS + Math.max(0, effectiveLevel) / LEVELS_PER_CHECKMARK;
    }

    /**
     * @param block a JobBlock
     * @return whether it offers a Producing Job (and so keeps a pattern library)
     */
    public static boolean isProducing(SettlementBlock block) {
        return block.jobType().category() == JobCategory.PRODUCING;
    }

    /**
     * @param block a Producing JobBlock
     * @return the Producing Job working it: its assignee's, else the JobBlock's own Job
     */
    public static Job producer(SettlementBlock block) {
        return block.assignees().stream().map(SettlementBlock.Assignee::job).filter(job -> job.category() == JobCategory.PRODUCING).findFirst()
                .orElse(block.jobType());
    }

    /**
     * @param block a Producing JobBlock
     * @return the profile its library follows (of the {@link #producer})
     */
    public static ProducingProfile profile(SettlementBlock block) {
        return profile(producer(block));
    }

    /**
     * @param job a Producing Job
     * @return its profile
     */
    public static ProducingProfile profile(Job job) {
        return job.profile(ProducingProfile.class).orElseThrow(() -> new IllegalStateException(job + " has no Producing profile"));
    }

    /**
     * @return the recipe types of every registered Producing Job's profile, each once (the recipe types recipe viewers
     *         offer the transfer button for)
     */
    public static List<RecipeType<?>> allRecipeTypes() {
        return ModJobs.REGISTRY.stream().flatMap(job -> job.profile(ProducingProfile.class).stream())
                .flatMap(profile -> profile.recipeTypes().stream()).distinct().toList();
    }

    /**
     * @param block a Producing JobBlock
     * @param slot  a library slot
     * @return the pattern in that slot
     */
    public static Optional<Pattern> pattern(SettlementBlock block, int slot) {
        return block.patterns().stream().filter(pattern -> pattern.slot() == slot).findFirst();
    }

    /**
     * @param block a Producing JobBlock
     * @return its checked patterns that make something, in list order
     */
    public static List<Pattern> checked(SettlementBlock block) {
        return block.patterns().stream().filter(pattern -> pattern.checked() && pattern.valid()).toList();
    }

    /**
     * @param level the level
     * @param block a Producing JobBlock
     * @return its checked patterns its producer can use (of the producer's recipe types, passing its filters), in list
     *         order; only these count against the checkmark limit
     */
    public static List<Pattern> usable(ServerLevel level, SettlementBlock block) {
        ProducingProfile profile = profile(block);
        return checked(block).stream().filter(pattern -> allowed(level, profile, pattern)).toList();
    }

    /**
     * @param item an item
     * @return whether it is metal (item tag {@code civmod:metals}, the Craftsperson's filter)
     */
    public static boolean isMetal(Item item) {
        return item.getDefaultInstance().is(METALS);
    }

    /**
     * @param level   the level
     * @param profile a Producing profile
     * @param pattern a pattern
     * @return whether the profile can use it: it makes something, follows a recipe of the profile's types and passes its
     *         filters
     */
    static boolean allowed(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        return pattern.valid() && !foreign(level, profile, pattern) && !filtered(level, profile, pattern);
    }

    /**
     * @param level the level
     * @param block a Producing JobBlock
     * @param pattern a pattern
     * @return whether its producer cannot use the pattern although it makes something (foreign recipe type or filtered)
     */
    public static boolean unusable(ServerLevel level, SettlementBlock block, Pattern pattern) {
        ProducingProfile profile = profile(block);
        return pattern.valid() && !allowed(level, profile, pattern);
    }

    /**
     * @param level   the level
     * @param profile a Producing profile
     * @param pattern a pattern
     * @return whether it follows a recipe of another type than the profile's (a recipe that is gone counts as none)
     */
    public static boolean foreign(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        return pattern.valid() && pattern.recipe().flatMap(id -> PatternRecipes.holder(level, id))
                .filter(holder -> !profile.recipeTypes().contains(holder.value().getType())).isPresent();
    }

    /**
     * @param level   the level
     * @param profile a Producing profile
     * @param pattern a pattern
     * @return whether the profile's filters reject its output or an input (all its alternatives)
     */
    public static boolean filtered(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        return pattern.valid() && (!profile.outputFilter().test(pattern.output())
                || allInputs(level, profile, pattern).stream().anyMatch(input -> input.items().noneMatch(profile.inputFilter()::test)));
    }

    /**
     * Makes the pattern in a slot from its output: the first recipe of the profile's types making the item fills the
     * grid (tag ingredients stay flexible). A replaced pattern's checkmark stays; a new one is checked automatically.
     *
     * @param level  the level
     * @param pos    the Producing JobBlock
     * @param slot   the library slot
     * @param output the item to make
     * @return the new pattern, empty if no recipe makes the item
     */
    public static Optional<Pattern> setOutput(ServerLevel level, BlockPos pos, int slot, Item output) {
        Optional<SettlementBlock> block = block(level, pos);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        List<PatternRecipes.Shape> shapes = PatternRecipes.producing(level, profile(block.get()).recipeTypes(), output);
        return shapes.isEmpty() ? Optional.empty() : put(level, pos, slot, recipePattern(slot, shapes.getFirst()));
    }

    /**
     * Cycles a recipe pattern to the next (or previous) recipe making its output.
     *
     * @param level     the level
     * @param pos       the Producing JobBlock
     * @param slot      the library slot
     * @param direction +1 next, -1 previous
     * @return the new pattern, empty if the slot holds no recipe pattern
     */
    public static Optional<Pattern> cycle(ServerLevel level, BlockPos pos, int slot, int direction) {
        Optional<SettlementBlock> block = block(level, pos);
        Optional<Pattern> current = block.flatMap(found -> pattern(found, slot)).filter(Pattern::valid);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        List<PatternRecipes.Shape> shapes = PatternRecipes.producing(level, profile(block.get()).recipeTypes(), current.get().output());
        if (shapes.isEmpty()) {
            return Optional.empty();
        }
        int index = 0;
        for (int i = 0; i < shapes.size(); i++) {
            if (current.get().recipe().filter(shapes.get(i).id()::equals).isPresent()) {
                index = i;
            }
        }
        PatternRecipes.Shape next = shapes.get(Math.floorMod(index + Integer.signum(direction), shapes.size()));
        return put(level, pos, slot, recipePattern(slot, next));
    }

    /**
     * Sets one input of a pattern: the pattern uses exactly its grid items from now on, its output follows the recipe
     * they match ({@link Items#AIR} while they match none).
     *
     * @param level the level
     * @param pos   the Producing JobBlock
     * @param slot  the library slot
     * @param index the grid cell (0–8)
     * @param item  the item, {@link Items#AIR} to empty the cell
     * @return the new pattern, empty if the JobBlock is no Producing JobBlock
     */
    public static Optional<Pattern> setInput(ServerLevel level, BlockPos pos, int slot, int index, Item item) {
        Optional<SettlementBlock> block = block(level, pos);
        if (index < 0 || index >= Pattern.GRID || block.isEmpty()) {
            return Optional.empty();
        }
        List<Item> grid = new ArrayList<>(pattern(block.get(), slot).map(Pattern::grid).orElse(List.of()));
        while (grid.size() < Pattern.GRID) {
            grid.add(Items.AIR);
        }
        grid.set(index, item);
        return put(level, pos, slot, exactPattern(level, profile(block.get()), slot, grid));
    }

    /**
     * Fills a pattern from a recipe viewer's transfer button: the recipe if the server knows it as a recipe of the
     * profile's types, otherwise exactly the shown items (padded to the 3×3 grid). A known recipe of a type the producer
     * does not follow is refused.
     *
     * @param level  the level
     * @param pos    the Producing JobBlock
     * @param slot   the library slot
     * @param recipe the recipe id the viewer showed
     * @param grid   the shown items, row by row
     * @return the new pattern, empty if refused
     */
    public static Optional<Pattern> transfer(ServerLevel level, BlockPos pos, int slot, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid) {
        Optional<SettlementBlock> block = block(level, pos);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        ProducingProfile profile = profile(block.get());
        if (recipe.flatMap(id -> PatternRecipes.holder(level, id)).filter(holder -> !profile.recipeTypes().contains(holder.value().getType())).isPresent()) {
            // "Not a recipe this Job follows": the viewer's button is disabled for it, a stale client must not get around that.
            return Optional.empty();
        }
        Optional<PatternRecipes.Shape> shape = recipe.flatMap(id -> PatternRecipes.byKey(level, profile.recipeTypes(), id));
        List<Item> cells = new ArrayList<>(grid.subList(0, Math.min(Pattern.GRID, grid.size())));
        while (cells.size() < Pattern.GRID) {
            // Recipes of other types than crafting show fewer inputs than the 3×3 grid.
            cells.add(Items.AIR);
        }
        return put(level, pos, slot, shape.map(found -> recipePattern(slot, found)).orElseGet(() -> exactPattern(level, profile, slot, cells)));
    }

    /**
     * Checks or unchecks a pattern; checking needs a pattern its producer can use and a free checkmark (counted over
     * the {@link #usable} patterns). Every toggle ends the state line ({@link SettlementBlock#notice()}).
     *
     * @param level the level
     * @param pos   the Producing JobBlock
     * @param slot  the library slot
     * @return whether the pattern is checked afterwards
     */
    public static boolean toggleCheck(ServerLevel level, BlockPos pos, int slot) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos);
        Optional<Pattern> pattern = found.flatMap(block -> pattern(block, slot));
        if (pattern.isEmpty()) {
            return false;
        }
        SettlementBlock block = found.get();
        block.setNotice(Optional.empty());
        boolean check = !pattern.get().checked();
        if (check && (!allowed(level, profile(block), pattern.get()) || usable(level, block).size() >= block.checkLimit())) {
            return false;
        }
        replace(data, block, pattern.get().withChecked(check));
        return check;
    }

    /**
     * Empties a library slot.
     *
     * @param data the dimension's settlement data
     * @param pos  the Producing JobBlock
     * @param slot the library slot
     */
    public static void clear(SettlementData data, BlockPos pos, int slot) {
        block(data, pos).ifPresent(block -> {
            if (block.mutablePatterns().removeIf(pattern -> pattern.slot() == slot)) {
                block.setNotice(Optional.empty());
                data.setDirty();
            }
        });
    }

    /**
     * Applies the checkmark limit of the settler working the JobBlock: if it dropped, the last {@link #usable} patterns in
     * list order lose their checkmark until the count fits, and the GUI shows how many were removed. Foreign patterns
     * (another Job's recipe type) keep theirs.
     *
     * @param level the level
     * @param pos   the Producing JobBlock
     * @param limit the checkmark limit
     * @return how many checkmarks were removed
     */
    public static int enforceLimit(ServerLevel level, BlockPos pos, int limit) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos);
        if (found.isEmpty()) {
            return 0;
        }
        SettlementBlock block = found.get();
        if (block.checkLimit() != limit) {
            block.setCheckLimit(limit);
            data.setDirty();
        }
        List<Pattern> checked = usable(level, block);
        int removed = Math.max(0, checked.size() - limit);
        for (Pattern pattern : checked.subList(checked.size() - removed, checked.size())) {
            replace(data, block, pattern.withChecked(false));
        }
        if (removed > 0) {
            block.setNotice(Optional.of(new SettlementBlock.Notice.Trimmed(removed, limit)));
            data.setDirty();
        }
        return removed;
    }

    /**
     * Removes the checkmark of every pattern of its producer's recipe types that the producer's filters reject (e.g.
     * metal for the Craftsperson): such a pattern is never checked, also when it was checked in an older save or before
     * a filter tag changed. Foreign patterns (another Job's recipe type) keep their checkmark for that Job.
     *
     * @param level the level
     * @param pos   the Producing JobBlock
     * @return how many checkmarks were removed
     */
    public static int uncheckFiltered(ServerLevel level, BlockPos pos) {
        SettlementData data = SettlementData.get(level);
        return block(data, pos).map(block -> uncheckFiltered(level, data, block)).orElse(0);
    }

    /**
     * @param level the level
     * @param data  the dimension's settlement data (marked dirty on a change)
     * @param block a Producing JobBlock
     * @return how many checkmarks of filtered patterns were removed
     * @see #uncheckFiltered(ServerLevel, BlockPos)
     */
    public static int uncheckFiltered(ServerLevel level, SettlementData data, SettlementBlock block) {
        ProducingProfile profile = profile(block);
        List<Pattern> filtered = block.patterns().stream()
                .filter(pattern -> pattern.checked() && !foreign(level, profile, pattern) && filtered(level, profile, pattern)).toList();
        filtered.forEach(pattern -> replace(data, block, pattern.withChecked(false)));
        return filtered.size();
    }

    /**
     * The inputs of one execution of a pattern: exactly its grid items, or the ingredients of its recipe (tags flexible,
     * without the alternatives the profile's input filter rejects). A recipe that is gone falls back to the grid items.
     *
     * @param level   the level
     * @param profile the producer's profile
     * @param pattern a pattern
     * @return the ingredients, one per used grid cell
     */
    public static List<Ingredient> inputs(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        return allInputs(level, profile, pattern).stream().map(input -> filtered(profile, input)).toList();
    }

    /** An ingredient without the alternatives the input filter rejects (unchanged if it has none, or only such). */
    private static Ingredient filtered(ProducingProfile profile, Ingredient input) {
        List<Holder<Item>> items = input.items().toList();
        List<Holder<Item>> kept = items.stream().filter(profile.inputFilter()::test).toList();
        return kept.isEmpty() || kept.size() == items.size() ? input : Ingredient.of(HolderSet.direct(kept));
    }

    private static List<Ingredient> allInputs(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        if (!pattern.exact()) {
            Optional<PatternRecipes.Shape> shape = pattern.recipe().flatMap(id -> PatternRecipes.byKey(level, profile.recipeTypes(), id));
            if (shape.isPresent()) {
                return shape.get().ingredients();
            }
        }
        return pattern.grid().stream().filter(item -> item != Items.AIR).map(item -> Ingredient.of(item)).toList();
    }

    private static Pattern recipePattern(int slot, PatternRecipes.Shape shape) {
        return new Pattern(slot, shape.output(), shape.count(), shape.grid(), Optional.of(shape.id()), false, false);
    }

    private static Pattern exactPattern(ServerLevel level, ProducingProfile profile, int slot, List<Item> grid) {
        Optional<PatternRecipes.Shape> shape = PatternRecipes.matching(level, profile.recipeTypes(), grid);
        return new Pattern(slot, shape.map(PatternRecipes.Shape::output).orElse(Items.AIR), shape.map(PatternRecipes.Shape::count).orElse(1),
                grid, shape.map(PatternRecipes.Shape::id), true, false);
    }

    /**
     * Puts a pattern into its slot. Replacing a pattern that made something keeps its checkmark if the new one is
     * usable; a new pattern (empty slot, or one that made nothing) is checked automatically if it is usable and a
     * checkmark is free (counted over the {@link #usable} patterns), at the limit it stays unchecked with the "masters
     * only n recipes" state line. Any other edit ends the state line.
     */
    private static Optional<Pattern> put(ServerLevel level, BlockPos pos, int slot, Pattern pattern) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos);
        if (found.isEmpty() || slot < 0 || slot >= Pattern.SLOTS) {
            return Optional.empty();
        }
        SettlementBlock block = found.get();
        boolean allowed = allowed(level, profile(block), pattern);
        Optional<Pattern> replaced = pattern(block, slot).filter(Pattern::valid);
        boolean checked;
        if (replaced.isPresent() || !allowed) {
            checked = allowed && replaced.map(Pattern::checked).orElse(false);
        } else {
            checked = usable(level, block).size() < block.checkLimit();
        }
        boolean atLimit = replaced.isEmpty() && allowed && !checked;
        block.setNotice(atLimit ? Optional.of(new SettlementBlock.Notice.AtLimit(block.checkLimit())) : Optional.empty());
        Pattern placed = pattern.withChecked(checked);
        replace(data, block, placed);

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
        return data.settlementAt(pos).flatMap(settlement -> settlement.block(pos)).filter(Patterns::isProducing);
    }
}
