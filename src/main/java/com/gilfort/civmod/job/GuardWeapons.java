package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

/**
 * The weapons of a Guard (vault {@code Jobs/Guard.md}, "Weapons", "Modded weapons through item tags"): swords, axes and
 * spears by their item tags, so modded ones count when a mod tags them. Mace and trident are not in those tags and are not
 * weapons of a Guard. Of several on the belt the Guard takes the one that hits hardest among those its skill allows.
 */
final class GuardWeapons {

    /** The tool of the Guard Job for the tool supply: its weapon, shown as an iron sword in the "tool missing" bubble. */
    static final ToolKind KIND = new ToolKind(GuardWeapons::isWeapon, () -> Items.IRON_SWORD, ToolStaircase::allowedOnly, GuardWeapons::best);

    private GuardWeapons() {
    }

    /**
     * @param stack an item stack
     * @return whether it is a sword, an axe or a spear
     */
    static boolean isWeapon(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS));
    }

    /**
     * @param stacks the stacks of the belt
     * @param rule   what the Guard's skill allows
     * @param isTool which stacks are weapons
     * @return the weapon that hits hardest among the allowed ones; with equal damage the better ranked one
     */
    static Optional<ItemStack> best(Iterable<ItemStack> stacks, ToolStaircase.Rule rule, Predicate<ItemStack> isTool) {
        Comparator<ItemStack> byDamage = Comparator.comparingDouble(GuardCombat::weaponDamage);
        ItemStack best = ItemStack.EMPTY;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !isTool.test(stack) || !rule.allows(stack)) {
                continue;
            }
            if (best.isEmpty() || byDamage.thenComparingInt(rule::rank).compare(stack, best) > 0) {
                best = stack;
            }
        }
        return best.isEmpty() ? Optional.empty() : Optional.of(best);
    }
}
