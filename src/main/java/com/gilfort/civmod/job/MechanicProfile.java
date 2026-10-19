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
 * The Mechanic's Producing specifics (vault {@code Jobs/Mechanic.md}): {@code crafting} recipes at a Crafter
 * ({@code civmod:stations/mechanic}), only in the redstone domain ({@code civmod:domains/redstone}, any input in the tag),
 * four times as fast as the Craftsperson (job factor 1.2).
 */
public final class MechanicProfile implements ProducingProfile {

    /** The Mechanic's station: Crafters. */
    public static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, CivMod.id("stations/mechanic"));
    /** The Mechanic's domain: redstone items. */
    public static final TagKey<Item> DOMAIN = TagKey.create(Registries.ITEM, CivMod.id("domains/redstone"));

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
