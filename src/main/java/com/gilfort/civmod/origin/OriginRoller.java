package com.gilfort.civmod.origin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import com.gilfort.civmod.npc.RaceNames;
import com.gilfort.civmod.origin.RaceDefinition.NameList;
import com.gilfort.civmod.origin.RaceDefinition.SurnameRule;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Rolls the origin of one settler from the data of its race (vault {@code Systems/Settler Origin.md}). It decides from plain
 * values and touches no world, so a test can hand it a race and a scripted random source.
 * <p>
 * Every choice asks the random source for one {@code nextInt}, in this order, which tests script: the gender (0 male, 1 female),
 * the first name (index into the unused names of the gender and the unisex names), the upbringing, the place (index into the
 * candidates), then the surname (the type by weight, then its detail: the parent's name, or the trade with its ancestor, or the
 * trait or clan), the turning point and the reason. A weighted choice draws one number below the sum of the weights, entries
 * in table order.
 */
public final class OriginRoller {

    /** The name of a settler whose race has no first names (also of one whose race has no origin definition). */
    static final String FALLBACK_NAME = "Settler";

    private OriginRoller() {
    }

    /**
     * What the arrival tells the roll.
     *
     * @param channel        how the settler comes in
     * @param hits           the nearest biome per biome type that the settlement found, empty without a settlement or lookup
     * @param missionPlace   the place a mission names, for a Villager it brought
     * @param keepName       whether the settler keeps a name it already has: no first name and no surname are rolled
     * @param usedFirstNames the first names that settlers of the settlement already carry
     */
    public record Arrival(Channel channel, Map<Identifier, PlaceHit> hits, Optional<Identifier> missionPlace, boolean keepName,
            Collection<String> usedFirstNames) {
    }

    /**
     * @param race    the race of the settler
     * @param names   the first names of the configured name culture
     * @param arrival what the arrival tells
     * @param random  the random source
     * @return the origin, without a rescuer
     */
    public static SettlerOrigin roll(RaceDefinition race, NameList names, Arrival arrival, RandomSource random) {
        Gender gender = Gender.values()[random.nextInt(Gender.values().length)];
        Optional<String> first = arrival.keepName() ? Optional.empty()
                : Optional.of(RaceNames.pick(names.pool(gender), arrival.usedFirstNames(), random).orElse(FALLBACK_NAME));
        Optional<OriginEntry> upbringing = pickWeighted(race.upbringings(), OriginEntry::weight, random);
        Place place = rollPlace(race, arrival, random);
        Optional<SettlerName> name = first.map(firstName -> new SettlerName(firstName, rollSurname(race, names, gender, upbringing, place, random)));
        Optional<OriginEntry> turningPoint = pickWeighted(
                race.turningPoints().stream().filter(entry -> fits(entry, place)).toList(), OriginEntry::weight, random);
        String channel = arrival.channel().key();
        Optional<OriginEntry> reason = pickWeighted(
                race.reasons().stream().filter(entry -> entry.channels().contains(channel)).toList(), OriginEntry::weight, random);
        return new SettlerOrigin(gender, name, place, upbringing.map(OriginEntry::id), turningPoint.map(OriginEntry::id),
                reason.map(OriginEntry::id), Optional.empty());
    }

    private static boolean fits(OriginEntry turningPoint, Place place) {
        return turningPoint.places().isEmpty() || place.conditionIds().stream().anyMatch(turningPoint.places()::contains);
    }

    private static Place rollPlace(RaceDefinition race, Arrival arrival, RandomSource random) {
        return switch (arrival.channel().placeRule()) {
            case VILLAGE -> Place.village();
            case MISSION -> arrival.missionPlace().map(Place::mission).orElseGet(Place::village);
            case ANY_TYPE -> race.places().isEmpty() ? Place.farAway()
                    : Place.somewhere(race.places().get(random.nextInt(race.places().size())).id());
            case ANY_HIT -> rollNear(race, arrival.hits(), false, random);
            case NEARBY -> rollNear(race, arrival.hits(), true, random);
        };
    }

