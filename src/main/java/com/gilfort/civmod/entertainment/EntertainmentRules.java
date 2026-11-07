package com.gilfort.civmod.entertainment;

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
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * The rules of the Entertainment value (vault {@code Systems/Entertainment.md}) as plain functions over plain values, with
 * their numbers from {@code data/<namespace>/civmod_entertainment/rules.json}; files of all packs are merged, later values
 * override, missing values keep the {@link #FALLBACK}. Nothing here touches the world.
 *
 * @param decay          points taken from the value per in-game day
 * @param tier2Threshold tier 2 needs more than this much Entertainment
 * @param tier3Threshold tier 3 needs more than this much Entertainment
 * @param speedPerPoint  work speed gained per point (0.02: 2 %)
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record EntertainmentRules(double decay, double tier2Threshold, double tier3Threshold, double speedPerPoint) {

    /** The highest value. */
    public static final double MAX = 10.0;
    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("entertainment_rules");
    /** Values used when no file is loaded. */
    public static final EntertainmentRules FALLBACK = new EntertainmentRules(1.0, 5.0, 8.0, 0.02);

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_entertainment");
    private static EntertainmentRules current = FALLBACK;

    private record Part(Optional<Double> decay, Optional<Double> tier2Threshold, Optional<Double> tier3Threshold, Optional<Double> speedPerPoint) {

        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0.0, MAX).optionalFieldOf("decay").forGetter(Part::decay),
                Codec.doubleRange(0.0, MAX).optionalFieldOf("tier_2_threshold").forGetter(Part::tier2Threshold),
                Codec.doubleRange(0.0, MAX).optionalFieldOf("tier_3_threshold").forGetter(Part::tier3Threshold),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("speed_per_point").forGetter(Part::speedPerPoint)
        ).apply(instance, Part::new));
    }

    /**
     * @return the loaded rules
     */
    public static EntertainmentRules get() {
        return current;
    }

    /**
     * @param value a value
     * @return the value kept in 0 to {@link #MAX}
     */
    public static double clamp(double value) {
        return Math.clamp(value, 0.0, MAX);
    }

    /**
     * @param value the value
     * @param days  the days that passed
     * @return the value after the decay of those days, never below 0
     */
    public double decayed(double value, long days) {
        return clamp(value - decay * days);
    }

    /**
     * @param value the value
     * @param tier  a need tier
     * @return whether the value is enough for that tier; only the tiers 2 and 3 ask for Entertainment
     */
    public boolean met(double value, int tier) {
        return switch (tier) {
            case 2 -> value > tier2Threshold;
            case 3 -> value > tier3Threshold;
            default -> true;
        };
    }

    /**
     * @param value the value
     * @return the work speed the value adds (0.2 at 10 points with the defaults)
     */
    public double speedBonus(double value) {
        return clamp(value) * speedPerPoint;
    }

    /**
     * Adds the Entertainment bonus to the share of the other speed bonuses; they add up, they do not multiply.
     *
     * @param value           the value
     * @param blessingFactor  the factor of the other speed bonus, 1 for none (the Priest blessing: 1.2)
     * @return the factor to multiply the work speed with
     */
    public double speedFactor(double value, double blessingFactor) {
        return 1.0 + (blessingFactor - 1.0) + speedBonus(value);
    }

    /**
     * Merges JSON files in order (later values override).
     *
     * @param files parsed JSON files
     * @return the merged rules
     */
    public static EntertainmentRules merge(List<JsonElement> files) {
        EntertainmentRules merged = FALLBACK;
        for (JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new EntertainmentRules(part.decay().orElse(merged.decay()), part.tier2Threshold().orElse(merged.tier2Threshold()),
                    part.tier3Threshold().orElse(merged.tier3Threshold()), part.speedPerPoint().orElse(merged.speedPerPoint()));
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<EntertainmentRules> {

        @Override
        protected EntertainmentRules prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<JsonElement> files = new ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream()
                    .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals("rules"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        for (Resource resource : entry.getValue()) {
                            try (Reader reader = resource.openAsReader()) {
                                files.add(JsonParser.parseReader(reader));
                            } catch (Exception e) {
                                CivMod.LOGGER.error("Failed to read Entertainment rules {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                            }
                        }
                    });
            try {
                return merge(files);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid Entertainment rules, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(EntertainmentRules prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
        }
    }
}
