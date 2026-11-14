package com.gilfort.civmod.datagen;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCraftingRecipe;

import com.mojang.serialization.Codec;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.common.data.JsonCodecProvider;

/**
 * The 16 innate recipes of the Concrete Mixer (vault {@code Jobs/Concrete Mixer.md}): one concrete powder gives one
 * concrete of the same colour, in {@code data/civmod/recipe/concrete_mixer/<colour>_concrete.json}. The recipes have no
 * station of their own: the water cauldron is the Mixer's main station.
 */
final class ConcreteMixerRecipes extends JsonCodecProvider<Recipe<?>> {

    private static final String DIRECTORY = "recipe";
    private static final String JOB = "concrete_mixer";
    /** Writes a recipe with its {@code type} field, like the files in a datapack. */
    private static final Codec<Recipe<?>> CODEC = BuiltInRegistries.RECIPE_SERIALIZER.byNameCodec().dispatch(Recipe::getSerializer,
            RecipeSerializer::codec);

    ConcreteMixerRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, PackOutput.Target.DATA_PACK, DIRECTORY, CODEC, lookup, CivMod.MOD_ID);
    }

    @Override
    protected void gather() {
        for (DyeColor colour : DyeColor.values()) {
            String name = colour.getSerializedName() + "_concrete";
            JobCraftingRecipe recipe = new JobCraftingRecipe(List.of(CivMod.id(JOB)), List.of(Ingredient.of(vanilla(name + "_powder"))),
                    new ItemStackTemplate(vanilla(name)), Optional.empty(), Optional.empty());
            unconditional(CivMod.id(JOB + "/" + name), recipe);
        }
    }

    private static Item vanilla(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
    }
}
