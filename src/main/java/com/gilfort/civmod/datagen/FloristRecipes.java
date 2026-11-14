package com.gilfort.civmod.datagen;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.DropOutcome;
import com.gilfort.civmod.api.Drops;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.common.data.JsonCodecProvider;

/**
 * The innate recipes of the Florist (vault {@code Jobs/Florist.md}): one recipe per vanilla flower of
 * {@code #c:flowers/small} and {@code #c:flowers/tall}, in {@code data/civmod/recipe/florist/<flower>.json}. A flower and
 * a bone meal are used up and one draw decides the outcome:
 * <ul>
 * <li>75 %: two of the same flower;</li>
 * <li>10 %: the flower and one flower of a different type: a random item of {@code #civmod:florist_flowers} (both flower
 * tags) other than the flower, picked when the outcome is drawn;</li>
 * <li>10 %: the flower comes back and nothing grows;</li>
 * <li>5 %: nothing comes back.</li>
 * </ul>
 */
public final class FloristRecipes extends JsonCodecProvider<Recipe<?>> {

    private static final String DIRECTORY = "recipe";
    private static final String JOB = "florist";
    private static final TagKey<Item> SMALL_FLOWERS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "flowers/small"));
    private static final TagKey<Item> TALL_FLOWERS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "flowers/tall"));
    /** Both flower tags together ({@code data/civmod/tags/item/florist_flowers.json}): the pool of the "different type" outcome. */
    private static final TagKey<Item> ANY_FLOWER = TagKey.create(Registries.ITEM, CivMod.id("florist_flowers"));
    /** Percent of the draw for two of the same flower. */
    private static final int DOUBLE_PERCENT = 75;
    /** Percent of the draw for the flower and one other flower. */
    private static final int DIFFERENT_PERCENT = 10;
    /** Percent of the draw for the flower coming back alone. */
    private static final int COMES_BACK_PERCENT = 10;
    /** Percent of the draw for nothing coming back. */
    private static final int LOST_PERCENT = 5;
    /** Writes a recipe with its {@code type} field, like the files in a datapack. */
    private static final Codec<Recipe<?>> CODEC = BuiltInRegistries.RECIPE_SERIALIZER.byNameCodec().dispatch(Recipe::getSerializer,
            RecipeSerializer::codec);

    FloristRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, PackOutput.Target.DATA_PACK, DIRECTORY, CODEC, lookup, CivMod.MOD_ID);
    }

    /** A name of its own: two providers of the same directory and mod would otherwise clash. */
    @Override
    public String getName() {
        return "Florist recipes for " + CivMod.MOD_ID;
    }

    /**
     * Reads the flowers from the tag files on the class path, because the item tags are not bound while data is generated.
     * Every pack with a file for a tag adds to it, nested {@code #tag} entries are followed, and ids that are no item are
     * skipped. Only vanilla flowers get a recipe of their own: the data run sees the other mods of the dev environment,
     * and a recipe of a mod a player did not install would break the datapack. Flowers of other mods still come up as
     * the different flower of these recipes, and the mod or pack that ships them can add a recipe for them.
     *
     * @return every vanilla flower of the small and tall flower tags, once, ordered by item id
     */
    static List<Item> flowers() {
        Set<Identifier> ids = new TreeSet<>();
        for (TagKey<Item> tag : List.of(SMALL_FLOWERS, TALL_FLOWERS)) {
            collect(tag.location(), ids, new HashSet<>());
        }
        return ids.stream().filter(id -> id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)).flatMap(id -> BuiltInRegistries.ITEM.getOptional(id).stream()).toList();
    }

    private static void collect(Identifier tag, Set<Identifier> ids, Set<Identifier> visited) {
        if (!visited.add(tag)) {
            return;
        }
        String path = "data/" + tag.getNamespace() + "/tags/item/" + tag.getPath() + ".json";
        try {
            for (URL url : Collections.list(FloristRecipes.class.getClassLoader().getResources(path))) {
                try (Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
                    for (JsonElement entry : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) {
                        String id = entry.isJsonObject() ? entry.getAsJsonObject().get("id").getAsString() : entry.getAsString();
                        if (id.startsWith("#")) {
                            collect(Identifier.parse(id.substring(1)), ids, visited);
                        } else {
                            ids.add(Identifier.parse(id));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the item tag " + tag, e);
        }
    }

    /**
     * @param flower the flower of the recipe
     * @return the four outcomes of the recipe, weighing 75, 10, 10 and 5; the "different type" outcome draws its second
     *         flower from {@code #civmod:florist_flowers} when it is drawn, so flowers of other mods take part
     */
    public static Drops outcomes(Item flower) {
        return Drops.of(List.of(
                outcome(DOUBLE_PERCENT, Optional.empty(), new ItemStackTemplate(flower, 2)),
                outcome(DIFFERENT_PERCENT, Optional.of(new DropOutcome.TagResult(ANY_FLOWER, 1)), new ItemStackTemplate(flower)),
                outcome(COMES_BACK_PERCENT, Optional.empty(), new ItemStackTemplate(flower)),
                outcome(LOST_PERCENT, Optional.empty())));
    }

    private static DropOutcome outcome(int weight, Optional<DropOutcome.TagResult> tagResult, ItemStackTemplate... results) {
        return new DropOutcome(weight, DropOutcome.DEFAULT_MIN_LEVEL, List.of(results), tagResult);
    }

    @Override
    protected void gather() {
        List<Item> flowers = flowers();
        for (Item flower : flowers) {
            Identifier id = BuiltInRegistries.ITEM.getKey(flower);
            JobCraftingRecipe recipe = new JobCraftingRecipe(List.of(CivMod.id(JOB)),
                    List.of(JobCraftingRecipe.Input.of(Ingredient.of(flower)), JobCraftingRecipe.Input.of(Ingredient.of(Items.BONE_MEAL))), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), JobCraftingRecipe.DEFAULT_WEIGHT, Optional.of(outcomes(flower)),
                    Optional.empty());
            unconditional(CivMod.id(JOB + "/" + id.getNamespace() + "_" + id.getPath()), recipe);
        }
    }
}
