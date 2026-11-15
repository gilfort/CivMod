package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Alchemist's Producing specifics (vault {@code Jobs/Alchemist.md}): every {@code brewing} recipe in the recipe
 * manager, vanilla or from a datapack, at a brewing stand ({@code civmod:stations/alchemist}). A brewing stand needs no
 * fuel here. One brewing round takes the vanilla 400 ticks at level 20, as the Smelter takes the cooking time.
 */
public final class AlchemistProfile implements ProducingProfile {

    /** The Alchemist's station: brewing stands. */
    private static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/alchemist"));
    /** Ticks one brewing round takes in vanilla ({@code BrewingStandBlockEntity}). */
    private static final double BREWING_TICKS = 400.0;

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.BREWING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return STATION;
    }

    @Override
    public double workTime(RecipeHolder<?> recipe) {
        return BREWING_TICKS / SmelterProfile.TICKS_PER_OPERATION;
    }
}
