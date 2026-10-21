package com.gilfort.civmod.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;

/**
 * The biome bonuses acting on a Job at a biome (vault {@code Systems/JobBlock.md}, decisions on biome bonuses), resolved
 * from the datapack files {@code data/<namespace>/civmod_biome_bonuses/<name>.json} that name the Job and match the
 * biome. Ask for the bonuses acting on a settler today with {@link CivModApi#biomeBonus}.
 * <p>
 * Combining rules: the {@code speed} and {@code rare_chance} factors of all matching files multiply (1.1 × 1.1 = 1.21);
 * the {@code double_drop} chances of all entries matching a produced item add up ({@link #doubleDropChance}). A Job
 * without a rare group ({@link ResourceCollectionProfile#rareGroup()}) has a rare chance factor of 1.
 *
 * @param biome       the biome the bonuses were read at, empty if the Job has no biome position (Mission Jobs) or no
 *                    work place yet
 * @param speed       the factor on the Job's work speed
 * @param rareChance  the factor on the chance of the rare group of a roll ({@link #rareChance(double)})
 * @param doubleDrops the {@code double_drop} entries of all matching files, in file order
 */
@ApiStatus.Experimental
public record BiomeBonus(Optional<ResourceKey<Biome>> biome, Factor speed, Factor rareChance, List<DoubleDrop> doubleDrops) {

    /** No biome and no effect. */
    public static final BiomeBonus NONE = new BiomeBonus(Optional.empty(), Factor.ONE, Factor.ONE, List.of());

    /**
     * @param biome       the biome
     * @param speed       the speed factor
     * @param rareChance  the rare chance factor
     * @param doubleDrops the double drop entries
     */
    public BiomeBonus {
        doubleDrops = List.copyOf(doubleDrops);
    }

    /**
     * @return whether no file acts on the Job (the biome may still be known)
     */
    public boolean isEmpty() {
        return speed.sources().isEmpty() && rareChance.sources().isEmpty() && doubleDrops.isEmpty();
    }

    /**
     * @param baseChance the chance of the rare group without bonuses (Miner: the ore share, Fisherman: the treasure
     *                   chance)
     * @return the chance with the rare chance factor, capped at 1.0
     */
    public double rareChance(double baseChance) {
        return Math.min(1.0, baseChance * rareChance.value());
    }

    /**
     * @param stack a produced stack
     * @return the double drop entries matching its item
     */
    public List<DoubleDrop> doubleDrops(ItemStack stack) {
        return doubleDrops.stream().filter(entry -> entry.matches(stack)).toList();
    }

    /**
     * @param stack a produced stack
     * @return the sum S of the double drop chances matching its item (0 without any)
     */
    public double doubleDropChance(ItemStack stack) {
        double sum = 0.0;
        for (DoubleDrop entry : doubleDrops) {
            if (entry.matches(stack)) {
                sum += entry.chance();
            }
        }
        return sum;
    }

    /**
     * How many copies of a produced stack the settler gets: with S ≥ 0 the stack plus ⌊S⌋ copies plus one more with
     * probability S − ⌊S⌋; with S &lt; 0 none (the drop is lost) with probability |S|, else the stack itself.
     *
     * @param stack  a produced stack
     * @param random the random source
     * @return the number of copies (0 = lost, 1 = the stack as it is)
     */
    public int copies(ItemStack stack, RandomSource random) {
        double sum = doubleDropChance(stack);
        if (sum == 0.0) {
            return 1;
        }
        if (sum < 0.0) {
            return random.nextDouble() < -sum ? 0 : 1;
        }
        int whole = (int) Math.floor(sum);
        return 1 + whole + (random.nextDouble() < sum - whole ? 1 : 0);
    }

    /**
     * Applies {@code double_drop} to the stacks of one roll: each stack is lost or copied ({@link #copies}); a copy is
     * a copy of the produced stack (after Fortune / Silk Touch, Fortune is not rolled again).
     *
     * @param stacks the produced stacks
     * @param random the random source
     * @return the stacks the settler gets (the same list without a double drop entry)
     */
    public List<ItemStack> applyDoubleDrop(List<ItemStack> stacks, RandomSource random) {
        if (doubleDrops.isEmpty()) {
            return stacks;
        }
        List<ItemStack> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            int copies = stack.isEmpty() ? 1 : copies(stack, random);
            for (int i = 0; i < copies; i++) {
                result.add(i == 0 ? stack : stack.copy());
            }
        }
        return result;
    }

    /**
     * A factor of {@code speed} or {@code rare_chance}.
     *
     * @param value   the product of the matching files' values (1 without any)
     * @param sources the ids of the files that set the value
     */
    @ApiStatus.Experimental
    public record Factor(double value, List<Identifier> sources) {

        /** No file sets the value. */
        public static final Factor ONE = new Factor(1.0, List.of());

        /**
         * @param value   the factor
         * @param sources the source files
         */
        public Factor {
            sources = List.copyOf(sources);
        }
    }

    /**
     * One {@code double_drop} entry of a bonus file.
     *
     * @param items  the item ids and {@code #item tags} it matches
     * @param chance its chance (−1 … 10; negative chances lose drops)
     * @param source the id of its file
     */
    @ApiStatus.Experimental
    public record DoubleDrop(List<ExtraCodecs.TagOrElementLocation> items, double chance, Identifier source) {

        /**
         * @param items  the items
         * @param chance the chance
         * @param source the source file
         */
        public DoubleDrop {
            items = List.copyOf(items);
        }

        /**
         * @param stack a produced stack
         * @return whether the entry matches its item
         */
        public boolean matches(ItemStack stack) {
            for (ExtraCodecs.TagOrElementLocation item : items) {
                if (item.tag() ? stack.typeHolder().is(TagKey.create(Registries.ITEM, item.id())) : stack.typeHolder().is(item.id())) {
                    return true;
                }
            }
            return false;
        }
    }
}
