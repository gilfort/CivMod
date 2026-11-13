package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;

/**
 * The slot layout of a library with innate recipes, without any world state: the innate recipes take the first slots in
 * the order given, player patterns keep their slot unless an innate recipe needs it and move to the next free slot
 * then (lost if none is left: patterns are ghost items). {@code Patterns.syncInnate} adds the world parts.
 */
public final class InnateLayout {

    private InnateLayout() {
    }

    /**
     * Makes a pattern for an innate recipe.
     */
    @FunctionalInterface
    public interface Maker {

        /**
         * @param slot    the library slot
         * @param recipe  the innate recipe's id
         * @param checked whether the pattern is checked
         * @return the locked innate pattern
         */
        Pattern make(int slot, ResourceKey<Recipe<?>> recipe, boolean checked);
    }

    /**
     * @param patterns the new library, ordered by slot
     * @param fresh    the slots of innate patterns that had no pattern before (the caller checks them while the limit has room)
     */
    public record Result(List<Pattern> patterns, List<Integer> fresh) {
    }

    /**
     * An innate pattern keeps the check state, always flag and batch size of the pattern of its recipe id; a new one
     * starts unchecked.
     *
     * @param current   the library as it is, ordered by slot
     * @param recipeIds the innate recipes, ordered by recipe id
     * @param maker     makes the pattern of an innate recipe
     * @return the new library and its fresh slots
     */
    public static Result layout(List<Pattern> current, List<ResourceKey<Recipe<?>>> recipeIds, Maker maker) {
        Map<ResourceKey<Recipe<?>>, Pattern> before = current.stream().filter(Pattern::innate).filter(pattern -> pattern.recipe().isPresent())
                .collect(Collectors.toMap(pattern -> pattern.recipe().get(), pattern -> pattern, (first, second) -> first));
        List<Pattern> laidOut = new ArrayList<>();
        List<Integer> fresh = new ArrayList<>();
        int innate = Math.min(recipeIds.size(), Pattern.SLOTS);
        for (int slot = 0; slot < innate; slot++) {
            Pattern old = before.get(recipeIds.get(slot));
            if (old == null) {
                fresh.add(slot);
            }
            Pattern laid = maker.make(slot, recipeIds.get(slot), old != null && old.checked());
            laidOut.add(old != null ? laid.withAlways(old.always(), old.atOnce()) : laid);
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
            for (int slot = innate; slot < Pattern.SLOTS; slot++) {
                if (!taken[slot]) {
                    taken[slot] = true;
                    laidOut.add(pattern.withSlot(slot));
                    break;
                }
            }
        }
        laidOut.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return new Result(laidOut, fresh);
    }
}
