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
 * The Dyer's Producing specifics (vault {@code Jobs/Dyer.md}): {@code crafting} recipes at a loom
 * ({@code civmod:stations/dyer}), only in the dye domain ({@code civmod:domains/dye}: dyes and their crafting sources;
 * any input in the tag), so dyes and every dyed block. Its base job factor 0.3 becomes 1.2 with a water cauldron near
 * the loom (the station extra of {@code civmod_jobs/dyer.json}). Its innate recipe (dyed wool → white wool at a water
 * cauldron) comes from a {@code civmod:job_crafting} recipe in the datapack.
 */
public final class DyerProfile implements ProducingProfile {

    /** The Dyer's station: looms. */
    private static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/dyer"));
    /** The Dyer's domain. */
    private static final TagKey<Item> DOMAIN = TagKey.create(Registries.ITEM, CivMod.id("domains/dye"));

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
