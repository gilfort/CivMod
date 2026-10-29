package com.gilfort.civmod.api;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.registry.ModRecipes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
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
import net.minecraft.world.level.block.state.BlockState;

/**
 * An innate recipe (vault {@code Systems/Producing Jobs.md}, {@code Systems/Resource Collection Jobs.md}): a Job-only
 * recipe of the recipe type {@code civmod:job_crafting} that players cannot craft anywhere (no block uses the type).
 * <p>
 * Every <b>Producing Job</b> named in {@link #jobs()} knows it: it sits locked in the first slots of the Job's pattern
 * library (ordered by recipe id), can only be checked or unchecked and counts towards the checkmark limit. Profiles do
 * not list the type in {@link ProducingProfile#recipeTypes()}; the domain and filters of the profile do not apply to it.
 * <p>
 * Every <b>Resource Collection Job</b> named in {@link #jobs()} turns a consumed input into a product while it works
 * (milk from cows into buckets): while the Job's scan found what {@link #requires()} names, the recipe is an own entry
 * of the JobBlock's Found tab and joins the Job's roll like a found type, with its {@link #weight()} times the count of its
 * {@code requires} in the scan against the weights of the found types ({@link ResourceCollectionProfile#weight}). When it is rolled, the settler uses up one of each
 * ingredient from its belt (it fetches them from its collect inventory like a tool) and collects the result; the Job's
 * tool is not worn and no double drop applies. Without the ingredients the entry leaves the roll and is greyed out
 * ("Needs: Bucket"); the Job goes on with its other drops. The Found tab exclusion and the Home Clerk's Max of the result
 * apply to the entry.
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
 * A Resource Collection recipe (the Animal Farmer milks cows):
 *
 * <pre>{@code
 * {
 *   "type": "civmod:job_crafting",
 *   "jobs": ["civmod:animal_farmer"],
 *   "ingredients": ["minecraft:bucket"],
 *   "result": { "id": "minecraft:milk_bucket" },
 *   "requires": { "entity": "minecraft:cow" },
 *   "weight": 1
 * }
 * }</pre>
 *
 * <ul>
 * <li>{@code jobs}: the Job ids; Jobs that are neither Producing nor Resource Collection (or unknown) are ignored with a
 * log warning.</li>
 * <li>{@code ingredients}: shapeless, 1–9 ingredients (item ids, {@code #tags} or lists).</li>
 * <li>{@code result}: the made item stack.</li>
 * <li>{@code tool} (optional, Producing only): an item id or {@code #tag}; the settler fetches it like a Resource
 * Collection tool (belt, else from its collect inventory) and wears it by 1 durability per execution (Unbreaking works).
 * Without the tool only this recipe is skipped and marked in the Patterns tab ("Needs Shears").</li>
 * <li>{@code station} (optional, Producing only): a block {@code #tag}; the settler works the recipe at the nearest such
 * station (as a step of another recipe type). Without it the station of the Job's main recipe type counts. A missing
 * station skips the recipe and marks it ("Needs a station nearby: Water Cauldron").</li>
 * <li>{@code requires} (optional, Resource Collection only): what the Job's scan must have found for the recipe to be
 * offered: {@code {"entity": <entity type id, #tag or list>}} (an animal the Animal Farmer counted), or
 * {@code {"block": <block id, #tag or list>, "state": {<property>: <value>}}} (a found block; {@code state} is optional
 * and takes exact values or {@code {"min": .., "max": ..}} ranges), e.g.
 * {@code {"block": ["minecraft:beehive", "minecraft:bee_nest"], "state": {"honey_level": "5"}}}. Each Job's profile
 * decides how many of it its scan has found ({@link ResourceCollectionProfile#count}). Without {@code requires} the
 * recipe is offered whenever the Job has a scan.</li>
 * <li>{@code weight} (optional, Resource Collection only, default 1, at least 1): the recipe's weight in the roll against
 * the weights of the found types (the Animal Farmer weighs an animal type by its count, other Jobs each found type by
 * 1), times the count of its {@code requires} in the scan (20 cows: milk weighs 20 × {@code weight}); without
 * {@code requires} the weight alone.</li>
 * </ul>
 *
 * @param jobs        the Jobs that know the recipe
 * @param ingredients the inputs of one execution (shapeless)
 * @param result      what one execution makes
 * @param tool        the tool the settler needs, if any
 * @param station     the station tag the recipe is worked at, if not the Job's main station
 * @param requires    what a Resource Collection Job must have found for the recipe, if anything
 * @param weight      the recipe's weight in a Resource Collection Job's roll (at least 1)
 */