    /** Picks among the biome types that have a hit; a biome type without one is not a candidate, with none the settler comes from far away. */
    private static Place rollNear(RaceDefinition race, Map<Identifier, PlaceHit> hits, boolean nearerHalfOnly, RandomSource random) {
        List<Identifier> candidates = new ArrayList<>(race.places().stream().map(OriginEntry::id).filter(hits::containsKey).toList());
        if (candidates.isEmpty()) {
            return Place.farAway();
        }
        candidates.sort(Comparator.comparingInt(type -> hits.get(type).blocks()));
        int count = nearerHalfOnly ? (candidates.size() + 1) / 2 : candidates.size();
        Identifier type = candidates.get(random.nextInt(count));
        return Place.biome(type, hits.get(type));
    }

    private static SettlerName.Surname rollSurname(RaceDefinition race, NameList names, Gender gender, Optional<OriginEntry> upbringing,
            Place place, RandomSource random) {
        List<SurnameRule> usable = race.surnames().stream()
                .filter(rule -> rule.weight() > 0 && rule.type().allows(gender) && hasMaterial(race, names, rule.type(), place)).toList();
        Optional<SurnameRule> rule = pickWeighted(usable, SurnameRule::weight, random);
        if (rule.isEmpty()) {
            return SettlerName.Surname.NONE;
        }
        SurnameType type = rule.get().type();
        return switch (type) {
            case PATRONYMIC -> parentName(type, names.pool(Gender.MALE), random);
            case MATRONYMIC -> parentName(type, names.pool(Gender.FEMALE), random);
            case TRADE -> trade(race, upbringing, random);
            case PLACE -> new SettlerName.Surname(type, place.id().toString(), Optional.empty());
            case TRAIT, CLAN -> new SettlerName.Surname(type, pickWeighted(race.surnameEntries().get(type), OriginEntry::weight, random)
                    .orElseThrow().id().toString(), Optional.empty());
            case NONE -> SettlerName.Surname.NONE;
        };
    }

    /** Whether the race and the place give a surname of this type something to be made of; otherwise the type drops out. */
    private static boolean hasMaterial(RaceDefinition race, NameList names, SurnameType type, Place place) {
        return switch (type) {
            case PATRONYMIC -> !names.pool(Gender.MALE).isEmpty();
            case MATRONYMIC -> !names.pool(Gender.FEMALE).isEmpty();
            case PLACE -> place.kind() == Place.Kind.BIOME;
            case TRADE, TRAIT, CLAN -> !race.surnameEntries().getOrDefault(type, List.of()).isEmpty();
            case NONE -> true;
        };
    }

    private static SettlerName.Surname parentName(SurnameType type, List<String> pool, RandomSource random) {
        return new SettlerName.Surname(type, pool.get(random.nextInt(pool.size())), Optional.empty());
    }

    /** A trade is independent of the talent; the father is only an ancestor when the trade fits the upbringing. */
    private static SettlerName.Surname trade(RaceDefinition race, Optional<OriginEntry> upbringing, RandomSource random) {
        OriginEntry trade = pickWeighted(race.surnameEntries().get(SurnameType.TRADE), OriginEntry::weight, random).orElseThrow();
        Predicate<OriginEntry> fitsTrade = trade::sharesJobWith;
        List<Ancestor> ancestors = new ArrayList<>();
        if (upbringing.filter(fitsTrade).isPresent()) {
            ancestors.add(Ancestor.FATHER);
        }
        ancestors.add(Ancestor.GRANDFATHER);
        ancestors.add(Ancestor.GREAT_GRANDFATHER);
        return new SettlerName.Surname(SurnameType.TRADE, trade.id().toString(), Optional.of(ancestors.get(random.nextInt(ancestors.size()))));
    }

    /** One weighted draw ({@code nextInt} below the sum of the weights); empty if there is nothing to draw from. */
    private static <T> Optional<T> pickWeighted(List<T> items, ToIntFunction<T> weight, RandomSource random) {
        int total = items.stream().mapToInt(weight).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (T item : items) {
            roll -= weight.applyAsInt(item);
            if (roll < 0) {
                return Optional.of(item);
            }
        }
        throw new IllegalStateException("Weighted draw " + roll + " left over from " + items);
    }
}
