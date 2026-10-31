package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.PatternLayout;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.registry.ModRecipes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The pattern libraries of a JobBlock (vault {@code Systems/Producing Jobs.md}, {@code Jobs/Craftsperson.md}): one per
 * Producing Job unlocked there (its Base Job or a Specialization of its Station Types), kept in its
 * {@link SettlementBlock}: 54 pattern slots, of which {@link SettlementBlock#checkLimit(Job)} can be checked (the limit of
 * the best settler working that Job at the JobBlock). A pattern
 * is made from its output alone (the first matching recipe of the profile's recipe types fills the grid, arrows cycle the
 * alternatives) or from exact inputs (the output follows the recipe they match), or by a recipe viewer's transfer button.
 * A settler works only the library of its Job.
 * <p>
 * A pattern is usable if its recipe is of one of the Job's profile's types, the profile's filters let it through and
 * an input is in the profile's domain (if it has one); rejected alternatives of a tag input are skipped. A pattern of another recipe type ("foreign", a profile that changed)
 * keeps its checkmark but is skipped. A filtered pattern loses its checkmark. A new pattern is checked automatically while
 * the limit allows. The library of a Job no longer unlocked at the JobBlock (a replaced Station Type) is kept but
 * "locked": unusable and not editable until the Job is unlocked again.
 * <p>
 * Innate recipes ({@link InnateRecipes}) sit in the first slots of a Job's library, ordered by recipe id, as locked
 * patterns ({@link Pattern#innate()}): they are always usable (the profile's recipe types, filters and domain do not
 * apply), count towards the checkmark limit and only toggle their checkmark; edit, clear, overwrite and recipe viewer
 * transfers are refused. {@link #syncInnate} lays them out again whenever the library is used or the datapacks change.
 */
public final class Patterns {

    /** Checkmarks below skill level 5. */
    public static final int BASE_CHECKMARKS = 6;
    /** Metal items: ingots, nuggets, raw materials and metal blocks (the future Smith's domain). */
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
     * @param block a JobBlock
     * @param job   a Job
     * @return whether the JobBlock can keep a library for the Job: a Producing Job that is its Base Job or one of its
     *         Specializations
     */
    public static boolean hasLibrary(SettlementBlock block, Job job) {
        return job.category() == JobCategory.PRODUCING && (job == block.jobType() || job.parent().filter(block.jobType()::equals).isPresent());
    }

    /**
     * @param block a JobBlock
     * @param job   a Producing Job of it
     * @return whether its library is locked: the Job is no longer unlocked at the JobBlock (a replaced Station Type)
     */
    public static boolean locked(SettlementBlock block, Job job) {
        return !StationTypes.isUnlocked(block, job);
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
     * @param block a JobBlock
     * @param job   a Producing Job of it
     * @param slot  a library slot
     * @return the pattern in that slot of the Job's library
     */
    public static Optional<Pattern> pattern(SettlementBlock block, Job job, int slot) {
        return block.patterns(job).stream().filter(pattern -> pattern.slot() == slot).findFirst();
    }

    /**
     * @param block a JobBlock
     * @param job   a Producing Job of it
     * @return the checked patterns of the Job's library that make something, in list order
     */
    public static List<Pattern> checked(SettlementBlock block, Job job) {
        return block.patterns(job).stream().filter(pattern -> pattern.checked() && pattern.valid()).toList();
    }

    /**
     * @param level the level
     * @param block a JobBlock
     * @param job   a Producing Job of it
     * @return the checked patterns of the Job's library it can use (of its recipe types, passing its filters), in list
     *         order; only these count against the checkmark limit. None while the library is {@link #locked}.
     */
    public static List<Pattern> usable(ServerLevel level, SettlementBlock block, Job job) {
        if (locked(block, job)) {
            return List.of();
        }
        ProducingProfile profile = profile(job);
        return checked(block, job).stream().filter(pattern -> allowed(level, profile, pattern)).toList();
    }

    /**
     * @param item an item
     * @return whether it is metal (item tag {@code civmod:metals})
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
        return pattern.valid() && (pattern.innate() || !foreign(level, profile, pattern) && !filtered(level, profile, pattern));
    }

    /**
     * @param level   the level
     * @param block   a JobBlock
     * @param job     a Producing Job of it
     * @param pattern a pattern of the Job's library
     * @return whether the Job cannot use the pattern although it makes something (locked library, foreign recipe type
     *         or filtered)
     */
    public static boolean unusable(ServerLevel level, SettlementBlock block, Job job, Pattern pattern) {
        return pattern.valid() && (locked(block, job) || !allowed(level, profile(job), pattern));
    }

    /**
     * @param level   the level
     * @param profile a Producing profile
     * @param pattern a pattern
     * @return whether it follows a recipe of another type than the profile's (a recipe that is gone counts as none; an
     *         innate pattern never does)
     */
    public static boolean foreign(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        return pattern.valid() && !pattern.innate() && pattern.recipe().flatMap(id -> PatternRecipes.holder(level, id))
                .filter(holder -> !profile.recipeTypes().contains(holder.value().getType())).isPresent();
    }

    /**
     * @param level   the level
     * @param profile a Producing profile
     * @param pattern a pattern
     * @return whether the profile's filters reject its output or an input (all its alternatives), or the pattern lies
     *         outside the profile's domain (no input with an alternative in the domain tag); never for an innate pattern
     */
    public static boolean filtered(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        if (!pattern.valid() || pattern.innate()) {
            return false;
        }
        List<Ingredient> inputs = allInputs(level, profile, pattern);
        return !profile.outputFilter().test(pattern.output())
                || inputs.stream().anyMatch(input -> input.items().noneMatch(profile.inputFilter()::test))
                || profile.domain().filter(domain -> inputs.stream().noneMatch(input -> input.items().anyMatch(item -> item.is(domain)))).isPresent();
    }

    /**
     * Makes the pattern in a slot from its output: the first recipe of the profile's types making the item fills the
     * grid (tag ingredients stay flexible). A replaced pattern's checkmark stays; a new one is checked automatically.
     *
     * @param level  the level
     * @param pos    the JobBlock
     * @param job    the Producing Job whose library it is
     * @param slot   the library slot
     * @param output the item to make
     * @return the new pattern, empty if no recipe makes the item or the library is locked
     */
    public static Optional<Pattern> setOutput(ServerLevel level, BlockPos pos, Job job, int slot, Item output) {
        Optional<SettlementBlock> block = block(level, pos, job);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        List<PatternRecipes.Shape> shapes = PatternRecipes.producing(level, profile(job).recipeTypes(), output);
        return shapes.isEmpty() ? Optional.empty() : put(level, pos, job, slot, recipePattern(slot, shapes.getFirst()));
    }

    /**
     * Cycles a recipe pattern to the next (or previous) recipe making its output.
     *
     * @param level     the level
     * @param pos       the JobBlock
     * @param job       the Producing Job whose library it is
     * @param slot      the library slot
     * @param direction +1 next, -1 previous
     * @return the new pattern, empty if the slot holds no recipe pattern
     */
    public static Optional<Pattern> cycle(ServerLevel level, BlockPos pos, Job job, int slot, int direction) {
        Optional<SettlementBlock> block = block(level, pos, job);
        Optional<Pattern> current = block.flatMap(found -> pattern(found, job, slot)).filter(pattern -> pattern.valid() && !pattern.innate());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        List<PatternRecipes.Shape> shapes = PatternRecipes.producing(level, profile(job).recipeTypes(), current.get().output());
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
        return put(level, pos, job, slot, recipePattern(slot, next));
    }

    /**
     * Sets one input of a pattern: the pattern uses exactly its grid items from now on, its output follows the recipe
     * they match ({@link Items#AIR} while they match none).
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param job   the Producing Job whose library it is
     * @param slot  the library slot
     * @param index the grid cell (0–8)
     * @param item  the item, {@link Items#AIR} to empty the cell
     * @return the new pattern, empty if the JobBlock keeps no (unlocked) library for the Job
     */
    public static Optional<Pattern> setInput(ServerLevel level, BlockPos pos, Job job, int slot, int index, Item item) {
        return setInput(level, pos, job, slot, index, item, Optional.empty());
    }

    /**
     * Sets one input of a pattern in a layout (vault {@code Systems/Producing Jobs.md}): the pattern uses exactly its
     * grid items from now on, its output follows the recipe of the layout's type they match ({@link Items#AIR} while
     * they match none). Another layout than the pattern's starts from empty inputs.
     *
     * @param level  the level
     * @param pos    the JobBlock
     * @param job    the Producing Job whose library it is
     * @param slot   the library slot
     * @param index  the cell (within the layout's cells)
     * @param item   the item, {@link Items#AIR} to empty the cell
     * @param layout the recipe type of the editor's layout (empty, or one the Job does not follow: the pattern's own)
     * @return the new pattern, empty if the cell is outside the layout or the JobBlock keeps no (unlocked) library for
     *         the Job
     */
    public static Optional<Pattern> setInput(ServerLevel level, BlockPos pos, Job job, int slot, int index, Item item, Optional<RecipeType<?>> layout) {
        Optional<SettlementBlock> block = block(level, pos, job);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        ProducingProfile profile = profile(job);
        Optional<Pattern> current = pattern(block.get(), job, slot);
        RecipeType<?> own = current.<RecipeType<?>>map(pattern -> layoutType(level, profile, pattern)).orElse(profile.recipeTypes().getFirst());
        RecipeType<?> type = layout.filter(profile.recipeTypes()::contains).orElse(own);
        PatternLayout shown = PatternLayout.of(level, profile, type);
        if (index < 0 || index >= shown.kind().cells()) {
            return Optional.empty();
        }
        List<Item> grid = new ArrayList<>(current.filter(pattern -> type == own).map(Pattern::grid).orElse(List.of()));
        while (grid.size() < Pattern.GRID) {
            grid.add(Items.AIR);
        }
        grid.set(index, item);
        return put(level, pos, job, slot, exactPattern(level, shown, slot, grid));
    }

    /**
     * @param level   the level
     * @param profile the Producing profile of the library's Job
     * @param pattern a pattern of the library
     * @return the layout the editor shows it in: an innate recipe's row, else the layout of its {@link Pattern#layout()},
     *         of its recipe's type (patterns saved before layouts existed), else of the Job's first recipe type
     */
    public static PatternLayout layout(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        if (pattern.innate()) {
            TagKey<Block> station = InnateRecipes.recipe(level, pattern).flatMap(JobCraftingRecipe::station)
                    .orElseGet(() -> profile.station(profile.recipeTypes().getFirst()));
            return PatternLayout.innate(ModRecipes.JOB_CRAFTING.get(), station);
        }
        return PatternLayout.of(level, profile, layoutType(level, profile, pattern));
    }

    private static RecipeType<?> layoutType(ServerLevel level, ProducingProfile profile, Pattern pattern) {
        return pattern.layout().or(() -> pattern.recipe().flatMap(id -> PatternRecipes.holder(level, id))
                .<RecipeType<?>>map(holder -> holder.value().getType())).orElse(profile.recipeTypes().getFirst());
    }

    /**
     * Fills a pattern from a recipe viewer's transfer button: the recipe if the server knows it as a recipe of the
     * profile's types, otherwise exactly the shown items (padded to the 3×3 grid). A known recipe of a type the Job
     * does not follow is refused.
     *
     * @param level  the level
     * @param pos    the JobBlock
     * @param job    the Producing Job whose library it is
     * @param slot   the library slot
     * @param recipe the recipe id the viewer showed
     * @param grid   the shown items, row by row
     * @return the new pattern, empty if refused
     */
    public static Optional<Pattern> transfer(ServerLevel level, BlockPos pos, Job job, int slot, Optional<ResourceKey<Recipe<?>>> recipe,
            List<Item> grid) {
        return transfer(level, pos, job, slot, recipe, grid, Optional.empty());
    }

    /**
     * {@link #transfer(ServerLevel, BlockPos, Job, int, Optional, List)} with the recipe type the viewer showed: the
     * layout of exactly the shown items when the server does not know the recipe.
     *
     * @param level  the level
     * @param pos    the JobBlock
     * @param job    the Producing Job whose library it is
     * @param slot   the library slot
     * @param recipe the recipe id the viewer showed
     * @param grid   the shown items, row by row
     * @param type   the recipe type the viewer showed (empty, or one the Job does not follow: the Job's first type)
     * @return the new pattern, empty if refused
     */
    public static Optional<Pattern> transfer(ServerLevel level, BlockPos pos, Job job, int slot, Optional<ResourceKey<Recipe<?>>> recipe,
            List<Item> grid, Optional<RecipeType<?>> type) {
        Optional<SettlementBlock> block = block(level, pos, job);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        ProducingProfile profile = profile(job);
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
        PatternLayout layout = PatternLayout.of(level, profile, type.filter(profile.recipeTypes()::contains).orElse(profile.recipeTypes().getFirst()));
        return put(level, pos, job, slot, shape.map(found -> recipePattern(slot, found)).orElseGet(() -> exactPattern(level, layout, slot, cells)));
    }

    /**
     * Checks or unchecks a pattern; checking needs a pattern its Job can use and a free checkmark (counted over the
     * {@link #usable} patterns). Every toggle ends the state line ({@link SettlementBlock#notice(Job)}).
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param job   the Producing Job whose library it is
     * @param slot  the library slot
     * @return whether the pattern is checked afterwards
     */
    public static boolean toggleCheck(ServerLevel level, BlockPos pos, Job job, int slot) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(level, pos, job);
        Optional<Pattern> pattern = found.flatMap(block -> pattern(block, job, slot));
        if (pattern.isEmpty()) {
            return false;
        }
        SettlementBlock block = found.get();
        SettlementBlock.Library library = block.library(job);
        library.setNotice(Optional.empty());
        boolean check = !pattern.get().checked();
        if (check && (!allowed(level, profile(job), pattern.get()) || usable(level, block, job).size() >= library.checkLimit())) {
            return false;
        }
        replace(data, library, pattern.get().withChecked(check));
        return check;
    }

    /**
     * Empties a library slot; an innate pattern stays.
     *
     * @param data the dimension's settlement data
     * @param pos  the JobBlock
     * @param job  the Producing Job whose library it is
     * @param slot the library slot
     */
    public static void clear(SettlementData data, BlockPos pos, Job job, int slot) {
        block(data, pos, job).map(block -> block.library(job)).ifPresent(library -> {
            if (library.mutablePatterns().removeIf(pattern -> pattern.slot() == slot && !pattern.innate())) {
                library.setNotice(Optional.empty());
                data.setDirty();
            }
        });
    }

    /**
     * Applies the checkmark limit of a settler working a Job's library together with the others working that Job at the
     * JobBlock (vault {@code Systems/Producing Jobs.md}): the library's limit is the highest one any of them last
     * reported (settlers no longer in the Job at the block are forgotten), trimmed as in
     * {@link #enforceLimit(ServerLevel, BlockPos, Job, int)} when it drops.
     *
     * @param level   the level
     * @param pos     the JobBlock
     * @param job     the Producing Job whose library it is
     * @param settler the settler working it
     * @param limit   that settler's checkmark limit (from its effective skill)
     * @return how many checkmarks were removed
     */
    public static int enforceLimit(ServerLevel level, BlockPos pos, Job job, UUID settler, int limit) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos, job);
        if (found.isEmpty()) {
            return 0;
        }
        SettlementBlock block = found.get();
        Map<UUID, Integer> workers = block.library(job).workerLimits();
        Set<UUID> holders = block.assignees().stream().filter(assignee -> assignee.job() == job).map(SettlementBlock.Assignee::id)
                .collect(Collectors.toSet());
        boolean changed = workers.keySet().retainAll(holders);
        if (holders.contains(settler)) {
            changed |= !Integer.valueOf(limit).equals(workers.put(settler, limit));
        }
        if (changed) {
            data.setDirty();
        }
        int best = workers.values().stream().mapToInt(Integer::intValue).max().orElse(limit);
        return enforceLimit(level, pos, job, best);
    }

    /**
     * Applies a checkmark limit to a Job's library: if it dropped, the last {@link #usable}
     * patterns in list order lose their checkmark until the count fits, and the GUI shows how many were removed. Foreign
     * patterns keep theirs.
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param job   the Producing Job whose library it is
     * @param limit the checkmark limit
     * @return how many checkmarks were removed
     */
    public static int enforceLimit(ServerLevel level, BlockPos pos, Job job, int limit) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos, job);
        if (found.isEmpty()) {
            return 0;
        }
        SettlementBlock block = found.get();
        SettlementBlock.Library library = block.library(job);
        if (library.checkLimit() != limit) {
            library.setCheckLimit(limit);
            data.setDirty();
        }
        List<Pattern> checked = usable(level, block, job);
        int removed = Math.max(0, checked.size() - limit);
        for (Pattern pattern : checked.subList(checked.size() - removed, checked.size())) {
            replace(data, library, pattern.withChecked(false));
        }
        if (removed > 0) {
            library.setNotice(Optional.of(new SettlementBlock.Notice.Trimmed(removed, limit)));
            data.setDirty();
        }
        return removed;
    }

    /**
     * Removes the checkmark of every pattern of the Job's recipe types that its profile's filters reject: such a pattern
     * is never checked, also when it was checked in an older save or before a filter tag changed. Foreign patterns keep
     * their checkmark.
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param job   the Producing Job whose library it is
     * @return how many checkmarks were removed
     */
    public static int uncheckFiltered(ServerLevel level, BlockPos pos, Job job) {
        SettlementData data = SettlementData.get(level);
        return block(data, pos, job).map(block -> uncheckFiltered(level, data, block, job)).orElse(0);
    }

    /**
     * @param level the level
     * @param data  the dimension's settlement data (marked dirty on a change)
     * @param block a JobBlock
     * @param job   the Producing Job whose library it is
     * @return how many checkmarks of filtered patterns were removed
     * @see #uncheckFiltered(ServerLevel, BlockPos, Job)
     */
    public static int uncheckFiltered(ServerLevel level, SettlementData data, SettlementBlock block, Job job) {
        ProducingProfile profile = profile(job);
        SettlementBlock.Library library = block.library(job);
        List<Pattern> filtered = library.patterns().stream()
                .filter(pattern -> pattern.checked() && !foreign(level, profile, pattern) && filtered(level, profile, pattern)).toList();
        filtered.forEach(pattern -> replace(data, library, pattern.withChecked(false)));
        return filtered.size();
    }

    /**
     * Lays out the Job's innate recipes in the first slots of its library, ordered by recipe id (vault
     * {@code Systems/Producing Jobs.md}). A player pattern on a slot an innate recipe needs moves to the next free slot
     * (lost if none is left: patterns are ghost items); a removed innate recipe frees its slot. An innate pattern keeps
     * the check state of its recipe id; a new one is checked while the checkmark limit has room. A locked library is
     * left as it is.
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param job   the Producing Job whose library it is
     * @return whether the library changed
     */
    public static boolean syncInnate(ServerLevel level, BlockPos pos, Job job) {
        return syncInnate(level, pos, job, InnateRecipes.of(level, job));
    }

    /**
     * {@link #syncInnate(ServerLevel, BlockPos, Job)} with a given list of innate recipes, as after a datapack change.
     *
     * @param level  the level
     * @param pos    the JobBlock
     * @param job    the Producing Job whose library it is
     * @param innate the Job's innate recipes, ordered by recipe id
     * @return whether the library changed
     */
    public static boolean syncInnate(ServerLevel level, BlockPos pos, Job job, List<RecipeHolder<JobCraftingRecipe>> innate) {
        SettlementData data = SettlementData.get(level);
        return block(data, pos, job).map(block -> syncInnate(level, data, block, job, innate)).orElse(false);
    }

    /**
     * Lays out the innate recipes of every unlocked Producing library in the dimension (after a datapack load).
     *
     * @param level the level
     */
    public static void syncInnate(ServerLevel level) {
        SettlementData data = SettlementData.get(level);
        for (Settlement settlement : data.settlements()) {
            for (SettlementBlock block : settlement.blocks()) {
                if (isProducing(block)) {
                    ModJobs.REGISTRY.stream().filter(job -> hasLibrary(block, job) && !locked(block, job))
                            .forEach(job -> syncInnate(level, data, block, job, InnateRecipes.of(level, job)));
                }
            }
        }
    }

    private static boolean syncInnate(ServerLevel level, SettlementData data, SettlementBlock block, Job job,
            List<RecipeHolder<JobCraftingRecipe>> recipes) {
        List<Pattern> current = block.patterns(job);
        if (recipes.isEmpty() && current.stream().noneMatch(Pattern::innate)) {
            return false;
        }
        SettlementBlock.Library library = block.library(job);
        if (library.innateLaidOut() == recipes) {
            // Laid out from this very list before (InnateRecipes keeps one list per recipe set) and only syncInnate
            // moves innate patterns: nothing to do. Keeps the per-decision call of the Producing loop at O(1).
            return false;
        }
        library.setInnateLaidOut(recipes);
        Map<ResourceKey<Recipe<?>>, Pattern> before = current.stream().filter(Pattern::innate).filter(pattern -> pattern.recipe().isPresent())
                .collect(Collectors.toMap(pattern -> pattern.recipe().get(), pattern -> pattern, (first, second) -> first));
        List<Pattern> laidOut = new ArrayList<>();
        List<Integer> fresh = new ArrayList<>();
        int innate = Math.min(recipes.size(), Pattern.SLOTS);
        for (int slot = 0; slot < innate; slot++) {
            RecipeHolder<JobCraftingRecipe> holder = recipes.get(slot);
            Pattern old = before.get(holder.id());
            if (old == null) {
                fresh.add(slot);
            }
            laidOut.add(InnateRecipes.pattern(slot, holder, old != null && old.checked()));
        }
        boolean[] taken = new boolean[Pattern.SLOTS];
        for (int slot = 0; slot < innate; slot++) {
            taken[slot] = true;
        }
        List<Pattern> displaced = new ArrayList<>();
        for (Pattern pattern : current) {
            if (pattern.innate()) {
                continue;
            }
            if (taken[pattern.slot()]) {
                displaced.add(pattern);
            } else {
                taken[pattern.slot()] = true;
                laidOut.add(pattern);
            }
        }
        for (Pattern pattern : displaced) {
            // The next free slot; none left: the pattern is lost (a ghost item, nothing to give back).
            for (int slot = innate; slot < Pattern.SLOTS; slot++) {
                if (!taken[slot]) {
                    taken[slot] = true;
                    laidOut.add(pattern.withSlot(slot));
                    break;
                }
            }
        }
        laidOut.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        if (laidOut.equals(current)) {
            return false;
        }
        List<Pattern> patterns = library.mutablePatterns();
        patterns.clear();
        patterns.addAll(laidOut);
        for (int slot : fresh) {
            // Like every new pattern: checked while the limit has room.
            if (usable(level, block, job).size() < library.checkLimit()) {
                patterns.set(slot, patterns.get(slot).withChecked(true));
            }
        }
        data.setDirty();
        return true;
    }

    /**
     * Records which innate recipes a settler had to skip and what they need, shown in the Patterns tab.
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param job   the Producing Job whose library it is
     * @param needs what each skipped innate recipe needs, by recipe id (empty: none skipped)
     */
    public static void setNeeds(ServerLevel level, BlockPos pos, Job job, Map<ResourceKey<Recipe<?>>, Component> needs) {
        block(SettlementData.get(level), pos, job).map(block -> block.library(job)).filter(library -> !library.needs().equals(needs))
                .ifPresent(library -> library.setNeeds(needs));
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
        if (pattern.innate()) {
            Optional<JobCraftingRecipe> innate = InnateRecipes.recipe(level, pattern);
            if (innate.isPresent()) {
                return innate.get().ingredients();
            }
        } else if (!pattern.exact()) {
            Optional<PatternRecipes.Shape> shape = pattern.recipe().flatMap(id -> PatternRecipes.byKey(level, profile.recipeTypes(), id));
            if (shape.isPresent()) {
                return shape.get().ingredients();
            }
        }
        return pattern.grid().stream().filter(item -> item != Items.AIR).map(item -> Ingredient.of(item)).toList();
    }

    private static Pattern recipePattern(int slot, PatternRecipes.Shape shape) {
        return new Pattern(slot, shape.output(), shape.count(), shape.grid(), Optional.of(shape.id()), false, false, false, Optional.of(shape.type()));
    }

    /** Exactly the items in the layout's cells (the others stay empty); the output follows the recipe of its type they match. */
    private static Pattern exactPattern(ServerLevel level, PatternLayout layout, int slot, List<Item> grid) {
        List<Item> cells = new ArrayList<>(grid);
        for (int i = layout.kind().cells(); i < cells.size(); i++) {
            cells.set(i, Items.AIR);
        }
        Optional<PatternRecipes.Shape> shape = PatternRecipes.matching(level, layout, cells);
        return new Pattern(slot, shape.map(PatternRecipes.Shape::output).orElse(Items.AIR), shape.map(PatternRecipes.Shape::count).orElse(1),
                cells, shape.map(PatternRecipes.Shape::id), true, false, false, Optional.of(layout.type()));
    }

    /**
     * Puts a pattern into its slot. Replacing a pattern that made something keeps its checkmark if the new one is
     * usable; a new pattern (empty slot, or one that made nothing) is checked automatically if it is usable and a
     * checkmark is free (counted over the {@link #usable} patterns), at the limit it stays unchecked with the "masters
     * only n recipes" state line. Any other edit ends the state line.
     */
    private static Optional<Pattern> put(ServerLevel level, BlockPos pos, Job job, int slot, Pattern pattern) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos, job);
        if (found.isEmpty() || slot < 0 || slot >= Pattern.SLOTS) {
            return Optional.empty();
        }
        SettlementBlock block = found.get();
        if (pattern(block, job, slot).filter(Pattern::innate).isPresent()) {
            // Innate recipes are locked: no edit, overwrite or recipe viewer transfer.
            return Optional.empty();
        }
        SettlementBlock.Library library = block.library(job);
        boolean allowed = allowed(level, profile(job), pattern);
        Optional<Pattern> replaced = pattern(block, job, slot).filter(Pattern::valid);
        boolean checked;
        if (replaced.isPresent() || !allowed) {
            checked = allowed && replaced.map(Pattern::checked).orElse(false);
        } else {
            checked = usable(level, block, job).size() < library.checkLimit();
        }
        boolean atLimit = replaced.isEmpty() && allowed && !checked;
        library.setNotice(atLimit ? Optional.of(new SettlementBlock.Notice.AtLimit(library.checkLimit())) : Optional.empty());
        Pattern placed = pattern.withChecked(checked);
        replace(data, library, placed);

        return Optional.of(placed);
    }

    private static void replace(SettlementData data, SettlementBlock.Library library, Pattern pattern) {
        List<Pattern> patterns = library.mutablePatterns();
        patterns.removeIf(existing -> existing.slot() == pattern.slot());
        patterns.add(pattern);
        patterns.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        data.setDirty();
    }

    /** The JobBlock as {@link #block(SettlementData, BlockPos, Job)}, its library's innate recipes laid out first. */
    private static Optional<SettlementBlock> block(ServerLevel level, BlockPos pos, Job job) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = block(data, pos, job);
        found.ifPresent(block -> syncInnate(level, data, block, job, InnateRecipes.of(level, job)));
        return found;
    }

    /** The JobBlock, if it keeps a library for the Job that is not locked (edits of a locked library are refused). */
    private static Optional<SettlementBlock> block(SettlementData data, BlockPos pos, Job job) {
        return data.settlementAt(pos).flatMap(settlement -> settlement.block(pos)).filter(block -> hasLibrary(block, job) && !locked(block, job));
    }
}
