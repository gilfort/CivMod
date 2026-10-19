package com.gilfort.civmod.example;

import java.util.List;

import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Stonecutter's Producing specifics: vanilla (and modded) {@code stonecutting} recipes at a stonecutter, named by the
 * block tag {@code civmod_example:stations/stonecutter} ({@code data/civmod_example/tags/block/stations/stonecutter.json}).
 * Filters, checkmark limit, work time and the execution keep CivMod's defaults.
 */
final class StonecutterProfile implements ProducingProfile {

    /** The station: stonecutters. */
    static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(CivModExample.MOD_ID, "stations/stonecutter"));

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.STONECUTTING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return STATION;
    }
}
