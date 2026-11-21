package com.gilfort.civmod.mastery;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * The numbers and decisions of Mastery (vault {@code Systems/Producing Jobs.md}) as plain functions over plain values,
 * with the values from {@code data/<namespace>/civmod_mastery/rules.json}; files of all packs are merged, later values
 * override, missing values keep the {@link #FALLBACK}. Nothing here touches the world.
 *
 * @param chancePerLevel        the chance of one roll per skill level (0.001: 0.1 % at level 1, 10 % at level 100)
 * @param maxChance             the highest chance of one roll (0.1)
 * @param maxHits               how many hits one craft counts at most (3)
 * @param savingShare           the share of a recipe's items one hit saves (0.13)
 * @param savingMinimum         the items one saving gives at least (1)
 * @param savingMinIngredients  the fewest ingredient items a recipe has to save material; a smaller recipe gives extra
 *                              output instead (3)
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record MasteryRules(double chancePerLevel, double maxChance, int maxHits, double savingShare, int savingMinimum,
        int savingMinIngredients) {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("mastery_rules");
    /** Values used when no file is loaded. */
    public static final MasteryRules FALLBACK = new MasteryRules(0.001, 0.1, 3, 0.13, 1, 3);

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_mastery");
    private static MasteryRules current = FALLBACK;

    private record Part(Optional<Double> chancePerLevel, Optional<Double> maxChance, Optional<Integer> maxHits, Optional<Double> savingShare,
            Optional<Integer> savingMinimum, Optional<Integer> savingMinIngredients) {

        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("chance_per_level").forGetter(Part::chancePerLevel),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("max_chance").forGetter(Part::maxChance),
                Codec.intRange(0, 16).optionalFieldOf("max_hits").forGetter(Part::maxHits),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("saving_share").forGetter(Part::savingShare),
                Codec.intRange(0, 64).optionalFieldOf("saving_minimum").forGetter(Part::savingMinimum),
                Codec.intRange(1, 64).optionalFieldOf("saving_min_ingredients").forGetter(Part::savingMinIngredients)
        ).apply(instance, Part::new));
    }

    /**
     * @return the loaded rules
     */
    public static MasteryRules get() {
        return current;
    }

    /**
     * @param skill the settler's skill level in the Job
     * @return the chance of one roll: the skill times the chance per level, at most the maximum
     */
    public double chance(int skill) {
        return Math.min(maxChance, Math.max(0, skill) * chancePerLevel);
    }

    /**
     * Rolls until a roll misses or the hits reach the maximum: after a hit the settler rolls again with the same chance.
     *
     * @param skill  the settler's skill level in the Job
     * @param random the random source of the rolls
     * @return the hits of one craft
     */
    public int hits(int skill, RandomSource random) {
        double chance = chance(skill);
        int hits = 0;
        while (hits < maxHits && random.nextDouble() < chance) {
            hits++;
        }
        return hits;
    }

    /**
     * @param ingredients the ingredient items of one craft (counts included)
     * @return whether a hit saves material: the recipe is big enough, a smaller one gives extra output
     */
    public boolean savesMaterial(int ingredients) {
        return ingredients >= savingMinIngredients;
    }

    /**
     * How many of the ingredient items the hits save: the share of the recipe times the hits, rounded down, at least the
     * minimum, and never all of them (one item is always used).
     *
     * @param ingredients the ingredient items of one craft (counts included)
     * @param hits        the hits of the craft
     * @return the items saved, 0 for a recipe too small to save material or without a hit
     */
    public int saved(int ingredients, int hits) {
        if (hits <= 0 || !savesMaterial(ingredients)) {
            return 0;
        }
        int share = (int) Math.floor(ingredients * savingShare * hits);
        return Math.min(ingredients - 1, Math.max(savingMinimum, share));
    }

    /**
     * Merges JSON files in order (later values override).
     *
     * @param files parsed JSON files
     * @return the merged rules
     * @throws IllegalStateException if a file holds a value out of range
     */
    public static MasteryRules merge(List<JsonElement> files) {
        MasteryRules merged = FALLBACK;
        for (JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new MasteryRules(part.chancePerLevel().orElse(merged.chancePerLevel()), part.maxChance().orElse(merged.maxChance()),
                    part.maxHits().orElse(merged.maxHits()), part.savingShare().orElse(merged.savingShare()),
                    part.savingMinimum().orElse(merged.savingMinimum()), part.savingMinIngredients().orElse(merged.savingMinIngredients()));
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<MasteryRules> {

        @Override
        protected MasteryRules prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<JsonElement> files = new ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream()
                    .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals("rules"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        for (Resource resource : entry.getValue()) {
                            try (Reader reader = resource.openAsReader()) {
                                files.add(JsonParser.parseReader(reader));
                            } catch (Exception e) {
                                CivMod.LOGGER.error("Failed to read mastery rules {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                            }
                        }
                    });
            try {
                return merge(files);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid mastery rules, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(MasteryRules prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
        }
    }
}
