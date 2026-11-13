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
 * @param tavernPoints   the base points a Tavern evening gives each guest
 * @param tavernFactor   the factor on the base points when the crowd and the jukebox both hold (1.5)
 * @param crowdSize      settlers present for condition A, the Tavern Host included
 * @param tavernRadius   blocks around the Pantry that count as present and that seats may lie in
 * @param jukeboxRadius  blocks around the Pantry in which a jukebox with a disc counts
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record EntertainmentRules(double decay, double tier2Threshold, double tier3Threshold, double speedPerPoint,
        double tavernPoints, double tavernFactor, int crowdSize, int tavernRadius, int jukeboxRadius) {

    /** The highest value. */
    public static final double MAX = 10.0;
    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("entertainment_rules");
    /** Values used when no file is loaded. */
    public static final EntertainmentRules FALLBACK = new EntertainmentRules(1.0, 5.0, 8.0, 0.02, 3.0, 1.5, 4, 12, 8);

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_entertainment");
    private static EntertainmentRules current = FALLBACK;

    private record Part(Optional<Double> decay, Optional<Double> tier2Threshold, Optional<Double> tier3Threshold, Optional<Double> speedPerPoint,
            Optional<Double> tavernPoints, Optional<Double> tavernFactor, Optional<Integer> crowdSize, Optional<Integer> tavernRadius,
            Optional<Integer> jukeboxRadius) {

        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0.0, MAX).optionalFieldOf("decay").forGetter(Part::decay),
                Codec.doubleRange(0.0, MAX).optionalFieldOf("tier_2_threshold").forGetter(Part::tier2Threshold),
                Codec.doubleRange(0.0, MAX).optionalFieldOf("tier_3_threshold").forGetter(Part::tier3Threshold),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("speed_per_point").forGetter(Part::speedPerPoint),
                Codec.doubleRange(0.0, MAX).optionalFieldOf("tavern_points").forGetter(Part::tavernPoints),
                Codec.doubleRange(1.0, MAX).optionalFieldOf("tavern_factor").forGetter(Part::tavernFactor),
                Codec.intRange(1, 64).optionalFieldOf("crowd_size").forGetter(Part::crowdSize),
                Codec.intRange(1, 64).optionalFieldOf("tavern_radius").forGetter(Part::tavernRadius),
                Codec.intRange(1, 64).optionalFieldOf("jukebox_radius").forGetter(Part::jukeboxRadius)
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
     * @param value        the value
     * @param bonusFactor  the factor of the other speed bonuses, 1 for none: the Priest blessing (1.2) plus the share of a
     *                     {@code WorkSpeed} boost, so 1.3 with both
     * @return the factor to multiply the work speed with
     */
    public double speedFactor(double value, double bonusFactor) {
        return 1.0 + (bonusFactor - 1.0) + speedBonus(value);
    }

    /**
     * The points of one Tavern evening: the base points if the crowd (condition A) or the jukebox (condition B) holds,
     * the base points times the factor if both hold. The factor applies to whatever the base value is.
     *
     * @param present settlers present at the Pantry, the Tavern Host included
     * @param jukebox whether a jukebox that holds a disc stands near the Pantry
     * @return the points each guest gets, 0 if neither condition holds
     */
    public double tavernPoints(int present, boolean jukebox) {
        boolean crowd = present >= crowdSize;
        if (crowd && jukebox) {
            return tavernPoints * tavernFactor;
        }
        return crowd || jukebox ? tavernPoints : 0.0;
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
                    part.tier3Threshold().orElse(merged.tier3Threshold()), part.speedPerPoint().orElse(merged.speedPerPoint()),
                    part.tavernPoints().orElse(merged.tavernPoints()), part.tavernFactor().orElse(merged.tavernFactor()),
                    part.crowdSize().orElse(merged.crowdSize()), part.tavernRadius().orElse(merged.tavernRadius()),
                    part.jukeboxRadius().orElse(merged.jukeboxRadius()));
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
