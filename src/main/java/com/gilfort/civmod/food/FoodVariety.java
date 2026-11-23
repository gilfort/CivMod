package com.gilfort.civmod.food;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.config.CivModConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * What a settler ate lately and the rule of the Food variety need (vault {@code Systems/Needs.md}): at least the foods of
 * its {@link Diet} as distinct food items within the window. The window is the last days, but always covers at least the
 * last items eaten, twice as many as the foods it asks for, so a light eater with large meals does not fail for eating little. Meals keep their
 * item id: a food of a removed mod still counts for the days it stays in the window.
 */
public final class FoodVariety {

    /** Longest list of meals: the oldest goes first. */
    public static final int MAX_MEALS = 64;

    /**
     * What a tier asks for: a number of distinct foods within a number of days.
     *
     * @param foods the distinct foods the need asks for
     * @param days  how many days the window reaches back, today included
     */
    public record Diet(int foods, int days) {

        /** The tier from which 5 foods are asked. */
        private static final int FIVE_FOODS_TIER = 4;
        /** The tier from which 10 foods are asked. */
        private static final int TEN_FOODS_TIER = 5;

        /**
         * @param tier a need tier
         * @return the diet of that tier: 3 foods up to tier 3, 5 foods at tier 4, 10 foods at tier 5, each with the window of the server config
         */
        public static Diet forTier(int tier) {
            if (tier >= TEN_FOODS_TIER) {
                return new Diet(10, CivModConfig.FOOD_VARIETY_DAYS_10.get());
            }
            if (tier >= FIVE_FOODS_TIER) {
                return new Diet(5, CivModConfig.FOOD_VARIETY_DAYS_5.get());
            }
            return new Diet(3, CivModConfig.FOOD_VARIETY_DAYS.get());
        }

        /**
         * @param tier a need tier
         * @return whether that tier asks for the settler's favourite meal within its diet: only tier 5 does
         */
        public static boolean asksForFavourite(int tier) {
            return tier >= TEN_FOODS_TIER;
        }

        /**
         * @return the items the window always covers, however old they are
         */
        int coveredItems() {
            return 2 * foods;
        }
    }

    /**
     * One food item eaten.
     *
     * @param item the item id
     * @param day  the in-game day it was eaten on (simulated in catch-up)
     */
    public record Meal(Identifier item, long day) {

        /** Save codec. */
        public static final Codec<Meal> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("item").forGetter(Meal::item),
                Codec.LONG.fieldOf("day").forGetter(Meal::day)).apply(instance, Meal::new));
    }

    /** Oldest first. */
    private final List<Meal> meals = new ArrayList<>();

    /**
     * Notes one food item eaten.
     *
     * @param item the item id
     * @param day  the day
     */
    public void record(Identifier item, long day) {
        meals.add(new Meal(item, day));
        trim();
    }

    private void trim() {
        if (meals.size() > MAX_MEALS) {
            meals.subList(0, meals.size() - MAX_MEALS).clear();
        }
    }

    /**
     * @return the remembered meals, oldest first
     */
    public List<Meal> meals() {
        return List.copyOf(meals);
    }

    /**
     * @param saved the saved meals, oldest first
     */
    void restore(List<Meal> saved) {
        meals.clear();
        meals.addAll(saved);
        trim();
    }

    /**
     * @param today the day
     * @param diet  what the tier asks for
     * @return the distinct foods in the window, the oldest first
     */
    public Set<Identifier> window(long today, Diet diet) {
        int lastItems = meals.size() - diet.coveredItems();
        Set<Identifier> foods = new LinkedHashSet<>();
        for (int i = 0; i < meals.size(); i++) {
            Meal meal = meals.get(i);
            if (i >= lastItems || meal.day() > today - diet.days()) {
                foods.add(meal.item());
            }
        }
        return foods;
    }

    /**
     * @param today the day
     * @param diet  what the tier asks for
     * @return the number of distinct foods in the window
     */
    public int distinct(long today, Diet diet) {
        return window(today, diet).size();
    }

    /**
     * @param today the day
     * @param diet  what the tier asks for
     * @return whether the settler ate too few different foods
     */
    public boolean isShort(long today, Diet diet) {
        return distinct(today, diet) < diet.foods();
    }

    /**
     * The food choice at a food point: while variety is short a food outside the window, even a smaller one; otherwise
     * {@link SettlerFood#choose(List, int)}. The rule against overeating always applies.
     *
     * @param stacks candidate stacks (any items)
     * @param level  the food level
     * @param today  the day
     * @param diet   what the settler's tier asks for
     * @return the index of the chosen stack, empty if nothing may be eaten
     */
    public Optional<Integer> choose(List<ItemStack> stacks, int level, long today, Diet diet) {
        return choose(stacks, level, today, diet, Optional.empty());
    }

    /**
     * The food choice at a food point of a settler that asks for its favourite meal (tier 5): the favourite first while it is missing
     * from the window, then the choice of {@link #choose(List, int, long, Diet)}.
     *
     * @param stacks    candidate stacks (any items)
     * @param level     the food level
     * @param today     the day
     * @param diet      what the settler's tier asks for
     * @param favourite the favourite meal the tier asks for, empty if the tier asks for none or the settler has none
     * @return the index of the chosen stack, empty if nothing may be eaten
     */
    public Optional<Integer> choose(List<ItemStack> stacks, int level, long today, Diet diet, Optional<Identifier> favourite) {
        if (favourite.isPresent() && !window(today, diet).contains(favourite.get())) {
            Optional<Integer> favourited = SettlerFood.choose(stacks, level, stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(favourite.get()));
            if (favourited.isPresent()) {
                return favourited;
            }
        }
        if (isShort(today, diet)) {
            Set<Identifier> window = window(today, diet);
            Optional<Integer> fresh = SettlerFood.choose(stacks, level, stack -> !window.contains(BuiltInRegistries.ITEM.getKey(stack.getItem())));
            if (fresh.isPresent()) {
                return fresh;
            }
        }
        return SettlerFood.choose(stacks, level);
    }
}
