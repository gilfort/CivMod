package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.npc.SettlerJob;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What the tool of a Job is, as the tool supply sees it ({@link ToolOrders}, {@link WorkRules#missingTool},
 * {@link SettlerWork#tool}): which items count, the item the "tool missing" bubble shows, what a skill allows and requires
 * of it, and which one of several on the belt is the best. A Resource Collection Job's tool is declared by its profile.
 *
 * @param isTool whether an item stack is a tool of the Job
 * @param icon   the item of the "tool missing" bubble (asked only when it shows)
 * @param ruleOf what a Job skill allows and requires of the tool
 * @param best   the best tool of a set of stacks under a rule
 */
record ToolKind(Predicate<ItemStack> isTool, Supplier<Item> icon, IntFunction<ToolStaircase.Rule> ruleOf, Best best) {

    /** Picks the best tool among stacks. */
    @FunctionalInterface
    interface Best {

        /**
         * @param stacks the stacks to choose from (empty ones included)
         * @param rule   what the Job skill allows
         * @param isTool which stacks are tools of the Job
         * @return the best allowed tool, empty if there is none
         */
        Optional<ItemStack> of(Iterable<ItemStack> stacks, ToolStaircase.Rule rule, Predicate<ItemStack> isTool);
    }

    /**
     * @param profile a Resource Collection Job's profile
     * @return the kind of its tool: the staircase rule of its skill, the best tool is the one of the highest rank
     */
    static ToolKind of(ResourceCollectionProfile profile) {
        return new ToolKind(profile::isTool, profile::toolIcon, skill -> ToolStaircase.rule(profile, skill), ToolStaircase::best);
    }

    /**
     * @param job a Job of a settler
     * @return the kind of its tool, empty for a Job without one (every Resource Collection Job has one; bare-handed ones
     *         find none of their items)
     */
    static Optional<ToolKind> of(SettlerJob job) {
        return job.jobType().profile(ResourceCollectionProfile.class).map(ToolKind::of);
    }
}
