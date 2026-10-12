package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * Miner weights (vault {@code Jobs/Miner.md}): stone share per skill level and relative weights of stone and ore
 * types, from {@code data/<namespace>/civmod_miner/weights.json}. Files of all packs are merged; later entries
 * override earlier ones.
 *
 * @param stoneShareLevel1 stone share in percent at skill level 1
 * @param stoneSharePerLevel change of the stone share per level (percent points)
 * @param stones           weights of stone types
 * @param ores             weights of ore types
 * @param unknownStone     weight of stone types not listed
 * @param unknownOre       weight of ore types not listed
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record MinerWeights(double stoneShareLevel1, double stoneSharePerLevel, Map<Identifier, Double> stones, Map<Identifier, Double> ores,
        double unknownStone, double unknownOre) {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("miner_weights");
    /** Values used when no file is loaded. */
    public static final MinerWeights FALLBACK = new MinerWeights(99.7, -0.34, Map.of(), Map.of(), 10.0, 10.0);

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_miner");
    private static MinerWeights current = FALLBACK;

    /** One file; every field is optional and overrides what earlier files set. */
    private record Part(Optional<StoneShare> stoneShare, Map<Identifier, Double> stones, Map<Identifier, Double> ores,
            Optional<Double> unknownStone, Optional<Double> unknownOre) {
        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoneShare.CODEC.optionalFieldOf("stone_share").forGetter(Part::stoneShare),
                Codec.unboundedMap(Identifier.CODEC, Codec.DOUBLE).optionalFieldOf("stones", Map.of()).forGetter(Part::stones),
                Codec.unboundedMap(Identifier.CODEC, Codec.DOUBLE).optionalFieldOf("ores", Map.of()).forGetter(Part::ores),
                Codec.DOUBLE.optionalFieldOf("unknown_stone").forGetter(Part::unknownStone),
                Codec.DOUBLE.optionalFieldOf("unknown_ore").forGetter(Part::unknownOre)
        ).apply(instance, Part::new));
    }

    private record StoneShare(double level1, double perLevel) {
        static final Codec<StoneShare> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("level_1").forGetter(StoneShare::level1),
                Codec.DOUBLE.fieldOf("per_level").forGetter(StoneShare::perLevel)
        ).apply(instance, StoneShare::new));
    }

    /**
     * @return the loaded weights
     */
    public static MinerWeights get() {
        return current;
    }

    /**
     * @param skillLevel the skill level
     * @return the share of the stone group (0–1)
     */
    public double stoneShare(int skillLevel) {
        return Math.clamp((stoneShareLevel1 + stoneSharePerLevel * (skillLevel - 1)) / 100.0, 0.0, 1.0);
    }

    /**
     * @param block a block type
     * @param stone whether it belongs to the stone group
     * @return its weight
     */
    public double weight(Identifier block, boolean stone) {
        return stone ? stones.getOrDefault(block, unknownStone) : ores.getOrDefault(block, unknownOre);
    }

    /**
     * Merges JSON files in order (later ones override).
     *
     * @param files parsed JSON files
     * @return the merged weights
     */
    public static MinerWeights merge(List<com.google.gson.JsonElement> files) {
        double level1 = FALLBACK.stoneShareLevel1();
        double perLevel = FALLBACK.stoneSharePerLevel();
        Map<Identifier, Double> stones = new HashMap<>();
        Map<Identifier, Double> ores = new HashMap<>();
        double unknownStone = FALLBACK.unknownStone();
        double unknownOre = FALLBACK.unknownOre();
        for (com.google.gson.JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            if (part.stoneShare().isPresent()) {
                level1 = part.stoneShare().get().level1();
                perLevel = part.stoneShare().get().perLevel();
            }
            stones.putAll(part.stones());
            ores.putAll(part.ores());
            unknownStone = part.unknownStone().orElse(unknownStone);
            unknownOre = part.unknownOre().orElse(unknownOre);
        }
        return new MinerWeights(level1, perLevel, Map.copyOf(stones), Map.copyOf(ores), unknownStone, unknownOre);
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<MinerWeights> {

        @Override
        protected MinerWeights prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<com.google.gson.JsonElement> files = new java.util.ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream()
                    .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals("weights"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        for (Resource resource : entry.getValue()) {
                            try (Reader reader = resource.openAsReader()) {
                                files.add(JsonParser.parseReader(reader));
                            } catch (Exception e) {
                                CivMod.LOGGER.error("Failed to read miner weights {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                            }
                        }
                    });
            try {
                return merge(files);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid miner weights, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(MinerWeights prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
            CivMod.LOGGER.info("Loaded miner weights for {} stone and {} ore type(s)", prepared.stones().size(), prepared.ores().size());
        }
    }
}
