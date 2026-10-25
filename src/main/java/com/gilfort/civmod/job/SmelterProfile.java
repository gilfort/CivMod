package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Smelter's Producing specifics (vault {@code Jobs/Smelter.md}): {@code smelting} recipes at furnaces
 * ({@code civmod:stations/smelter}), any item; up to one furnace per JobBlock level at once, coal and charcoal as fuel.
 * One item takes the recipe's cooking time at level 20 (vanilla speed) per furnace.
 */
public final class SmelterProfile implements ProducingProfile {

    /** The Smelter's station: furnaces. */
    public static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/smelter"));
    /**
     * Ticks of cooking time per operation of the shared speed formula: at level 20 (0.25 operations per second) an
     * item takes its cooking time.
     */
    private static final double TICKS_PER_OPERATION = 80.0;

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.SMELTING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return STATION;
    }

    @Override
    public double workTime(RecipeHolder<?> recipe) {
        return recipe.value() instanceof AbstractCookingRecipe cooking ? cooking.cookingTime() / TICKS_PER_OPERATION : 1.0;
    }

    @Override
    public int parallelStations(int jobBlockLevel) {
        return Math.clamp(jobBlockLevel, 1, 5);
    }

    @Override
    public Optional<List<Item>> fuel() {
        return Optional.of(List.of(Items.COAL, Items.CHARCOAL));
    }
}
