package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.api.ResourceCollectionProfile;

import net.minecraft.core.Holder;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * The tool staircase (vault {@code Systems/Needs.md}, decisions of 2026-11-04): the skill a settler has in a Job sets which
 * tool tiers it may use (allowed) and which tier it needs (required). The bands are those of the need tiers
 * ({@link NeedState#tierOf}) and overlap, so a better tool is allowed one band before it is required:
 * <pre>
 * Job skill   required            allowed up to
 * 1-20        nothing             T1
 * 21-40       T1                  T2
 * 41-60       T2                  T3
 * 61-80       T3, enchanted       T4
 * 81-100      T4, enchanted       T4
 * </pre>
 * The table is fixed in code. A tool above the allowed tier is ignored completely. The staircase applies to every
 * Resource Collection profile whose tool is an axe, hoe, shovel or pickaxe ({@link #appliesTo}); the answers to "which
 * tool may it use, which does it need" for a skill are a {@link Rule}.
 */
public final class ToolStaircase {

    /** Marks "no tier required". */
    public static final int NO_TIER = -1;

    /** Highest tier allowed in each band (index: need tier - 1). */
    private static final int[] ALLOWED = {1, 2, 3, 4, 4};
    /** Tier required in each band (index: need tier - 1). */
    private static final int[] REQUIRED = {NO_TIER, 1, 2, 3, 4};
    /** First band that also requires an enchanted tool. */
    private static final int FIRST_ENCHANTED_BAND = 4;

    private ToolStaircase() {
    }

    /**
     * What a skill allows and requires of a tool.
     *
     * @param allowed  the highest tool tier it may use
     * @param required the tier it needs, {@link #NO_TIER} if none
     * @param enchanted whether the required tool must be enchanted
     */
    public record Rule(int allowed, int required, boolean enchanted) {

        /** No restriction: every tier is allowed and nothing is required (Jobs outside the staircase). */
        public static final Rule NONE = new Rule(ToolTiers.MAX_TIER, NO_TIER, false);

        /**
         * @param stack a tool
         * @return whether the settler may use it: its tier is not above the allowed tier
         */
        public boolean allows(ItemStack stack) {
            return ToolTiers.tier(stack) <= allowed;
        }

        /**
         * @param stack a tool
         * @return whether it is allowed and meets the requirement (tier, and enchanted from skill 61)
         */
        public boolean meets(ItemStack stack) {
            return allows(stack) && ToolTiers.tier(stack) >= required && (!enchanted || isEnchanted(stack));
        }

        /**
         * @return whether the requirement can be missed at all
         */
        public boolean requiresTool() {
            return required != NO_TIER;
        }

        /**
         * Orders tools: one that meets the requirement beats one that does not, then the higher tier, then an enchanted tool.
         *
         * @param stack a tool
         * @return its rank, higher is better
         */
        int rank(ItemStack stack) {
            return (meets(stack) ? 1000 : 0) + ToolTiers.tier(stack) * 10 + (isEnchanted(stack) ? 1 : 0);
        }
    }

    /**
     * @param skill a Job skill level (1-100)
     * @return the rule for that skill
     */
    public static Rule forSkill(int skill) {
        return forBand(NeedState.tierOf(skill), NeedState.tierOf(skill));
    }

    /**
     * @param band      the band whose requirement counts (1-{@value NeedState#MAX_TIER})
     * @param allowBand the band whose allowed tier counts (the band of the skill)
     * @return the rule
     */
    private static Rule forBand(int band, int allowBand) {
        return new Rule(ALLOWED[allowBand - 1], REQUIRED[band - 1], band >= FIRST_ENCHANTED_BAND);
    }

    /**
     * @param profile a Job's profile
     * @param skill   the Job skill level
     * @return the rule of the skill if the staircase applies to the Job, else {@link Rule#NONE}
     */
    public static Rule rule(ResourceCollectionProfile profile, int skill) {
        return appliesTo(profile) ? forSkill(skill) : Rule.NONE;
    }

    /**
     * @param profile a Job's profile
     * @return whether the staircase applies: the Job needs a tool and it is an axe, hoe, shovel or pickaxe (addon Jobs
     *         that declare such a tool get it without extra code; fishing rod and shears do not)
     */
    public static boolean appliesTo(ResourceCollectionProfile profile) {
        if (!profile.needsTool()) {
            return false;
        }
        Item icon = profile.toolIcon();
        ItemStack sample = new ItemStack(icon);
        return !sample.isEmpty() && profile.isTool(sample) && (sample.is(ItemTags.AXES) || sample.is(ItemTags.HOES)
                || sample.is(ItemTags.SHOVELS) || sample.is(ItemTags.PICKAXES));
    }

    /**
     * @param stack a tool
     * @return whether it has at least one enchantment that is not a curse
     */
    public static boolean isEnchanted(ItemStack stack) {
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            if (!enchantment.is(EnchantmentTags.CURSE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest need tier whose tool requirement a set of tools meets, judged for a Job skill: the input of the tool
     * need ({@link NeedState}, 1 when nothing is required).
     *
     * @param skill the Job skill level; it sets which tools are allowed
     * @param tools the settler's tools of the Job
     * @return the highest band 1-{@value NeedState#MAX_TIER} whose requirement is met
     */
    public static int highestMet(int skill, Iterable<ItemStack> tools) {
        int allowBand = NeedState.tierOf(skill);
        int highest = 1;
        for (int band = 2; band <= NeedState.MAX_TIER; band++) {
            Rule rule = forBand(band, allowBand);
            boolean met = false;
            for (ItemStack tool : tools) {
                if (rule.meets(tool)) {
                    met = true;
                    break;
                }
            }
            if (met) {
                highest = band;
            }
        }
        return highest;
    }

    /**
     * @param stacks stacks to choose from
     * @param rule   the rule
     * @param kind   what counts as the Job's tool
     * @return the best allowed tool of the kind
     */
    static Optional<ItemStack> best(Iterable<ItemStack> stacks, Rule rule, Predicate<ItemStack> kind) {
        ItemStack best = ItemStack.EMPTY;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !kind.test(stack) || !rule.allows(stack)) {
                continue;
            }
            if (best.isEmpty() || rule.rank(stack) > rule.rank(best)) {
                best = stack;
            }
        }
        return best.isEmpty() ? Optional.empty() : Optional.of(best);
    }
}
