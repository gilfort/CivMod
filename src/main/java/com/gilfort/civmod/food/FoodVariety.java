package com.gilfort.civmod.food;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * What a settler ate lately and the rule of the Food variety need (vault {@code Systems/Needs.md}): at least
 * {@value #REQUIRED} distinct food items within the window. The window is the last days, but always covers at least the
 * last {@value #COVERED_ITEMS} items eaten, so a light eater with large meals does not fail for eating little.
 * Meals keep their item id: a food of a removed mod still counts for the days it stays in the window.
 */
public final class FoodVariety {

    /** Distinct foods the need asks for. */
    public static final int REQUIRED = 3;
    /** Items the window always covers. */
    private static final int COVERED_ITEMS = 2 * REQUIRED;
    /** Longest list of meals: the oldest goes first. */
    public static final int MAX_MEALS = 64;

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
     * @param days  how many days the window reaches back, today included
     * @return the distinct foods in the window, the oldest first
     */
    public Set<Identifier> window(long today, int days) {
        int lastItems = meals.size() - COVERED_ITEMS;
        Set<Identifier> foods = new LinkedHashSet<>();
        for (int i = 0; i < meals.size(); i++) {
            Meal meal = meals.get(i);
            if (i >= lastItems || meal.day() > today - days) {
                foods.add(meal.item());
            }
        }
        return foods;
    }

    /**
     * @param today the day
     * @param days  how many days the window reaches back, today included
     * @return the number of distinct foods in the window
     */
    public int distinct(long today, int days) {
        return window(today, days).size();
    }

    /**
     * @param today the day
     * @param days  how many days the window reaches back, today included
     * @return whether the settler ate too few different foods
     */
    public boolean isShort(long today, int days) {
        return distinct(today, days) < REQUIRED;
    }

    /**
     * The food choice at a food point: while variety is short a food outside the window, even a smaller one; otherwise
     * {@link SettlerFood#choose(List, int)}. The rule against overeating always applies.
     *
     * @param stacks candidate stacks (any items)
     * @param level  the food level
     * @param today  the day
     * @param days   how many days the window reaches back, today included
     * @return the index of the chosen stack, empty if nothing may be eaten
     */
    public Optional<Integer> choose(List<ItemStack> stacks, int level, long today, int days) {
        if (isShort(today, days)) {
            Set<Identifier> window = window(today, days);
            Optional<Integer> fresh = SettlerFood.choose(stacks, level, stack -> !window.contains(BuiltInRegistries.ITEM.getKey(stack.getItem())));
            if (fresh.isPresent()) {
                return fresh;
            }
        }
        return SettlerFood.choose(stacks, level);
    }
}
