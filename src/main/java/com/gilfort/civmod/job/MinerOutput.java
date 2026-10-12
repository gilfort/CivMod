package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Miner roll (vault {@code Jobs/Miner.md}): stone group or ore pool by the level-based stone share, then a type by
 * weight among the types found by the scan; the work place type has double weight in its group.
 */
public final class MinerOutput {

    private MinerOutput() {
    }

    /**
     * Rolls the block type of one resource.
     *
     * @param scan        the scan result
     * @param poi         the work place block type
     * @param harvestable whether the current pickaxe can harvest a type
     * @param skillLevel  the skill level
     * @param weights     the weights
     * @param random      random source
     * @return the block type, empty if the rolled group has no (harvestable) type in the area
     */
    public static Optional<Identifier> roll(MineScan.Result scan, Identifier poi, Predicate<Identifier> harvestable, int skillLevel,
            MinerWeights weights, RandomSource random) {
        boolean stone = random.nextDouble() < weights.stoneShare(skillLevel);
        List<Identifier> group = (stone ? scan.stones() : scan.ores()).stream().filter(harvestable).sorted().toList();
        if (group.isEmpty()) {
            return Optional.empty();
        }
        double total = 0.0;
        for (Identifier type : group) {
            total += weight(type, stone, poi, weights);
        }
        double pick = random.nextDouble() * total;
        for (Identifier type : group) {
            pick -= weight(type, stone, poi, weights);
            if (pick < 0.0) {
                return Optional.of(type);
            }
        }
        return Optional.of(group.getLast());
    }

    private static double weight(Identifier type, boolean stone, Identifier poi, MinerWeights weights) {
        return weights.weight(type, stone) * (type.equals(poi) ? 2.0 : 1.0);
    }
}
