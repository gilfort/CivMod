package com.gilfort.civmod.mission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.ToIntFunction;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.StationTypes;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

/**
 * Mission arithmetic without world access (vault {@code Systems/Mission Jobs.md}); randomness is passed in.
 */
public final class MissionRules {

    /** Specialization slots of a Mission Board (Station Types slots besides the Base Job's). */
    private static final int SPECIALIZATION_SLOTS = StationTypes.SLOTS - 1;
    /** The largest offer of any board: the Base Job's missions plus every Specialization slot filled, at the top level. */
    public static final int MAX_OFFER_SIZE = SettlementRules.MAX_LEVEL + 1 + SPECIALIZATION_SLOTS * SettlementRules.MAX_LEVEL;
    /** Skill levels above the recommendation per extra loot roll. */
    private static final int LEVELS_PER_EXTRA_ROLL = 20;

    private MissionRules() {
    }

    /**
     * How many missions each Mission Job of a board gets in the daily offer: the Base Job (the Adventurer) board level + 1,
     * plus 1 per open Station Types slot without a Specialization; every unlocked Specialization as many as the board
     * level.
     *
     * @param unlocked   the Mission Jobs the board offers, its Base Job first ({@code StationTypes.unlocked})
     * @param boardLevel the Mission Board level
     * @param emptySlots the open Station Types slots without an unlock item
     * @return the number of missions per Mission Job, in the order of {@code unlocked}
     */
    public static Map<Job, Integer> quotas(List<Job> unlocked, int boardLevel, int emptySlots) {
        int level = SettlementRules.clampLevel(boardLevel);
        Map<Job, Integer> quotas = new LinkedHashMap<>();
        for (int i = 0; i < unlocked.size(); i++) {
            quotas.put(unlocked.get(i), i == 0 ? level + 1 + emptySlots : level);
        }
        return quotas;
    }

    /**
     * The daily refill: keeps marked entries whose mission still exists (in order), drops unmarked ones and draws new
     * unmarked entries for every Mission Job up to its quota ({@link #quotas}); a kept entry counts towards the quota of
     * the first Mission Job (in quota order) that may take it and still has room. Each job draws among the missions it
     * may take, weighted by {@link Mission#weight()}; a mission appears at most {@link Mission#maxPerOffer()} times,
     * kept entries included. A job's share stays shorter when every mission it may take is at its maximum; the whole
     * offer never exceeds {@link #MAX_OFFER_SIZE}.
     *
     * @param offer  the current entries
     * @param pool   all missions by id, in a stable order
     * @param quotas the number of missions per Mission Job, in drawing order
     * @param random the random source
     * @return the new entries, marked ones first
     */
    public static List<MissionOffer.Entry> refill(List<MissionOffer.Entry> offer, Map<Identifier, Mission> pool, Map<Job, Integer> quotas,
            RandomSource random) {
        List<MissionOffer.Entry> result = new ArrayList<>();
        Map<Identifier, Integer> copies = new HashMap<>();
        Map<Job, Integer> left = new LinkedHashMap<>(quotas);
        for (MissionOffer.Entry entry : offer) {
            Mission mission = pool.get(entry.mission());
            if (entry.marked() && mission != null) {
                result.add(entry);
                copies.merge(entry.mission(), 1, Integer::sum);
                left.entrySet().stream().filter(quota -> quota.getValue() > 0 && mission.jobs().contains(quota.getKey())).findFirst()
                        .ifPresent(quota -> quota.setValue(quota.getValue() - 1));
            }
        }
        for (Map.Entry<Job, Integer> quota : left.entrySet()) {
            List<Identifier> drawable = pool.entrySet().stream()
                    .filter(mission -> mission.getValue().jobs().contains(quota.getKey()))
                    .map(Map.Entry::getKey).toList();
            for (int i = 0; i < quota.getValue() && result.size() < MAX_OFFER_SIZE; i++) {
                Optional<Identifier> drawn = draw(drawable, pool, copies, random);
                if (drawn.isEmpty()) {
                    break;
                }
                result.add(new MissionOffer.Entry(drawn.get(), false));
                copies.merge(drawn.get(), 1, Integer::sum);
            }
        }
        return List.copyOf(result);
    }

