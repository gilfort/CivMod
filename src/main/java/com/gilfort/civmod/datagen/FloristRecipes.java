package com.gilfort.civmod.datagen;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * The innate recipes of the Florist (vault {@code Jobs/Florist.md}): one recipe per flower of {@code #c:flowers/small}
 * and {@code #c:flowers/tall}, in {@code data/civmod/recipe/florist/<flower>.json}. A flower and a bone meal are used up
 * and one draw decides the outcome:
 * <ul>
 * <li>75 %: two of the same flower;</li>
 * <li>10 %: the flower and one flower of a different type, every other flower equally likely;</li>
 * <li>10 %: the flower comes back and nothing grows;</li>
 * <li>5 %: nothing comes back.</li>
 * </ul>
 * Drop weights are whole numbers, so each percentage is multiplied by the number of other flowers and the "different
 * type" share is split into one outcome per other flower.
 */
public final class FloristRecipes extends JsonCodecProvider<Recipe<?>> {

    private static final String DIRECTORY = "recipe";
    private static final String JOB = "florist";
    private static final TagKey<Item> SMALL_FLOWERS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "flowers/small"));
    private static final TagKey<Item> TALL_FLOWERS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "flowers/tall"));
    /** Percent of the draw for two of the same flower. */
    private static final int DOUBLE_PERCENT = 75;
    /** Percent of the draw for the flower and one other flower; shared by all other flowers. */
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
     * skipped. Only vanilla flowers are written: the data run sees the other mods of the dev environment, and a recipe of a mod a player did not install would break the datapack. Mod flowers are added by the mod or pack that ships them.
     *
     * @return every flower of the small and tall flower tags, once, ordered by item id
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
     * @param flower  the flower of the recipe
     * @param flowers every flower that takes part, the recipe's own included
     * @return the four outcomes of the recipe; with {@code n} other flowers the weights add up to {@code 100 * n}
     */
    public static Drops outcomes(Item flower, List<Item> flowers) {
        List<Item> others = flowers.stream().filter(other -> other != flower).toList();
        if (others.isEmpty()) {
            throw new IllegalArgumentException("A flower needs at least one other flower to grow: " + flower);
        }
        int scale = others.size();
        List<DropOutcome> outcomes = new ArrayList<>();
        outcomes.add(outcome(DOUBLE_PERCENT * scale, new ItemStackTemplate(flower, 2)));
        for (Item other : others) {
            outcomes.add(outcome(DIFFERENT_PERCENT, new ItemStackTemplate(flower), new ItemStackTemplate(other)));
        }
        outcomes.add(outcome(COMES_BACK_PERCENT * scale, new ItemStackTemplate(flower)));
        outcomes.add(outcome(LOST_PERCENT * scale));
        return Drops.of(outcomes);
    }

    private static DropOutcome outcome(int weight, ItemStackTemplate... results) {
        return new DropOutcome(weight, DropOutcome.DEFAULT_MIN_LEVEL, List.of(results));
    }

    @Override
    protected void gather() {
        List<Item> flowers = flowers();
        for (Item flower : flowers) {
            Identifier id = BuiltInRegistries.ITEM.getKey(flower);
            JobCraftingRecipe recipe = new JobCraftingRecipe(List.of(CivMod.id(JOB)),
                    List.of(JobCraftingRecipe.Input.of(Ingredient.of(flower)), JobCraftingRecipe.Input.of(Ingredient.of(Items.BONE_MEAL))), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), JobCraftingRecipe.DEFAULT_WEIGHT, Optional.of(outcomes(flower, flowers)),
                    Optional.empty());
            unconditional(CivMod.id(JOB + "/" + id.getNamespace() + "_" + id.getPath()), recipe);
        }
    }
}
