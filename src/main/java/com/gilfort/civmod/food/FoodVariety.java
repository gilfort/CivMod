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

        /** The first tier that asks for variety: 3 foods. */
        public static final int THREE_FOODS_TIER = 2;
        /** The tier from which 5 foods are asked. */
        public static final int FIVE_FOODS_TIER = 4;
        /** The tier from which 10 foods are asked, and the favourite meal. */
        public static final int TEN_FOODS_TIER = 5;
        private static final int THREE_FOODS = 3;
        private static final int FIVE_FOODS = 5;
        private static final int TEN_FOODS = 10;

        /**
         * @param tier a need tier
         * @return the diet of that tier: 3 foods up to tier 3, 5 foods at tier 4, 10 foods at tier 5, each with the window of the server config
         */
        public static Diet forTier(int tier) {
            if (tier >= TEN_FOODS_TIER) {
                return new Diet(TEN_FOODS, CivModConfig.FOOD_VARIETY_DAYS_10.get());
            }
            if (tier >= FIVE_FOODS_TIER) {
                return new Diet(FIVE_FOODS, CivModConfig.FOOD_VARIETY_DAYS_5.get());
            }
            return new Diet(THREE_FOODS, CivModConfig.FOOD_VARIETY_DAYS.get());
        }

        /**
         * @param tier a need tier
         * @return the diets a settler of that tier has to meet: its own and those of the lower tiers, the lowest first
         */
        public static List<Diet> upToTier(int tier) {
            List<Diet> diets = new ArrayList<>();
            diets.add(forTier(THREE_FOODS_TIER));
            if (tier >= FIVE_FOODS_TIER) {
                diets.add(forTier(FIVE_FOODS_TIER));
            }
            if (tier >= TEN_FOODS_TIER) {
                diets.add(forTier(TEN_FOODS_TIER));
            }
            return List.copyOf(diets);
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
     * What a settler wants on its plate at a food point: the diets of its tier and the lower ones, and the favourite meal when its tier
     * asks for it.
     *
     * @param diets     the diets the settler has to meet
     * @param favourite the favourite meal the tier asks for, empty if the tier asks for none or the settler has none
     */
    public record Wishes(List<Diet> diets, Optional<Identifier> favourite) {

        /**
         * @param tier      the settler's need tier
         * @param favourite the settler's favourite meal, empty if it has none
         * @return the wishes of a settler of that tier
         */
        public static Wishes forTier(int tier, Optional<Identifier> favourite) {
            return new Wishes(Diet.upToTier(tier), Diet.asksForFavourite(tier) ? favourite : Optional.empty());
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
     * The food choice at a food point: the favourite meal first while the tier asks for it and it is missing from the window of the tier 5
     * diet; then, while any diet of the settler is short, a food that is outside the window of a short diet, even a smaller one;
     * otherwise {@link SettlerFood#choose(List, int)}. The rule against overeating always applies.
     *
     * @param stacks candidate stacks (any items)
     * @param level  the food level
     * @param today  the day
     * @param wishes what the settler's tier asks for
     * @return the index of the chosen stack, empty if nothing may be eaten
     */
    public Optional<Integer> choose(List<ItemStack> stacks, int level, long today, Wishes wishes) {
        if (wishes.favourite().isPresent()) {
            Identifier favourite = wishes.favourite().get();
            if (!window(today, Diet.forTier(Diet.TEN_FOODS_TIER)).contains(favourite)) {
                Optional<Integer> favourited = SettlerFood.choose(stacks, level, stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(favourite));
                if (favourited.isPresent()) {
                    return favourited;
                }
            }
        }
        List<Set<Identifier>> shortWindows = wishes.diets().stream().filter(diet -> isShort(today, diet)).map(diet -> window(today, diet)).toList();
        if (!shortWindows.isEmpty()) {
            Optional<Integer> fresh = SettlerFood.choose(stacks, level,
                    stack -> shortWindows.stream().anyMatch(window -> !window.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
            if (fresh.isPresent()) {
                return fresh;
            }
        }
        return SettlerFood.choose(stacks, level);
    }
}
