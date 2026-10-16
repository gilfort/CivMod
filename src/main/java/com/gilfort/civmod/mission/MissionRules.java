package com.gilfort.civmod.mission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

/**
 * Mission arithmetic without world access (vault {@code Systems/Mission Jobs.md}); randomness is passed in.
 */
public final class MissionRules {

    /** Missions offered per board level (index 0 = level 1). */
    private static final int[] OFFER_SIZE = {2, 4, 10, 14, 20};
    /** The largest offer size of any board level. */
    public static final int MAX_OFFER_SIZE = OFFER_SIZE[OFFER_SIZE.length - 1];
    /** Skill levels above the recommendation per extra loot roll. */
    private static final int LEVELS_PER_EXTRA_ROLL = 20;

    private MissionRules() {
    }

    /**
     * @param boardLevel the Mission Board level
     * @return how many missions the board offers each day
     */
    public static int offerSize(int boardLevel) {
        return OFFER_SIZE[SettlementRules.clampLevel(boardLevel) - SettlementRules.MIN_LEVEL];
    }

    /**
     * The daily refill: keeps marked entries whose mission still exists (in order), drops unmarked ones and draws new
     * unmarked entries up to {@code size}. Only missions one of the {@code unlocked} Mission Jobs can take are drawn,
     * weighted by {@link Mission#weight()}; a mission appears at most {@link Mission#maxPerOffer()} times, kept entries
     * included. The offer stays shorter when every drawable mission is at its maximum.
     *
     * @param offer    the current entries
     * @param pool     all missions by id, in a stable order
     * @param unlocked the Mission Jobs the board level unlocks
     * @param size     the offer size
     * @param random   the random source
     * @return the new entries, marked ones first
     */
    public static List<MissionOffer.Entry> refill(List<MissionOffer.Entry> offer, Map<Identifier, Mission> pool, Collection<Job> unlocked,
            int size, RandomSource random) {
        List<MissionOffer.Entry> result = new ArrayList<>();
        Map<Identifier, Integer> copies = new HashMap<>();
        for (MissionOffer.Entry entry : offer) {
            if (entry.marked() && pool.containsKey(entry.mission())) {
                result.add(entry);
                copies.merge(entry.mission(), 1, Integer::sum);
            }
        }
        List<Identifier> drawable = pool.entrySet().stream()
                .filter(mission -> mission.getValue().jobs().stream().anyMatch(unlocked::contains))
                .map(Map.Entry::getKey).toList();
        while (result.size() < size) {
            int total = 0;
            for (Identifier id : drawable) {
                total += available(pool.get(id), copies.getOrDefault(id, 0));
            }
            if (total == 0) {
                break;
            }
            int roll = random.nextInt(total);
            for (Identifier id : drawable) {
                roll -= available(pool.get(id), copies.getOrDefault(id, 0));
                if (roll < 0) {
                    result.add(new MissionOffer.Entry(id, false));
                    copies.merge(id, 1, Integer::sum);
                    break;
                }
            }
        }
        return List.copyOf(result);
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
        return new Chances(success, Math.min(death, 1.0 - success));
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
     * The pick rule: the marked mission with the highest recommended level at or below the skill that the Mission Job
     * may take; missions above the skill are never picked. Equal levels: the first entry.
     *
     * @param offer the current offer
     * @param pool  all missions by id
     * @param job   the settler's Mission Job
     * @param skill the settler's (effective) Mission Job skill
     * @return the offer index of the picked mission, empty if none fits
     */
    public static OptionalInt pick(List<MissionOffer.Entry> offer, Map<Identifier, Mission> pool, Job job, int skill) {
        int best = -1;
        int bestLevel = Integer.MIN_VALUE;
        for (int i = 0; i < offer.size(); i++) {
            MissionOffer.Entry entry = offer.get(i);
            Mission mission = pool.get(entry.mission());
            if (!entry.marked() || mission == null || !mission.jobs().contains(job) || mission.recommendedLevel() > skill) {
                continue;
            }
            if (mission.recommendedLevel() > bestLevel) {
                best = i;
                bestLevel = mission.recommendedLevel();
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
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
        return (departureDay + days - 1) * 24000L + SettlerWork.WORK_END;
    }
}