    /** One weighted draw among the missions below their maximum; empty if every one is at it. */
    private static Optional<Identifier> draw(List<Identifier> drawable, Map<Identifier, Mission> pool, Map<Identifier, Integer> copies,
            RandomSource random) {
        int total = 0;
        for (Identifier id : drawable) {
            total += available(pool.get(id), copies.getOrDefault(id, 0));
        }
        if (total == 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (Identifier id : drawable) {
            roll -= available(pool.get(id), copies.getOrDefault(id, 0));
            if (roll < 0) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** The draw weight of a mission, 0 once it reached its maximum. */
    private static int available(Mission mission, int copies) {
        return copies < mission.maxPerOffer() ? mission.weight() : 0;
    }

    // ---------------------------------------------------------------- running a mission

    /** How a mission ends. */
    public enum Outcome {
        /** Completed: loot and full EXP. */
        SUCCESS,
        /** Turned back: no loot, reduced EXP. */
        FAILED,
        /** Did not return. */
        DEATH
    }

    /**
     * Outcome chances of one settler on one mission; failed is the rest.
     *
     * @param success the success chance
     * @param death   the death chance
     */
    public record Chances(double success, double death) {

        /** Death chances from here on are shown as a warning. */
        private static final double HIGH_DEATH = 0.20;

        /**
         * @param success the success chance
         * @param death   the death chance
         * @return the chances with death capped at {@code 1 − success}
         */
        static Chances of(double success, double death) {
            return new Chances(success, Math.min(death, 1.0 - success));
        }

        /**
         * @return whether the death chance is high enough to warn the player
         */
        public boolean highDeath() {
            return death >= HIGH_DEATH;
        }

        /**
         * @return the failure chance
         */
        public double failed() {
            return Math.max(0.0, 1.0 - success - death);
        }
    }

    /**
     * The outcome formula: {@code gap = recommended − skill}; success = clamp(base − perLevel·gap), death =
     * clamp(base + perLevel·gap), failed = rest.
     *
     * @param rules       the outcome constants
     * @param recommended the mission's recommended level
     * @param skill       the settler's (effective) Mission Job skill
     * @return the chances
     */
    public static Chances chances(OutcomeRules rules, int recommended, int skill) {
        int gap = recommended - skill;
        double success = Math.clamp(rules.successBase() - rules.successPerLevel() * gap, rules.successMin(), rules.successMax());
        double death = Math.clamp(rules.deathBase() + rules.deathPerLevel() * gap, rules.deathMin(), rules.deathMax());
        return Chances.of(success, death);
    }

    /**
     * The outcome formula ({@link #chances(OutcomeRules, int, int)}) passed through the Mission Job's
     * {@link MissionProfile} hooks, clamped to {@code 0..1} with death at most {@code 1 − success}.
     *
     * @param rules       the outcome constants
     * @param recommended the mission's recommended level
     * @param skill       the settler's (effective) Mission Job skill
     * @param job         the settler's Mission Job
     * @return the chances
     */
    public static Chances chances(OutcomeRules rules, int recommended, int skill, Job job) {
        Chances base = chances(rules, recommended, skill);
        MissionProfile profile = job.profile(MissionProfile.class).orElse(MissionProfile.NEUTRAL);
        double success = Math.clamp(profile.successChance(base.success(), recommended, skill), 0.0, 1.0);
        double death = Math.clamp(profile.deathChance(base.death(), recommended, skill), 0.0, 1.0);
        return Chances.of(success, death);
    }

    /**
     * @param chances the chances
     * @param random  the random source
     * @return the rolled outcome
     */
    public static Outcome roll(Chances chances, RandomSource random) {
        double roll = random.nextDouble();
        if (roll < chances.success()) {
            return Outcome.SUCCESS;
        }
        return roll < chances.success() + chances.death() ? Outcome.DEATH : Outcome.FAILED;
    }

    /**
     * The pick rule: missions the player assigned to the settler come first, in random order and regardless of its skill.
     * Otherwise the marked mission with the highest recommended level at or below the skill that is not locked for the
     * settler; missions above the skill are never picked. Equal levels: the first entry. Only missions the Mission Job
     * may take are picked.
     *
     * @param offer   the current offer
     * @param pool    all missions by id
     * @param job     the settler's Mission Job
     * @param skill   the settler's (effective) Mission Job skill
     * @param settler the settler
     * @param random  the random source (order of several assigned missions)
     * @return the offer index of the picked mission, empty if none fits
     */
    public static OptionalInt pick(List<MissionOffer.Entry> offer, Map<Identifier, Mission> pool, Job job, int skill, UUID settler,
            RandomSource random) {
        List<Integer> assigned = new ArrayList<>();
        int best = -1;
        int bestLevel = Integer.MIN_VALUE;
        for (int i = 0; i < offer.size(); i++) {
            MissionOffer.Entry entry = offer.get(i);
            Mission mission = pool.get(entry.mission());
            if (!entry.takeableBy(settler) || mission == null || !mission.jobs().contains(job)) {
                continue;
            }
            if (entry.locked()) {
                assigned.add(i);
            } else if (mission.recommendedLevel() <= skill && mission.recommendedLevel() > bestLevel) {
                best = i;
                bestLevel = mission.recommendedLevel();
            }
        }
        if (!assigned.isEmpty()) {
            return OptionalInt.of(assigned.get(random.nextInt(assigned.size())));
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }

    /**
     * A settler of a Mission Board as the pick rule sees it.
     *
     * @param id    the settler UUID
     * @param job   its Mission Job
     * @param skill its effective Mission Job skill
     */
    public record Picker(UUID id, Job job, int skill) {
    }

    /**
     * Which settler the automatic rule would send on one offer entry: every settler asks at the same time and again
     * after each pick ({@link #order} and {@link #pick}), until one takes the entry or nobody takes anything more. An
     * unmarked entry is predicted as if it were marked. Equal skills pick in board order (the real order is random).
     *
     * @param offer    the current offer
     * @param pool     all missions by id
     * @param index    the offer index of the entry
     * @param settlers the settlers of the board
     * @return the index (in {@code settlers}) of the settler that would take the entry, empty if none would
     */
    public static OptionalInt picker(List<MissionOffer.Entry> offer, Map<Identifier, Mission> pool, int index, List<Picker> settlers) {
        List<MissionOffer.Entry> left = new ArrayList<>(offer);
        left.set(index, left.get(index).withMarked(true));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < settlers.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> settlers.get(i).skill()));
        // Several assigned missions only change their own order, never who takes another entry.
        RandomSource random = RandomSource.create(0L);
        boolean picked = true;
        while (picked) {
            picked = false;
            for (int i : order) {
                Picker settler = settlers.get(i);
                OptionalInt pick = pick(left, pool, settler.job(), settler.skill(), settler.id(), random);
                if (pick.isEmpty()) {
                    continue;
                }
                if (pick.getAsInt() == index) {
                    return OptionalInt.of(i);
                }
                left.set(pick.getAsInt(), left.get(pick.getAsInt()).withMarked(false));
                picked = true;
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The order in which settlers that want a mission at the same time pick: ascending skill, equal skills randomly.
     *
     * @param candidates the settlers
     * @param skill      the Mission Job skill of a settler
     * @param random     the random source
     * @param <T>        the settler type
     * @return the settlers in picking order (a new list)
     */
    public static <T> List<T> order(List<T> candidates, ToIntFunction<T> skill, RandomSource random) {
        List<T> order = new ArrayList<>(candidates);
        Util.shuffle(order, random);
        order.sort(Comparator.comparingInt(skill));
        return order;
    }

    /**
     * @param mission the mission
     * @param skill   the settler's (effective) Mission Job skill
     * @return the loot rolls: base rolls plus one per full 20 levels above the recommendation
     */
    public static int rolls(Mission mission, int skill) {
        return mission.rolls() + Math.max(0, skill - mission.recommendedLevel()) / LEVELS_PER_EXTRA_ROLL;
    }

    /**
     * The nitwit chance of each Villager a Villager mission brings: {@code clamp(base − perLevel · (skill −
     * recommended), min, max)} with the mission's own values. The skill never changes how many Villagers come.
     *
     * @param villagers   the mission's Villagers
     * @param recommended the mission's recommended level
     * @param skill       the settler's (effective) Mission Job skill
     * @return the chance that one Villager is a nitwit
     */
    public static double nitwitChance(Mission.Villagers villagers, int recommended, int skill) {
        double chance = villagers.nitwitBase() - villagers.nitwitPerLevel() * (skill - recommended);
        return Math.clamp(chance, villagers.nitwitMin(), villagers.nitwitMax());
    }

    /**
     * Mission EXP: {@code baseExp × board level × mission level}, times the success or failure multiplier; none on death.
     *
     * @param outcome     the outcome
     * @param rules       the outcome constants
     * @param baseExp     the Mission Job's base EXP
     * @param boardLevel  the Mission Board level
     * @param recommended the mission's recommended level
     * @return the EXP
     */
    public static int exp(Outcome outcome, OutcomeRules rules, int baseExp, int boardLevel, int recommended) {
        double factor = switch (outcome) {
            case SUCCESS -> rules.successExp();
            case FAILED -> rules.failedExp();
            case DEATH -> 0.0;
        };
        return (int) Math.round((double) baseExp * boardLevel * recommended * factor);
    }

    /**
     * @param departureDay the day the settler left (in the morning)
     * @param days         the mission duration in days
     * @return the world time of the return: the evening (end of work) of the last mission day, so a one-day mission
     *         returns the evening of its departure day
     */
    public static long returnTime(long departureDay, int days) {
        return (departureDay + days - 1) * DayClock.DAY_TICKS + SettlerWork.WORK_END;
    }
}
