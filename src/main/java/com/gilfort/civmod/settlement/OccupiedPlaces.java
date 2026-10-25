package com.gilfort.civmod.settlement;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * The occupied work places of a settlement (vault {@code Systems/Jobs.md}): a work place a settler chose as its work
 * place of the day (a Resource Collection work place, a Producing station) is occupied towards every other settler for
 * the rest of that day. The choice of the work place of the day skips places occupied by others.
 * <p>
 * An occupation ends when its day is over (it no longer counts and is replaced on the next choice), when the
 * settler's work place moves (a broken one), when it leaves the job ({@link #releaseJob}), when it dies or is removed
 * ({@link #releaseAll}) and when its JobBlock leaves the settlement. Kept in the {@link Settlement}, not saved: after a
 * reload every settler occupies its saved work place of the day again at its next decision.
 */
public final class OccupiedPlaces {

    private OccupiedPlaces() {
    }

    /**
     * Who occupies a work place.
     *
     * @param settler  the settler
     * @param name     its name, for "In use by <name>"
     * @param jobBlock the JobBlock of the job it works there for
     * @param day      the day it chose the place for
     */
    public record Occupation(UUID settler, String name, BlockPos jobBlock, long day) {
    }

    /**
     * @param settlement the settlement of the JobBlock
     * @param pos        a work place
     * @param settler    the settler asking
     * @param day        the day
     * @return whether the settler may take the place that day: nobody else occupies it for that day (or a later one)
     */
    public static boolean isFreeFor(Settlement settlement, BlockPos pos, UUID settler, long day) {
        Occupation occupation = settlement.mutableOccupied().get(pos);
        return occupation == null || occupation.settler().equals(settler) || occupation.day() < day;
    }

    /**
     * Occupies a work place for a settler's job for a day, if it is free for the settler.
     *
     * @param settlement the settlement of the JobBlock
     * @param pos        the work place
     * @param settler    the settler
     * @param name       its name
     * @param jobBlock   the JobBlock of the job
     * @param day        the day
     * @return whether the settler occupies the place now
     */
    public static boolean occupy(Settlement settlement, BlockPos pos, UUID settler, String name, BlockPos jobBlock, long day) {
        if (!isFreeFor(settlement, pos, settler, day)) {
            return false;
        }
        settlement.mutableOccupied().put(pos.immutable(), new Occupation(settler, name, jobBlock.immutable(), day));
        return true;
    }

    /**
     * Frees a work place the settler occupies (another settler's occupation stays).
     *
     * @param settlement the settlement of the JobBlock
     * @param pos        the work place
     * @param settler    the settler
     */
    public static void release(Settlement settlement, BlockPos pos, UUID settler) {
        Occupation occupation = settlement.mutableOccupied().get(pos);
        if (occupation != null && occupation.settler().equals(settler)) {
            settlement.mutableOccupied().remove(pos);
        }
    }

    /**
     * Frees every work place a settler occupies for one job (it left the job or switched it).
     *
     * @param data     the dimension's settlement data
     * @param settler  the settler
     * @param jobBlock the JobBlock of the job
     */
    public static void releaseJob(SettlementData data, UUID settler, BlockPos jobBlock) {
        for (Settlement settlement : data.settlements()) {
            settlement.mutableOccupied().values().removeIf(occupation -> occupation.settler().equals(settler) && occupation.jobBlock().equals(jobBlock));
        }
    }

    /**
     * Frees every work place a settler occupies (it died or was removed).
     *
     * @param data    the dimension's settlement data
     * @param settler the settler
     */
    public static void releaseAll(SettlementData data, UUID settler) {
        for (Settlement settlement : data.settlements()) {
            settlement.mutableOccupied().values().removeIf(occupation -> occupation.settler().equals(settler));
        }
    }

    /**
     * @param data the dimension's settlement data
     * @param pos  a position
     * @param day  the day
     * @return who occupies the position that day, if anybody
     */
    public static Optional<Occupation> occupant(SettlementData data, BlockPos pos, long day) {
        for (Settlement settlement : data.settlements()) {
            Map<BlockPos, Occupation> occupied = settlement.mutableOccupied();
            Occupation occupation = occupied.get(pos);
            if (occupation != null && occupation.day() == day) {
                return Optional.of(occupation);
            }
        }
        return Optional.empty();
    }
}
