package com.gilfort.civmod.registry;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CompostableIngredient;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.logistics.ItemKeyIngredient;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * CivMod's recipe type and serializer: {@code civmod:job_crafting}, the innate recipes of Producing Jobs
 * ({@link JobCraftingRecipe}).
 */
public final class ModRecipes {

    private static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, CivMod.MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, CivMod.MOD_ID);

    /** The innate recipes of Producing Jobs; no block crafts them. */
    public static final DeferredHolder<RecipeType<?>, RecipeType<JobCraftingRecipe>> JOB_CRAFTING = TYPES.register("job_crafting",
            () -> RecipeType.simple(CivMod.id("job_crafting")));
    /** The serializer of {@code civmod:job_crafting}. */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<JobCraftingRecipe>> JOB_CRAFTING_SERIALIZER = SERIALIZERS.register(
            "job_crafting", () -> JobCraftingRecipe.SERIALIZER);

    private static final DeferredRegister<IngredientType<?>> INGREDIENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.INGREDIENT_TYPES, CivMod.MOD_ID);

    /** The ingredient {@code civmod:compostable} of the Composter's recipe. */
    public static final DeferredHolder<IngredientType<?>, IngredientType<CompostableIngredient>> COMPOSTABLE = INGREDIENT_TYPES.register("compostable",
            () -> CompostableIngredient.TYPE);

    /** The ingredient {@code civmod:item_key}: an item with its relevant components (exact patterns). */
    public static final DeferredHolder<IngredientType<?>, IngredientType<ItemKeyIngredient>> ITEM_KEY = INGREDIENT_TYPES.register("item_key",
            () -> ItemKeyIngredient.TYPE);

    private ModRecipes() {
    }

    /**
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
        SERIALIZERS.register(modEventBus);
        INGREDIENT_TYPES.register(modEventBus);
    }
}
