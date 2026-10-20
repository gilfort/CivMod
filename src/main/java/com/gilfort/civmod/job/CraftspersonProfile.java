package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Craftsperson's Producing specifics (vault {@code Jobs/Craftsperson.md}): {@code crafting} recipes at a crafting
 * table ({@code civmod:stations/craftsperson}), any item; it is slow (job factor 0.3), crafting Specializations are
 * faster within their domain.
 */
public final class CraftspersonProfile implements ProducingProfile {

    /** The Craftsperson's station: crafting tables. */
    public static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/craftsperson"));

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.CRAFTING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return STATION;
    }
}
