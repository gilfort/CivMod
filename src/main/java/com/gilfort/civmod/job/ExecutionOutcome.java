package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.logistics.Pattern;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * What one execution of a pattern makes in the Producing loop.
 *
 * @param result what the pattern makes for sure (empty for a recipe that only draws)
 * @param drawn  the stacks the draw of the recipe's {@code drops} gave, empty if it has none
 */
record ExecutionOutcome(ItemStack result, List<ItemStack> drawn) {

    /**
     * Makes the outcome of one execution: the pattern's result, and for an innate recipe with {@code drops} the draw
     * at the settler's skill level (a recipe without a result makes nothing else).
     *
     * @param innate  the innate recipe the pattern follows, empty for a pattern of a player
     * @param pattern the pattern
     * @param level   the settler's effective skill level in the Job
     * @param random  the random source of the draw (a test can script it, see {@link DropPools#script})
     * @return what the execution makes
     */
    static ExecutionOutcome of(Optional<JobCraftingRecipe> innate, Pattern pattern, int level, RandomSource random) {
        boolean drawsOnly = innate.filter(JobCraftingRecipe::drawsOnly).isPresent();
        ItemStack result = drawsOnly ? ItemStack.EMPTY : new ItemStack(pattern.output(), pattern.count());
        List<ItemStack> drawn = innate.flatMap(JobCraftingRecipe::drops).map(drops -> DropPools.draw(drops, level, random)).orElse(List.of());
        return new ExecutionOutcome(result, drawn);
    }

    /** @return the result and the drawn stacks together */
    List<ItemStack> all() {
        List<ItemStack> all = new ArrayList<>(drawn);
        if (!result.isEmpty()) {
            all.addFirst(result);
        }
        return all;
    }
}
