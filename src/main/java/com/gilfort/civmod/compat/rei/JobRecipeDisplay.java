package com.gilfort.civmod.compat.rei;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * REI's display of one innate recipe ({@code civmod:job_crafting}) in the display-only "Job Recipes" category.
 */
final class JobRecipeDisplay extends BasicDisplay {

    /** The category. */
    static final CategoryIdentifier<JobRecipeDisplay> CATEGORY = CategoryIdentifier.of(CivMod.id("job_crafting"));

    private static final MapCodec<JobRecipeDisplay> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Recipe.KEY_CODEC.fieldOf("id").forGetter(display -> display.holder.id()),
            JobCraftingRecipe.MAP_CODEC.fieldOf("recipe").forGetter(display -> display.holder.value())
    ).apply(instance, (id, recipe) -> new JobRecipeDisplay(new RecipeHolder<>(id, recipe))));
    private static final StreamCodec<RegistryFriendlyByteBuf, JobRecipeDisplay> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(net.minecraft.core.registries.Registries.RECIPE), display -> display.holder.id(),
            JobCraftingRecipe.STREAM_CODEC, display -> display.holder.value(),
            (id, recipe) -> new JobRecipeDisplay(new RecipeHolder<>(id, recipe)));
    /** Not saved: the displays come from the recipes the server sent. */
    static final DisplaySerializer<JobRecipeDisplay> SERIALIZER = DisplaySerializer.of(CODEC, STREAM_CODEC, false);

    private final RecipeHolder<JobCraftingRecipe> holder;

    JobRecipeDisplay(RecipeHolder<JobCraftingRecipe> holder) {
        super(EntryIngredients.ofIngredients(holder.value().ingredients()), List.of(EntryIngredients.of(holder.value().result())),
                Optional.of(holder.id().identifier()));
        this.holder = holder;
    }

    /**
     * @return the innate recipe
     */
    JobCraftingRecipe recipe() {
        return holder.value();
    }

    /**
     * @return its id
     */
    Identifier id() {
        return holder.id().identifier();
    }

    @Override
    public CategoryIdentifier<?> getCategoryIdentifier() {
        return CATEGORY;
    }

    @Override
    public DisplaySerializer<? extends JobRecipeDisplay> getSerializer() {
        return SERIALIZER;
    }
}
