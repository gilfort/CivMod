package com.gilfort.civmod.inn;

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
 * The numbers of the inn (vault {@code Systems/NPC Population.md}, spec Inn and visitors) from
 * {@code data/<namespace>/civmod_inn/rules.json}; files of all packs are merged, later values override, missing values keep the
 * {@link #FALLBACK}. The Tavern radius that limits the guest beds is the one of the Tavern evening
 * ({@code EntertainmentRules}), not repeated here. Nothing here touches the world.
 *
 * @param baseChance        the share of a visitor each arrival check expects at the Pantry level 1 without any reputation (0.05)
 * @param reputationScale   the share that the best reputation adds (0.25)
 * @param bedsPerLevel      guest beds a Pantry holds per level (2)
 * @param minStayDays       days a visitor stays at least (2)
 * @param maxStayDays       days a visitor stays at most (4)
 * @param extendChance      the chance that asking a visitor to stay longer works (0.5)
 * @param extendDays        days a successful ask adds (2)
 * @param minLevelCap       the lowest cap of the levels a visitor rolls, whatever the best settler can do (3)
 * @param defaultPriceAmount the base amount of the price of a trade that has no entry in the price table (3); the item comes from the tag
 *                          {@code civmod:visitor_price_default}
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record InnRules(double baseChance, double reputationScale, int bedsPerLevel, int minStayDays, int maxStayDays, double extendChance,
        int extendDays, int minLevelCap, int defaultPriceAmount) {

    /** Reload listener id. */
    private static final Identifier ID = CivMod.id("inn_rules");
    /** Values used when no file is loaded. */
    public static final InnRules FALLBACK = new InnRules(0.05, 0.25, 2, 2, 4, 0.5, 2, 3, 3);

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_inn");
    private static InnRules current = FALLBACK;

    private record Part(Optional<Double> baseChance, Optional<Double> reputationScale, Optional<Integer> bedsPerLevel,
            Optional<Integer> minStayDays, Optional<Integer> maxStayDays, Optional<Double> extendChance, Optional<Integer> extendDays,
            Optional<Integer> minLevelCap, Optional<Integer> defaultPriceAmount) {

        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0.0, 10.0).optionalFieldOf("base_chance").forGetter(Part::baseChance),
                Codec.doubleRange(0.0, 10.0).optionalFieldOf("reputation_scale").forGetter(Part::reputationScale),
                Codec.intRange(1, 16).optionalFieldOf("beds_per_level").forGetter(Part::bedsPerLevel),
                Codec.intRange(1, 30).optionalFieldOf("min_stay_days").forGetter(Part::minStayDays),
                Codec.intRange(1, 30).optionalFieldOf("max_stay_days").forGetter(Part::maxStayDays),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("extend_chance").forGetter(Part::extendChance),
                Codec.intRange(0, 30).optionalFieldOf("extend_days").forGetter(Part::extendDays),
                Codec.intRange(1, 100).optionalFieldOf("min_level_cap").forGetter(Part::minLevelCap),
                Codec.intRange(1, 64).optionalFieldOf("default_price_amount").forGetter(Part::defaultPriceAmount)
        ).apply(instance, Part::new));
    }

    /**
     * @return the loaded rules
     */
    public static InnRules get() {
        return current;
    }

    /**
     * Replaces the loaded rules until the next reload (GameTests fix the chances at 0 % or 100 % this way).
     *
     * @param rules the rules to use
     */
    public static void use(InnRules rules) {
        current = rules;
    }

    /**
     * @param pantryLevel the level of the Pantry
     * @return the guest beds it holds
     */
    public int bedCapacity(int pantryLevel) {
        return bedsPerLevel * pantryLevel;
    }

    /**
     * What one arrival check expects at an inn: the whole part is certain, the fraction is rolled by the caller.
     *
     * @param reputation  the share of met needs over all settlers (0 to 1)
     * @param pantryLevel the level of the Pantry
     * @return the expected number of visitors
     */
    public double expectedArrivals(double reputation, int pantryLevel) {
        return (baseChance + reputationScale * reputation) * pantryLevel;
    }

    /**
     * @param roll a random number from 0 (included) to 1 (excluded)
     * @return whether a request to stay longer works
     */
    public boolean extensionWorks(double roll) {
        return roll < extendChance;
    }

    /**
     * Merges JSON files in order (later values override).
     *
     * @param files parsed JSON files
     * @return the merged rules
     */
    public static InnRules merge(List<JsonElement> files) {
        InnRules merged = FALLBACK;
        for (JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new InnRules(part.baseChance().orElse(merged.baseChance()), part.reputationScale().orElse(merged.reputationScale()),
                    part.bedsPerLevel().orElse(merged.bedsPerLevel()), part.minStayDays().orElse(merged.minStayDays()),
                    part.maxStayDays().orElse(merged.maxStayDays()), part.extendChance().orElse(merged.extendChance()),
                    part.extendDays().orElse(merged.extendDays()), part.minLevelCap().orElse(merged.minLevelCap()),
                    part.defaultPriceAmount().orElse(merged.defaultPriceAmount()));
        }
        if (merged.minStayDays() > merged.maxStayDays()) {
            throw new IllegalArgumentException("min_stay_days " + merged.minStayDays() + " is more than max_stay_days " + merged.maxStayDays());
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<InnRules> {

        @Override
        protected InnRules prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<JsonElement> files = new ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream()
                    .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals("rules"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        for (Resource resource : entry.getValue()) {
                            try (Reader reader = resource.openAsReader()) {
                                files.add(JsonParser.parseReader(reader));
                            } catch (Exception e) {
                                CivMod.LOGGER.error("Failed to read inn rules {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                            }
                        }
                    });
            try {
                return merge(files);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid inn rules, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(InnRules prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
        }
    }
}
