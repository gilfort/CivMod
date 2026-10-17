package com.gilfort.civmod.settlement;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * The Clerk's stand spot of the day (vault {@code Jobs/Clerk.md}): one of its stand spots is picked each morning and
 * again at 12:00, never the same one twice in a row if there are several. The pick is saved on the JobBlock, so the
 * Clerk and its customers agree on it; whoever asks first in a half day picks.
 */
public final class ClerkSpots {

    /** Day time of the second pick (12:00; day time 0 = 06:00). */
    public static final long NOON = 6000L;

    private ClerkSpots() {
    }

    /**
     * @param data    the dimension's settlement data
     * @param clerk   the Clerk JobBlock position
     * @param day     the day
     * @param dayTime the day time
     * @param random  random source for a new pick
     * @return the stand spot for now, empty if the Clerk has none
     */
    public static Optional<SettlementBlock.StandSpot> today(SettlementData data, BlockPos clerk, long day, long dayTime, RandomSource random) {
        Optional<SettlementBlock> found = JobBlockSite.blockAt(data, clerk);
        if (found.isEmpty() || found.get().standSpots().isEmpty()) {
            return Optional.empty();
        }
        SettlementBlock block = found.get();
        int spots = block.standSpots().size();
        long period = day * 2 + (dayTime >= NOON ? 1 : 0);
        SettlementBlock.SpotOfDay current = block.spotOfDay();
        if (current.period() != period || current.index() < 0 || current.index() >= spots) {
            int previous = current.index();
            int index;
            if (spots == 1) {
                index = 0;
            } else if (previous < 0 || previous >= spots) {
                index = random.nextInt(spots);
            } else {
                // Any other spot: skip the previous one.
                index = (previous + 1 + random.nextInt(spots - 1)) % spots;
            }
            block.setSpotOfDay(new SettlementBlock.SpotOfDay(period, index));
            data.changed();
        }
        return Optional.of(block.standSpots().get(block.spotOfDay().index()));
    }
}
