package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.api.DropOutcome;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * The weighted draw of a {@code drops} recipe (vault {@code Systems/Producing Jobs.md}) as pure functions: no world, no
 * registry, the random source is a parameter so tests can script the draw.
 */
public final class DropDraw {

    private DropDraw() {
    }

    /**
     * @param outcomes the outcomes of a recipe, in file order
     * @param level    the settler's skill level in the Job
     * @return the outcomes that are in the draw at that level (an outcome stays in at every higher level)
     */
    public static List<DropOutcome> eligible(List<DropOutcome> outcomes, int level) {
        return outcomes.stream().filter(outcome -> outcome.minLevel() <= level).toList();
    }

    /**
     * @param outcomes outcomes
     * @return the sum of their weights
     */
    public static int totalWeight(List<DropOutcome> outcomes) {
        return outcomes.stream().mapToInt(DropOutcome::weight).sum();
    }

    /**
     * Draws one outcome among those the level allows: the random source picks a number below their total weight, and the
     * outcomes take turns in file order, each covering a stretch as long as its weight.
     *
     * @param outcomes the outcomes of a recipe
     * @param level    the settler's skill level in the Job
     * @param random   the random source, asked once for a number below the total weight
     * @return the stacks of the drawn outcome, empty if it found nothing or no outcome is in the draw
     */
    public static List<ItemStackTemplate> draw(List<DropOutcome> outcomes, int level, RandomSource random) {
        List<DropOutcome> eligible = eligible(outcomes, level);
        if (eligible.isEmpty()) {
            return List.of();
        }
        int roll = random.nextInt(totalWeight(eligible));
        for (DropOutcome outcome : eligible) {
            roll -= outcome.weight();
            if (roll < 0) {
                return outcome.results();
            }
        }
        throw new IllegalStateException("Roll " + roll + " is beyond the total weight of " + eligible);
    }

    /**
     * @param outcomes the outcomes of a recipe
     * @param outcome  one of them
     * @return its chance in percent at the level where it opens (its weight against the outcomes in the draw by then)
     */
    public static double percentAtUnlock(List<DropOutcome> outcomes, DropOutcome outcome) {
        return 100.0 * outcome.weight() / totalWeight(eligible(outcomes, outcome.minLevel()));
    }
}
