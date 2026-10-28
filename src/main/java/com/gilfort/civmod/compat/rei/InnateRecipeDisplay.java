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
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * REI's display of one innate recipe ({@code civmod:job_crafting}) in the display-only "Job Recipes" category.
 */
final class InnateRecipeDisplay extends BasicDisplay {

    /** The category. */
    static final CategoryIdentifier<InnateRecipeDisplay> CATEGORY = CategoryIdentifier.of(CivMod.id("job_crafting"));

    private static final MapCodec<InnateRecipeDisplay> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Recipe.KEY_CODEC.fieldOf("id").forGetter(display -> display.holder.id()),
            JobCraftingRecipe.MAP_CODEC.fieldOf("recipe").forGetter(display -> display.holder.value())
    ).apply(instance, (id, recipe) -> new InnateRecipeDisplay(new RecipeHolder<>(id, recipe))));
    private static final StreamCodec<RegistryFriendlyByteBuf, InnateRecipeDisplay> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.RECIPE), display -> display.holder.id(),
            JobCraftingRecipe.STREAM_CODEC, display -> display.holder.value(),
            (id, recipe) -> new InnateRecipeDisplay(new RecipeHolder<>(id, recipe)));
    /** Not saved: the displays come from the recipes the server sent. */
    static final DisplaySerializer<InnateRecipeDisplay> SERIALIZER = DisplaySerializer.of(CODEC, STREAM_CODEC, false);

    private final RecipeHolder<JobCraftingRecipe> holder;

    InnateRecipeDisplay(RecipeHolder<JobCraftingRecipe> holder) {
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

    @Override
    public CategoryIdentifier<?> getCategoryIdentifier() {
        return CATEGORY;
    }

    @Override
    public DisplaySerializer<? extends InnateRecipeDisplay> getSerializer() {
        return SERIALIZER;
    }
}
