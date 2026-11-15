package com.gilfort.civmod.api;

import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

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
 * The list of items is worked out when the ingredient is first asked for it and kept until the next datapack reload
 * ({@link #invalidate()}).
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

    /** The compostable items, null until first asked for and again after a reload. */
    private static volatile @Nullable List<Holder<Item>> cachedItems;

    /** Drops the kept item list, called on every datapack reload so items a pack changed are seen. */
    @ApiStatus.Internal
    public static void invalidate() {
        cachedItems = null;
    }

    @Override
    public Stream<Holder<Item>> items() {
        List<Holder<Item>> items = cachedItems;
        if (items == null) {
            items = BuiltInRegistries.ITEM.listElements().filter(item -> item.value().components().has(DataComponents.COMPOSTABLE))
                    .<Holder<Item>>map(item -> item).toList();
            cachedItems = items;
        }
        return items.stream();
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
