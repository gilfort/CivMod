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
 * The Fletcher's Producing specifics (vault {@code Jobs/Fletcher.md}): {@code crafting} recipes at a fletching table
 * ({@code civmod:stations/fletcher}), only in the fletching domain ({@code civmod:domains/fletching}: string, feathers,
 * flint, arrows; any input in the tag). Its base job factor 0.3 becomes 1.2 with a target near the fletching table (the
 * station extra of {@code civmod_jobs/fletcher.json}). Its innate recipes (wool → string with shears, gravel → flint)
 * come from {@code civmod:job_crafting} recipes in the datapack.
 */
public final class FletcherProfile implements ProducingProfile {

    /** The Fletcher's station: fletching tables. */
    private static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/fletcher"));
    /** The Fletcher's domain. */
    private static final TagKey<Item> DOMAIN = TagKey.create(Registries.ITEM, CivMod.id("domains/fletching"));

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
        return Optional.of(DOMAIN);
    }
}
