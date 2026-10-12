package com.gilfort.civmod.job;

import java.util.Map;
import java.util.Optional;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;

/**
 * Tool tiers from the mining level (vault {@code Systems/Resource Collection Jobs.md}): derived from the
 * {@code #incorrect_for_*_tool} tag of the tool component, so modded tools are classified automatically.
 */
public final class ToolTiers {

    /** Highest tier. */
    public static final int MAX_TIER = 4;

    private static final Map<TagKey<Block>, Integer> TIERS = Map.of(
            BlockTags.INCORRECT_FOR_WOODEN_TOOL, 0,
            BlockTags.INCORRECT_FOR_GOLD_TOOL, 0,
            BlockTags.INCORRECT_FOR_STONE_TOOL, 1,
            BlockTags.INCORRECT_FOR_COPPER_TOOL, 1,
            BlockTags.INCORRECT_FOR_IRON_TOOL, 2,
            BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 3,
            BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 4);

    private static final double[] MULTIPLIERS = {0.5, 1.0, 1.2, 1.5, 2.0};

    private ToolTiers() {
    }

    /**
     * @param stack a tool
     * @return its tier 0–4 (1 if it has no recognisable mining level)
     */
    public static int tier(ItemStack stack) {
        Tool tool = stack.get(DataComponents.TOOL);
        if (tool == null) {
            return 1;
        }
        for (Tool.Rule rule : tool.rules()) {
            if (rule.correctForDrops().filter(correct -> !correct).isPresent()) {
                Optional<TagKey<Block>> tag = rule.blocks().unwrapKey();
                if (tag.isPresent() && TIERS.containsKey(tag.get())) {
                    return TIERS.get(tag.get());
                }
            }
        }
        return 1;
    }

    /**
     * @param tier a tool tier
     * @return the speed multiplier of that tier (T0 ×0.5, T1 ×1, T2 ×1.2, T3 ×1.5, T4 ×2)
     */
    public static double multiplier(int tier) {
        return MULTIPLIERS[Math.max(0, Math.min(MAX_TIER, tier))];
    }

    /**
     * The highest tool tier a settler may use. Allows everything in the PoC; the restriction by skill level is an
     * open topic (vault {@code Systems/Resource Collection Jobs.md}).
     *
     * @param skillLevel the job skill level
     * @return the highest allowed tier
     */
    public static int maxAllowedTier(int skillLevel) {
        return MAX_TIER;
    }
}
