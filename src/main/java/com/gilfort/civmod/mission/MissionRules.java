package com.gilfort.civmod.mission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Mission arithmetic without world access (vault {@code Systems/Mission Jobs.md}); randomness is passed in.
 */
public final class MissionRules {

    /** Missions offered per board level (index 0 = level 1). */
    private static final int[] OFFER_SIZE = {2, 4, 10, 14, 20};
    /** The largest offer size of any board level. */
    public static final int MAX_OFFER_SIZE = OFFER_SIZE[OFFER_SIZE.length - 1];

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
}
