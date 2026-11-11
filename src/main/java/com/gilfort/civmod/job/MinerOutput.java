package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.gilfort.civmod.api.BiomeBonus;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Miner roll (vault {@code Jobs/Miner.md}): stone group or ore pool by the level-based stone share, then a type by
 * weight among the types found by the scan (base weight times the square root of the block count); the work place type
 * has double weight in its group. Excluded types leave their group and the weights spread over the rest; a fully
 * excluded group gives an empty roll, never one of the other group (vault {@code Systems/Resource Collection Jobs.md}).
 */
public final class MinerOutput {

    private MinerOutput() {
    }

    /**
     * The outcome of one roll.
     *
     * @param type      the rolled block type, empty if nothing was rolled
     * @param emptyRoll whether the rolled group has types in the area, but all of them are excluded: the roll yields
     *                  nothing, yet counts as work (EXP, tool wear); otherwise an empty type means the group has no
     *                  (harvestable) type in the area
     */
    public record Roll(Optional<Identifier> type, boolean emptyRoll) {

        private static final Roll NOTHING = new Roll(Optional.empty(), false);
        private static final Roll EMPTY = new Roll(Optional.empty(), true);
    }

    /**
     * Rolls the block type of one resource, with a biome bonus on the ore share.
     *
     * @param scan        the scan result
     * @param poi         the work place block type
     * @param harvestable whether the current pickaxe can harvest a type
     * @param excluded    the types excluded at the JobBlock
     * @param skillLevel  the skill level
     * @param weights     the weights
     * @param rareChance  the factor on the ore share (1 − stone share), capped at an ore share of 1 (1 without a bonus)
     * @param random      random source
     * @return the roll
     */
    public static Roll roll(MineScan.Result scan, Identifier poi, Predicate<Identifier> harvestable, Set<Identifier> excluded, int skillLevel,
            MinerWeights weights, double rareChance, RandomSource random) {
        double stoneShare = weights.stoneShare(skillLevel);
        if (rareChance != 1.0) {
            stoneShare = 1.0 - BiomeBonus.cappedChance(1.0 - stoneShare, rareChance);
        }
        boolean stone = random.nextDouble() < stoneShare;
        Map<Identifier, Integer> counts = stone ? scan.stones() : scan.ores();
        List<Identifier> found = counts.keySet().stream().filter(harvestable).sorted().toList();
        if (found.isEmpty()) {
            return Roll.NOTHING;
        }
        List<Identifier> group = found.stream().filter(type -> !excluded.contains(type)).toList();
        if (group.isEmpty()) {
            return Roll.EMPTY;
        }
        double total = 0.0;
        for (Identifier type : group) {
            total += weight(type, counts.get(type), stone, poi, weights);
        }
        double pick = random.nextDouble() * total;
        for (Identifier type : group) {
            pick -= weight(type, counts.get(type), stone, poi, weights);
            if (pick < 0.0) {
                return new Roll(Optional.of(type), false);
            }
        }
        return new Roll(Optional.of(group.getLast()), false);
    }

    /**
     * The chance of each type to be rolled, for the Found tab: its group's share at the skill level times its weight
     * among the types of its group that the tool can harvest. Exclusions, the work place bonus and biome bonuses are
     * not part of it. A group without a harvestable type has no entries.
     *
     * @param scan        the scan result
     * @param harvestable whether the tool can harvest a type
     * @param skillLevel  the skill level
     * @param weights     the weights
     * @return the chance (0-1) per type
     */
    public static Map<Identifier, Double> chances(MineScan.Result scan, Predicate<Identifier> harvestable, int skillLevel, MinerWeights weights) {
        double stoneShare = weights.stoneShare(skillLevel);
        Map<Identifier, Double> chances = new HashMap<>();
        addGroupChances(chances, scan.stones(), true, stoneShare, harvestable, weights);
        addGroupChances(chances, scan.ores(), false, 1.0 - stoneShare, harvestable, weights);
        return chances;
    }

    private static void addGroupChances(Map<Identifier, Double> chances, Map<Identifier, Integer> counts, boolean stone, double share,
            Predicate<Identifier> harvestable, MinerWeights weights) {
        double total = 0.0;
        for (Map.Entry<Identifier, Integer> entry : counts.entrySet()) {
            if (harvestable.test(entry.getKey())) {
                total += weights.rollWeight(entry.getKey(), stone, entry.getValue());
            }
        }
        for (Map.Entry<Identifier, Integer> entry : counts.entrySet()) {
            if (harvestable.test(entry.getKey()) && total > 0.0) {
                chances.put(entry.getKey(), share * weights.rollWeight(entry.getKey(), stone, entry.getValue()) / total);
            }
        }
    }

    private static double weight(Identifier type, int count, boolean stone, Identifier poi, MinerWeights weights) {
        return weights.rollWeight(type, stone, count) * (type.equals(poi) ? 2.0 : 1.0);
    }
}
