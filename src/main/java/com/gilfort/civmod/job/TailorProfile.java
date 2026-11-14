package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.registry.ModClothing.ClothSet;

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
 * is dyed with the most common colour among the wool it used. White wool leaves the piece undyed, which looks white.
 */
public final class TailorProfile implements ProducingProfile {

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
        if (isCloth(result)) {
            Optional<DyeColor> colour = WoolColors.dominant(consumed.stream().map(WoolColors::of).flatMap(Optional::stream).toList());
            if (colour.isPresent() && colour.get() != DyeColor.WHITE) {
                made = result.copy();
                made.set(DataComponents.DYED_COLOR, new DyedItemColor(colour.get().getTextureDiffuseColor() & 0xFFFFFF));
            }
        }
        return ProducingProfile.super.execute(level, recipe, made, consumed);
    }

    private static boolean isCloth(ItemStack stack) {
        for (ClothSet set : ClothSet.values()) {
            if (stack.is(set.tag())) {
                return true;
            }
        }
        return false;
    }
}
