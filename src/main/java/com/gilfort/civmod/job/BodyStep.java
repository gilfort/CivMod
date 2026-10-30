package com.gilfort.civmod.job;

import com.gilfort.civmod.job.Schedule.Activity;

import net.minecraft.core.BlockPos;

/**
 * What a meal or rest intent wants next, as {@link SettlerMeals} and {@link SettlerRest} answer the Settler Shell (vault
 * {@code Systems/Architecture.md}). The modules only say what they want; the shell alone walks or teleports the settler
 * and changes its activity ({@link SettlerWork}). Like a {@link JobStep}, a step is applied after the module returned.
 */
sealed interface BodyStep {

    /** Nothing changes: the settler goes on with what it does. */
    BodyStep STAY = new Stay();
    /** Stand still: whatever the settler does ends. */
    BodyStep STOP = new Stop();
    /** Eat from the pockets on the spot. */
    BodyStep EAT = new Eat();
    /** Work the first job of the priority list that can work (a Clerk waits for food at its own counter, at work). */
    BodyStep WORK = new Work();
    /** The step is over: the settler is idle and the shell decides again. */
    BodyStep DONE = new Done(true);
    /** The step is over: the settler is idle until the next decision. */
    BodyStep DONE_NO_DECISION = new Done(false);

    /** Nothing changes. */
    record Stay() implements BodyStep {
    }

    /** Stand still. */
    record Stop() implements BodyStep {
    }

    /** Eat from the pockets on the spot. */
    record Eat() implements BodyStep {
    }

    /** Work as usual. */
    record Work() implements BodyStep {
    }

    /**
     * The step is over.
     *
     * @param decideAgain whether the shell decides again right away
     */
    record Done(boolean decideAgain) implements BodyStep {
    }

    /**
     * Walk to a target for an activity; a settler already walking there for it goes on.
     *
     * @param activity the activity on the way and at the target
     * @param target   the target
     * @param reach    how close the settler has to come
     */
    record Walk(Activity activity, BlockPos target, double reach) implements BodyStep {
    }
}
