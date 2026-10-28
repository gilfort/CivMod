package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Stonemason's Producing specifics (vault {@code Jobs/Stonemason.md}): {@code stonecutting} recipes at a stonecutter
 * ({@code civmod:stations/stonemason}, the main type: its station of the day) and {@code crafting} recipes at a crafting
 * table nearby ({@code civmod:stations/craftsperson}), only in the stone domain ({@code civmod:domains/stone}; any input
 * in the tag). Its base job factor 0.3 becomes 1.2 with a grindstone near the stonecutter (the station extra of
 * {@code civmod_jobs/stonemason.json}). It has no innate recipe.
 */
public final class StonemasonProfile implements ProducingProfile {

    /** The Stonemason's station of the day: stonecutters. */
    private static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/stonemason"));
    /** The Stonemason's domain. */
    private static final TagKey<Item> DOMAIN = TagKey.create(Registries.ITEM, CivMod.id("domains/stone"));

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.STONECUTTING, RecipeType.CRAFTING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return type == RecipeType.CRAFTING ? CraftspersonProfile.STATION : STATION;
    }

    @Override
    public Optional<TagKey<Item>> domain() {
        return Optional.of(DOMAIN);
    }
}
