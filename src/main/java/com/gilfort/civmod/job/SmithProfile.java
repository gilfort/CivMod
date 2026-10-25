package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Smith's Producing specifics (vault {@code Jobs/Smith.md}): {@code crafting} and {@code smithing} recipes (netherite
 * upgrades, no armor trims) at a smithing table ({@code civmod:stations/smith}), only in the metal domain
 * ({@code civmod:metals}, any input in the tag). Its base job factor 0.3 becomes 1.2 with an anvil near the smithing
 * table (the station extra of {@code civmod_jobs/smith.json}).
 */
public final class SmithProfile implements ProducingProfile {

    /** The Smith's station for both recipe types: smithing tables. */
    private static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/smith"));

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.CRAFTING, RecipeType.SMITHING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return STATION;
    }

    @Override
    public Optional<TagKey<Item>> domain() {
        return Optional.of(Patterns.METALS);
    }
}
