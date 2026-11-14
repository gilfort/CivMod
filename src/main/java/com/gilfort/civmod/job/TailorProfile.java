package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.registry.ModClothing;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Tailor's Producing specifics (vault {@code Jobs/Tailor.md}): {@link DomainProfile#tailor()} (crafting in the
 * textile domain at the Craftsperson's crafting table) plus the colour of the cloth. A piece of cloth the Tailor makes
 * is dyed with the most common colour among the wool it used; a tie goes to the lower dye id. White wool leaves the
 * piece undyed, which looks white. Only the twelve CivMod cloth pieces are dyed ({@link ModClothing#isCloth}); addon
 * items in the clothing tags are made as the recipe gives them.
 */
public final class TailorProfile implements ProducingProfile {

    /** Keeps the three colour channels of a texture colour and drops its alpha byte, as {@link DyedItemColor} stores them. */
    private static final int RGB_MASK = 0xFFFFFF;

    private final DomainProfile base = DomainProfile.tailor();

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return base.recipeTypes();
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return base.station(type);
    }

    @Override
    public Optional<TagKey<Item>> domain() {
        return base.domain();
    }

    @Override
    public List<ItemStack> execute(ServerLevel level, RecipeHolder<?> recipe, ItemStack result, List<ItemStack> consumed) {
        ItemStack made = result;
        if (ModClothing.isCloth(result)) {
            Optional<DyeColor> colour = WoolColors.dominant(consumed.stream().map(WoolColors::of).flatMap(Optional::stream).toList());
            if (colour.isPresent() && colour.get() != DyeColor.WHITE) {
                made = result.copy();
                made.set(DataComponents.DYED_COLOR, new DyedItemColor(colour.get().getTextureDiffuseColor() & RGB_MASK));
            }
        }
        return ProducingProfile.super.execute(level, recipe, made, consumed);
    }
}
