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
import net.minecraft.util.RandomSource;
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
 * @param drinks         the numbers of the drinks guests take at the counter
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record EntertainmentRules(double decay, double tier2Threshold, double tier3Threshold, double speedPerPoint,
        double tavernPoints, double tavernFactor, int crowdSize, int tavernRadius, int jukeboxRadius, DrinkRules drinks) {

    /** The highest value. */
    public static final double MAX = 10.0;
    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("entertainment_rules");
    /** Values used when no file is loaded. */
    public static final EntertainmentRules FALLBACK = new EntertainmentRules(1.0, 5.0, 8.0, 0.02, 2.0, 1.5, 4, 12, 8, DrinkRules.FALLBACK);

    /** Skill levels of the Tavern Host that add 1 % each to the points of a beer. */
    private static final double DRINK_BONUS_PER_LEVEL = 0.01;

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_entertainment");
    private static EntertainmentRules current = FALLBACK;

    /**
     * The numbers of the drinks in the Tavern evening (vault {@code Systems/Entertainment.md}, Tavern drinks): how much a beer
     * gives, how it is sipped, how many a guest takes and how many a missed evening counts. They sit in the same rules file
     * as the rest.
     *
     * @param points           Entertainment of one finished drink, before the Tavern Host's skill bonus
     * @param sipsMin          fewest sips of one drink
     * @param sipsMax          most sips of one drink
     * @param sipTicks         ticks one sip takes
     * @param pauseMinTicks    shortest pause between two sips in ticks
     * @param pauseMaxTicks    longest pause between two sips in ticks
     * @param maxPerEvening    most drinks one guest takes in an evening
     * @param refillLimitTicks ticks that must be left until the end of the Tavern Host's shift for a guest to fetch a drink
     * @param catchup          drinks each settler present has in a missed evening
     */
    public record DrinkRules(double points, int sipsMin, int sipsMax, int sipTicks, int pauseMinTicks, int pauseMaxTicks, int maxPerEvening,
            int refillLimitTicks, int catchup) {

        /** Values used when no file gives them. */
        public static final DrinkRules FALLBACK = new DrinkRules(0.5, 3, 5, 60, 100, 200, 3, 1000, 2);

        /**
         * @param random the source to draw from
         * @return how many sips the next drink takes
         */
        public int sipCount(RandomSource random) {
            return sipsMin + random.nextInt(sipsMax - sipsMin + 1);
        }

        /**
         * @param random the source to draw from
         * @return ticks of the pause before the next sip
         */
        public int pauseTicks(RandomSource random) {
            return pauseMinTicks + random.nextInt(pauseMaxTicks - pauseMinTicks + 1);
        }
    }

    private record DrinkPart(Optional<Double> points, Optional<Integer> sipsMin, Optional<Integer> sipsMax, Optional<Integer> sipTicks,
            Optional<Integer> pauseMinTicks, Optional<Integer> pauseMaxTicks, Optional<Integer> maxPerEvening, Optional<Integer> refillLimitTicks,
            Optional<Integer> catchup) {

        /** Upper bounds of the datapack values. */
        private static final int MAX_SIPS = 20;
        private static final int MAX_SIP_TICKS = 1200;
        private static final int MAX_PAUSE_TICKS = 2400;
        private static final int MAX_DRINKS = 20;
        private static final int MAX_REFILL_LIMIT_TICKS = 14000;

        static final Codec<DrinkPart> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0.0, MAX).optionalFieldOf("drink_points").forGetter(DrinkPart::points),
                Codec.intRange(1, MAX_SIPS).optionalFieldOf("sips_min").forGetter(DrinkPart::sipsMin),
                Codec.intRange(1, MAX_SIPS).optionalFieldOf("sips_max").forGetter(DrinkPart::sipsMax),
                Codec.intRange(1, MAX_SIP_TICKS).optionalFieldOf("sip_ticks").forGetter(DrinkPart::sipTicks),
                Codec.intRange(0, MAX_PAUSE_TICKS).optionalFieldOf("pause_min_ticks").forGetter(DrinkPart::pauseMinTicks),
                Codec.intRange(0, MAX_PAUSE_TICKS).optionalFieldOf("pause_max_ticks").forGetter(DrinkPart::pauseMaxTicks),
                Codec.intRange(0, MAX_DRINKS).optionalFieldOf("max_drinks_per_evening").forGetter(DrinkPart::maxPerEvening),
                Codec.intRange(0, MAX_REFILL_LIMIT_TICKS).optionalFieldOf("refill_limit_ticks").forGetter(DrinkPart::refillLimitTicks),
                Codec.intRange(0, MAX_DRINKS).optionalFieldOf("catchup_drinks").forGetter(DrinkPart::catchup)
        ).apply(instance, DrinkPart::new));

        DrinkRules over(DrinkRules base) {
            DrinkRules merged = new DrinkRules(points.orElse(base.points()), sipsMin.orElse(base.sipsMin()), sipsMax.orElse(base.sipsMax()),
                    sipTicks.orElse(base.sipTicks()), pauseMinTicks.orElse(base.pauseMinTicks()), pauseMaxTicks.orElse(base.pauseMaxTicks()),
                    maxPerEvening.orElse(base.maxPerEvening()), refillLimitTicks.orElse(base.refillLimitTicks()), catchup.orElse(base.catchup()));
            if (merged.sipsMin() > merged.sipsMax() || merged.pauseMinTicks() > merged.pauseMaxTicks()) {
                throw new IllegalStateException("The drink ranges run backwards: sips " + merged.sipsMin() + " to " + merged.sipsMax()
                        + ", pause " + merged.pauseMinTicks() + " to " + merged.pauseMaxTicks() + " ticks");
            }
            return merged;
        }
    }

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
     * @param present settlers present at the Pantry, the Tavern Host included
     * @return whether the crowd is big enough (condition A)
     */
    boolean crowdHolds(int present) {
        return present >= crowdSize;
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
        boolean crowd = crowdHolds(present);
        if (crowd && jukebox) {
            return tavernPoints * tavernFactor;
        }
        return crowd || jukebox ? tavernPoints : 0.0;
    }

    /**
     * @param hostSkill the skill level of the Tavern Host who served the drink
     * @return the points one finished drink gives: the drink points, plus 1 % for each level of the Tavern Host
     */
    public double drinkPoints(int hostSkill) {
        return drinks.points() * (1.0 + DRINK_BONUS_PER_LEVEL * hostSkill);
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
            DrinkPart drinkPart = DrinkPart.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new EntertainmentRules(part.decay().orElse(merged.decay()), part.tier2Threshold().orElse(merged.tier2Threshold()),
                    part.tier3Threshold().orElse(merged.tier3Threshold()), part.speedPerPoint().orElse(merged.speedPerPoint()),
                    part.tavernPoints().orElse(merged.tavernPoints()), part.tavernFactor().orElse(merged.tavernFactor()),
                    part.crowdSize().orElse(merged.crowdSize()), part.tavernRadius().orElse(merged.tavernRadius()),
                    part.jukeboxRadius().orElse(merged.jukeboxRadius()), drinkPart.over(merged.drinks()));
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
