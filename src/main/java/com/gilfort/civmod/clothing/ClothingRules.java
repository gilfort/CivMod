package com.gilfort.civmod.clothing;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.registry.ModClothing;
import com.gilfort.civmod.registry.ModClothing.ClothSet;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.Equippable;

/**
 * The rules of the clothing need (vault {@code Systems/Needs.md}, decisions of 2026-11-21), as plain values in and out: which tag a
 * tier asks for, what rank a piece has, which slot it fits, when it counts as worn out and when one piece may replace another.
 * <p>
 * A piece has a <em>rank</em>: 1 for a piece of {@code civmod:simple_clothing}, 2 for {@code civmod:better_clothing}, 3 for
 * {@code civmod:highest_clothing}, {@link #NO_RANK} for everything else (armour without a clothing tag is not clothing). A tier
 * asks for a minimum rank ({@link #requiredRank}); a piece of a higher rank satisfies it too.
 */
public final class ClothingRules {

    /** The rank of a piece that is not in a clothing tag. */
    public static final int NO_RANK = 0;

    /** The four slots in the order of the settler's armour slots ({@code Settler#ARMOR_START}): head, chest, legs, feet. */
    public static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /** A piece with less than this share of its durability left (percent) is worn out: the settler asks for a replacement. */
    public static final int WORN_OUT_PERCENT = 20;

    /** The cloth sets by rank: the set of rank {@code n} is at index {@code n - 1}. */
    private static final List<ClothSet> SETS = List.of(ClothSet.values());

    /** The minimum rank by tier (index 0 is unused): nothing at tier 1, simple at tier 2, better at tiers 3 and 4, highest at tier 5. */
    private static final int[] RANK_OF_TIER = {NO_RANK, NO_RANK, 1, 2, 2, 3};

    private ClothingRules() {
    }

    /**
     * @param tier a need tier
     * @return the lowest rank a piece needs to count for that tier; {@link #NO_RANK} for tier 1, which asks for nothing
     */
    public static int requiredRank(int tier) {
        return RANK_OF_TIER[Math.clamp(tier, 1, NeedState.MAX_TIER)];
    }

    /**
     * @param rank a rank above {@link #NO_RANK}
     * @return the tag that lists the pieces of that rank
     */
    public static TagKey<Item> tagOfRank(int rank) {
        return setOfRank(rank).tag();
    }

    /**
     * @param rank a rank above {@link #NO_RANK}
     * @return the cloth set of that rank
     */
    public static ClothSet setOfRank(int rank) {
        if (rank < 1 || rank > SETS.size()) {
            throw new IllegalArgumentException("No clothing set of rank " + rank);
        }
        return SETS.get(rank - 1);
    }

    /**
     * @param stack a stack
     * @return its rank: that of the highest clothing tag it is in, {@link #NO_RANK} if it is in none
     */
    public static int rank(ItemStack stack) {
        for (int rank = SETS.size(); rank >= 1; rank--) {
            if (stack.is(SETS.get(rank - 1).tag())) {
                return rank;
            }
        }
        return NO_RANK;
    }

    /**
     * @param stack a stack
     * @return the armour slot it fits (head, chest, legs or feet), empty if it is not wearable armour
     */
    public static Optional<EquipmentSlot> slotOf(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable == null ? Optional.empty() : Optional.of(equippable.slot()).filter(SLOTS::contains);
    }

    /**
     * @param slot a slot
     * @return the armour type of that slot
     */
    public static ArmorType armorTypeOf(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> ArmorType.HELMET;
            case CHEST -> ArmorType.CHESTPLATE;
            case LEGS -> ArmorType.LEGGINGS;
            case FEET -> ArmorType.BOOTS;
            default -> throw new IllegalArgumentException("Not a clothing slot: " + slot);
        };
    }

    /**
     * @param rank a rank above {@link #NO_RANK}
     * @param slot a slot
     * @return the cloth piece of that rank for that slot
     */
    public static Item clothOf(int rank, EquipmentSlot slot) {
        return ModClothing.piece(setOfRank(rank), armorTypeOf(slot)).get();
    }

    /**
     * @param stack a worn piece
     * @return whether it has less than {@value #WORN_OUT_PERCENT} % of its durability left; a piece that cannot wear is never worn out
     */
    public static boolean isWornOut(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        return (long) (max - stack.getDamageValue()) * 100 < (long) WORN_OUT_PERCENT * max;
    }

    /**
     * @param stack a stack
     * @return the share of its durability that is left (1 for a piece that cannot wear)
     */
    public static double durability(ItemStack stack) {
        return stack.isDamageableItem() ? (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage() : 1.0;
    }

    /**
     * Whether a settler puts a piece on in place of the one it wears: a piece of a higher rank always, one of the same rank only to
     * replace a worn-out piece by one that is not, one of a lower rank never. Any clothing replaces an empty slot or a piece that is
     * no clothing. The caller checks that both fit the slot.
     *
     * @param candidate the piece that might go on
     * @param worn      what the slot holds now, empty for none
     * @return whether the candidate replaces it
     */
    public static boolean replaces(ItemStack candidate, ItemStack worn) {
        int candidateRank = rank(candidate);
        if (candidateRank == NO_RANK) {
            return false;
        }
        int wornRank = worn.isEmpty() ? NO_RANK : rank(worn);
        if (wornRank == NO_RANK || candidateRank > wornRank) {
            return true;
        }
        return candidateRank == wornRank && isWornOut(worn) && !isWornOut(candidate);
    }
}
