package com.gilfort.civmod.logistics;

import java.util.stream.Stream;

import com.gilfort.civmod.registry.ModRecipes;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

/**
 * The ingredient of one {@link ItemKey}: an item with its relevant components, so an exact pattern with a Potion of
 * Healing in its grid takes that potion and no other. A key without components is the plain item ingredient
 * ({@link #of(ItemKey)}).
 *
 * @param key the item and relevant components a stack must have
 */
public record ItemKeyIngredient(ItemKey key) implements ICustomIngredient {

    /** Codec of the ingredient type. */
    public static final MapCodec<ItemKeyIngredient> CODEC = ItemKey.MAP_CODEC.xmap(ItemKeyIngredient::new, ItemKeyIngredient::key);
    /** The type to register as {@code civmod:item_key}. */
    public static final IngredientType<ItemKeyIngredient> TYPE = new IngredientType<>(CODEC,
            ItemKey.STREAM_CODEC.map(ItemKeyIngredient::new, ItemKeyIngredient::key));

    /**
     * @param key an item with its relevant components
     * @return the plain item ingredient for a key without components, else the component-aware one
     */
    public static Ingredient of(ItemKey key) {
        return key.components().isEmpty() ? Ingredient.of(key.item()) : new ItemKeyIngredient(key).toVanilla();
    }

    @Override
    public boolean test(ItemStack stack) {
        return key.matches(stack);
    }

    @Override
    public Stream<Holder<Item>> items() {
        return Stream.of(key.item().builtInRegistryHolder());
    }

    /** Not simple: the match looks at components, so the ingredient is no plain item list. */
    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public IngredientType<?> getType() {
        return ModRecipes.ITEM_KEY.get();
    }
}
