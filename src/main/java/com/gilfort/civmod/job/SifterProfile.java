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
 * The Sifter's Producing specifics (vault {@code Jobs/Sifter.md}): it works innate recipes only, so players cannot
 * make patterns for it. {@code crafting} stays its (unused) main recipe type, because a profile needs one, and its
 * domain tag {@code civmod:domains/sifter} is empty: every crafting pattern is "Outside this Job's domain". The innate
 * recipes ({@code recipe/sifter/}) name the pool they draw from; the station is a hopper
 * ({@code civmod:stations/sifter}).
 */
public final class SifterProfile implements ProducingProfile {

    private static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/sifter"));
    private static final TagKey<Item> NO_DOMAIN = TagKey.create(Registries.ITEM, CivMod.id("domains/sifter"));

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.CRAFTING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return STATION;
    }

    @Override
    public Optional<TagKey<Item>> domain() {
        return Optional.of(NO_DOMAIN);
    }
}
