package com.gilfort.civmod.api;

import java.util.stream.Stream;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.registry.ModRecipes;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

/**
 * The ingredient {@code {"neoforge:ingredient_type": "civmod:compostable"}}: any item a vanilla composter takes, modded items included (an
 * item is compostable when it has the {@code compostable} component). The Composter's innate recipe uses it for its input.
 * The list of items is worked out once per loaded recipe, when the ingredient is first asked for it.
 */
@ApiStatus.Experimental
public record CompostableIngredient() implements ICustomIngredient {

    /** Datapack codec: the ingredient has no fields. */
    public static final MapCodec<CompostableIngredient> CODEC = MapCodec.unit(new CompostableIngredient());
    /** The type to register as {@code civmod:compostable}. */
    public static final IngredientType<CompostableIngredient> TYPE = new IngredientType<>(CODEC, StreamCodec.unit(new CompostableIngredient()));

    @Override
    public boolean test(ItemStack stack) {
        return stack.has(DataComponents.COMPOSTABLE);
    }

    @Override
    public Stream<Holder<Item>> items() {
        return BuiltInRegistries.ITEM.listElements().filter(item -> item.value().components().has(DataComponents.COMPOSTABLE)).map(item -> (Holder<Item>) item);
    }

    /** Not simple: the compostable items depend on the loaded data, so the ingredient is no fixed list. */
    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public IngredientType<?> getType() {
        return ModRecipes.COMPOSTABLE.get();
    }
}
