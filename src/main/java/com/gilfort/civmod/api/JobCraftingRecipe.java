package com.gilfort.civmod.api;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.registry.ModRecipes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * An innate recipe (vault {@code Systems/Producing Jobs.md}): a Job-only recipe of the recipe type
 * {@code civmod:job_crafting} that players cannot craft anywhere (no block uses the type). Every Producing Job named in
 * {@link #jobs()} knows it: it sits locked in the first slots of the Job's pattern library (ordered by recipe id), can
 * only be checked or unchecked and counts towards the checkmark limit. Profiles do not list the type in
 * {@link ProducingProfile#recipeTypes()}; the domain and filters of the profile do not apply to it.
 * <p>
 * Packs and addons add innate recipes as JSON in {@code data/<namespace>/recipe/}:
 *
 * <pre>{@code
 * {
 *   "type": "civmod:job_crafting",
 *   "jobs": ["civmod:fletcher"],
 *   "ingredients": ["#minecraft:wool"],
 *   "result": { "id": "minecraft:string", "count": 2 },
 *   "tool": "#c:tools/shear",
 *   "station": "#civmod:stations/fletcher"
 * }
 * }</pre>
 *
 * <ul>
 * <li>{@code jobs}: the Job ids; Jobs that are not Producing (or unknown) are ignored with a log warning.</li>
 * <li>{@code ingredients}: shapeless, 1–9 ingredients (item ids, {@code #tags} or lists).</li>
 * <li>{@code result}: the made item stack.</li>
 * <li>{@code tool} (optional): an item id or {@code #tag}; the settler fetches it like a Resource Collection tool (belt,
 * else from its collect inventory) and wears it by 1 durability per execution (Unbreaking works). Without the tool
 * only this recipe is skipped and marked in the Patterns tab ("Needs Shears").</li>
 * <li>{@code station} (optional): a block {@code #tag}; the settler works the recipe at the nearest such station (as a
 * step of another recipe type). Without it the station of the Job's main recipe type counts. A missing station skips
 * the recipe and marks it ("Needs a station nearby: Water Cauldron").</li>
 * </ul>
 *
 * @param jobs        the Jobs that know the recipe
 * @param ingredients the inputs of one execution (shapeless)
 * @param result      what one execution makes
 * @param tool        the tool the settler needs, if any
 * @param station     the station tag the recipe is worked at, if not the Job's main station
 */
@ApiStatus.Experimental
public record JobCraftingRecipe(List<Identifier> jobs, List<Ingredient> ingredients, ItemStackTemplate result, Optional<Ingredient> tool,
        Optional<TagKey<Block>> station) implements Recipe<CraftingInput> {

    /** The most ingredients of one execution: the 3×3 pattern grid. */
    public static final int MAX_INGREDIENTS = 9;

    /** Datapack codec. */
    public static final MapCodec<JobCraftingRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.listOf(1, Integer.MAX_VALUE).fieldOf("jobs").forGetter(JobCraftingRecipe::jobs),
            Codec.lazyInitialized(() -> Ingredient.CODEC.listOf(1, MAX_INGREDIENTS)).fieldOf("ingredients").forGetter(JobCraftingRecipe::ingredients),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(JobCraftingRecipe::result),
            Ingredient.CODEC.optionalFieldOf("tool").forGetter(JobCraftingRecipe::tool),
            TagKey.hashedCodec(Registries.BLOCK).optionalFieldOf("station").forGetter(JobCraftingRecipe::station)
    ).apply(instance, JobCraftingRecipe::new));

    /** Network codec (recipe viewers). */
    public static final StreamCodec<RegistryFriendlyByteBuf, JobCraftingRecipe> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), JobCraftingRecipe::jobs,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), JobCraftingRecipe::ingredients,
            ItemStackTemplate.STREAM_CODEC, JobCraftingRecipe::result,
            ByteBufCodecs.optional(Ingredient.CONTENTS_STREAM_CODEC), JobCraftingRecipe::tool,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC.map(id -> TagKey.create(Registries.BLOCK, id), TagKey::location)),
            JobCraftingRecipe::station,
            JobCraftingRecipe::new);

    /** The serializer of {@code civmod:job_crafting}. */
    public static final RecipeSerializer<JobCraftingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /**
     * @param jobs        the Jobs that know the recipe
     * @param ingredients the inputs of one execution
     * @param result      what one execution makes
     * @param tool        the tool the settler needs, if any
     * @param station     the station tag, if not the Job's main station
     */
    public JobCraftingRecipe {
        jobs = List.copyOf(jobs);
        ingredients = List.copyOf(ingredients);
    }

    /** Players craft it nowhere. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return result.create();
    }

    @Override
    public boolean showNotification() {
        return false;
    }

    @Override
    public String group() {
        return "";
    }

    @Override
    public RecipeSerializer<JobCraftingRecipe> getSerializer() {
        return ModRecipes.JOB_CRAFTING_SERIALIZER.get();
    }

    @Override
    public RecipeType<JobCraftingRecipe> getType() {
        return ModRecipes.JOB_CRAFTING.get();
    }

    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.create(ingredients);
    }

    /** Shown like a shapeless recipe without a crafting station (the station depends on the Job). */
    @Override
    public List<RecipeDisplay> display() {
        return List.of(new ShapelessCraftingRecipeDisplay(ingredients.stream().map(Ingredient::display).toList(),
                new SlotDisplay.ItemStackSlotDisplay(result), SlotDisplay.Empty.INSTANCE));
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return RecipeBookCategories.CRAFTING_MISC;
    }
}
