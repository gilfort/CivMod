package com.gilfort.civmod.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The specifics of a Producing Job (vault {@code Systems/Producing Jobs.md}). CivMod's Producing loop does the rest:
 * it takes the highest-priority request of the settlement it has a checked pattern for, resolves intermediates with its
 * own patterns, fetches the inputs (or waits for input deliveries), works the recipes at a station and delivers the
 * result to the requesting Clerk (or into its drop-off for a Deliveryman).
 * <p>
 * Recipes come from recipe types, vanilla or modded ({@code crafting}, {@code stonecutting}, {@code smelting}, …): the
 * player makes patterns from them in the JobBlock's Patterns tab. A pattern is usable if its recipe is of one of the
 * profile's types, the {@link #inputFilter()} / {@link #outputFilter()} let it through and it lies in the
 * {@link #domain()}. New patterns are checked automatically up to {@link #checkLimit(int)}.
 * <p>
 * The Craftsperson ({@link CivModJobs#CRAFTSPERSON}) is a profile with {@code crafting} and no filters; the Mechanic
 * ({@link CivModJobs#MECHANIC}) adds the domain {@code civmod:domains/redstone}.
 */
@ApiStatus.Experimental
public interface ProducingProfile extends CategoryProfile {

    @Override
    default JobCategory category() {
        return JobCategory.PRODUCING;
    }

    /**
     * @return the recipe types the Job's patterns follow, the first one is the main type (its station is the work place
     *         of the day); at least one
     */
    List<RecipeType<?>> recipeTypes();

    /**
     * The station the Job works recipes of a type at, as a block tag ({@code <namespace>:stations/<job>} by convention).
     * The station of the main recipe type is the work place of the day (set with the Scroll of Assignment, else the
     * nearest within 16 blocks of the JobBlock); other types use the nearest station of their tag.
     *
     * @param type one of {@link #recipeTypes()}
     * @return the block tag of its station
     */
    TagKey<Block> station(RecipeType<?> type);

    /**
     * Which inputs the Job works with. An input whose alternatives the filter all rejects blocks the pattern; rejected
     * alternatives of a tag input are skipped.
     *
     * @return the input filter (every item by default)
     */
    default ItemFilter inputFilter() {
        return ItemFilter.ANY;
    }

    /**
     * @return which outputs the Job makes (every item by default); a rejected output blocks the pattern
     */
    default ItemFilter outputFilter() {
        return ItemFilter.ANY;
    }

    /**
     * The domain of a crafting Specialization (vault {@code Systems/Producing Jobs.md}): a recipe belongs to it if
     * <b>any</b> input is in the tag (a piston is in the domain of several Specializations). Recipes outside the domain
     * cannot be checked, and intermediates outside it are not made but reported missing.
     *
     * @return the domain item tag (none by default: every recipe)
     */
    default Optional<TagKey<Item>> domain() {
        return Optional.empty();
    }

    /**
     * @return the state line of a pattern the filters or the domain block, shown in the Patterns tab ("Outside this
     *         Job's domain" with a {@link #domain()}, else "Not allowed for this Job")
     */
    default Component filteredLine() {
        return Component.translatable(domain().isPresent() ? "civmod.gui.patterns.outside_domain" : "civmod.gui.patterns.filtered");
    }

    /**
     * @param skill the settler's effective skill level in the Job
     * @return how many patterns it can check at once (6 + 1 per 5 levels by default, like the Craftsperson)
     */
    default int checkLimit(int skill) {
        return Patterns.checkLimit(skill);
    }

    /**
     * The work time of one execution of a recipe, in operations of the shared speed formula × the Job's job factor.
     *
     * @param recipe the recipe
     * @return the operations one execution takes (1 by default)
     */
    default double workTime(RecipeHolder<?> recipe) {
        return 1.0;
    }

    /**
     * How many stations of the main recipe type a settler works at once, each with its own charge (vault
     * {@code Systems/Producing Jobs.md}): the Smelter's parallel furnaces. The stations come from the Scroll of
     * Assignment's work points, else from the search around the JobBlock; every one is occupied for the day. The list
     * {@code parallel_stations} in the Job's datapack job file overrides this value per JobBlock level.
     *
     * @param jobBlockLevel the level of the JobBlock (1–5)
     * @return the number of stations worked in parallel (1 by default)
     */
    default int parallelStations(int jobBlockLevel) {
        return 1;
    }

    /**
     * The fuel the Job burns at its stations (vault {@code Jobs/Smelter.md}). With fuel, every execution needs heat:
     * the recipe's cooking time ({@link net.minecraft.world.item.crafting.AbstractCookingRecipe#cookingTime()}, 200
     * ticks for other recipes), paid from the station's <i>Remaining Heat</i>; a station without enough burns the next
     * fuel item by its vanilla burn time and keeps the rest for its next charge. The settler fetches fuel like inputs
     * (the highest-priority listed fuel in stock) and keeps fuel rests in its buffer; the JobBlock gets a Fuel tab
     * where the player orders the list.
     *
     * @return the default fuel list in priority order (at most 6 items), empty if the Job burns no fuel (by default)
     */
    default Optional<List<Item>> fuel() {
        return Optional.empty();
    }

    /**
     * Executes a recipe once in the settler's buffer: the consumed inputs are already taken out, the returned items go in.
     *
     * @param level    the level
     * @param recipe   the recipe
     * @param result   what the pattern makes per execution
     * @param consumed the inputs taken out
     * @return what goes into the buffer (by default the result plus the inputs' remainders, e.g. empty buckets)
     */
    default List<ItemStack> execute(ServerLevel level, RecipeHolder<?> recipe, ItemStack result, List<ItemStack> consumed) {
        List<ItemStack> produced = new ArrayList<>();
        produced.add(result);
        produced.addAll(PatternRecipes.remainders(consumed));
        return produced;
    }
}