@ApiStatus.Experimental
public record JobCraftingRecipe(List<Identifier> jobs, List<Ingredient> ingredients, ItemStackTemplate result, Optional<Ingredient> tool,
        Optional<TagKey<Block>> station, Optional<Requirement> requires, int weight) implements Recipe<CraftingInput> {

    /** The most ingredients of one execution: the 3×3 pattern grid. */
    public static final int MAX_INGREDIENTS = 9;
    /** The weight of a recipe without {@code weight}. */
    public static final int DEFAULT_WEIGHT = 1;

    /**
     * What a Resource Collection Job's scan must have found for an innate recipe: an entity type or a block (with an
     * optional block state). Exactly one of {@link #entity()} and {@link #block()} is set; {@link #state()} only with a
     * block.
     *
     * @param entity the entity types, one of which must have been found
     * @param block  the blocks, one of which must have been found
     * @param state  the block state the found block must have
     */
    @ApiStatus.Experimental
    public record Requirement(Optional<HolderSet<EntityType<?>>> entity, Optional<HolderSet<Block>> block, Optional<StatePropertiesPredicate> state) {

        /** Datapack codec. */
        public static final Codec<Requirement> CODEC = RecordCodecBuilder.<Requirement>create(instance -> instance.group(
                RegistryCodecs.holderSet(Registries.ENTITY_TYPE).optionalFieldOf("entity").forGetter(Requirement::entity),
                RegistryCodecs.holderSet(Registries.BLOCK).optionalFieldOf("block").forGetter(Requirement::block),
                StatePropertiesPredicate.CODEC.optionalFieldOf("state").forGetter(Requirement::state)
        ).apply(instance, Requirement::new)).validate(Requirement::validate);

        /** Network codec (recipe viewers). */
        public static final StreamCodec<RegistryFriendlyByteBuf, Requirement> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(ByteBufCodecs.holderSet(Registries.ENTITY_TYPE)), Requirement::entity,
                ByteBufCodecs.optional(ByteBufCodecs.holderSet(Registries.BLOCK)), Requirement::block,
                ByteBufCodecs.optional(StatePropertiesPredicate.STREAM_CODEC), Requirement::state,
                Requirement::new);

        private static DataResult<Requirement> validate(Requirement requirement) {
            if (requirement.entity.isPresent() == requirement.block.isPresent()) {
                return DataResult.error(() -> "requires needs exactly one of \"entity\" and \"block\"");
            }
            if (requirement.state.isPresent() && requirement.block.isEmpty()) {
                return DataResult.error(() -> "requires: \"state\" only goes with \"block\"");
            }
            return DataResult.success(requirement);
        }

        /**
         * @param type an entity type
         * @return whether it is a required entity type
         */
        public boolean matches(EntityType<?> type) {
            return entity.isPresent() && entity.get().contains(type.builtInRegistryHolder());
        }

        /**
         * @param blockState a block state
         * @return whether it is a required block in the required state
         */
        public boolean matches(BlockState blockState) {
            return block.isPresent() && blockState.is(block.get()) && state.map(predicate -> predicate.matches(blockState)).orElse(true);
        }
    }

    /** Datapack codec. */
    public static final MapCodec<JobCraftingRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.listOf(1, Integer.MAX_VALUE).fieldOf("jobs").forGetter(JobCraftingRecipe::jobs),
            Codec.lazyInitialized(() -> Ingredient.CODEC.listOf(1, MAX_INGREDIENTS)).fieldOf("ingredients").forGetter(JobCraftingRecipe::ingredients),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(JobCraftingRecipe::result),
            Ingredient.CODEC.optionalFieldOf("tool").forGetter(JobCraftingRecipe::tool),
            TagKey.hashedCodec(Registries.BLOCK).optionalFieldOf("station").forGetter(JobCraftingRecipe::station),
            Requirement.CODEC.optionalFieldOf("requires").forGetter(JobCraftingRecipe::requires),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("weight", DEFAULT_WEIGHT).forGetter(JobCraftingRecipe::weight)
    ).apply(instance, JobCraftingRecipe::new));

    /** Network codec (recipe viewers). */
    public static final StreamCodec<RegistryFriendlyByteBuf, JobCraftingRecipe> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), JobCraftingRecipe::jobs,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), JobCraftingRecipe::ingredients,
            ItemStackTemplate.STREAM_CODEC, JobCraftingRecipe::result,
            ByteBufCodecs.optional(Ingredient.CONTENTS_STREAM_CODEC), JobCraftingRecipe::tool,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC.map(id -> TagKey.create(Registries.BLOCK, id), TagKey::location)),
            JobCraftingRecipe::station,
            ByteBufCodecs.optional(Requirement.STREAM_CODEC), JobCraftingRecipe::requires,
            ByteBufCodecs.VAR_INT, JobCraftingRecipe::weight,
            JobCraftingRecipe::new);

    /** The serializer of {@code civmod:job_crafting}. */
    public static final RecipeSerializer<JobCraftingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /**
     * @param jobs        the Jobs that know the recipe
     * @param ingredients the inputs of one execution
     * @param result      what one execution makes
     * @param tool        the tool the settler needs, if any
     * @param station     the station tag, if not the Job's main station
     * @param requires    what a Resource Collection Job must have found, if anything
     * @param weight      the weight in a Resource Collection Job's roll (at least 1)
     */
    public JobCraftingRecipe {
        jobs = List.copyOf(jobs);
        ingredients = List.copyOf(ingredients);
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be at least 1: " + weight);
        }
    }

    /**
     * A recipe without {@code requires} and with the default weight (as a Producing Job uses it).
     *
     * @param jobs        the Jobs that know the recipe
     * @param ingredients the inputs of one execution
     * @param result      what one execution makes
     * @param tool        the tool the settler needs, if any
     * @param station     the station tag, if not the Job's main station
     */
    public JobCraftingRecipe(List<Identifier> jobs, List<Ingredient> ingredients, ItemStackTemplate result, Optional<Ingredient> tool,
            Optional<TagKey<Block>> station) {
        this(jobs, ingredients, result, tool, station, Optional.empty(), DEFAULT_WEIGHT);
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
