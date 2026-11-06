package com.gilfort.civmod.settlement;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * The Laborers helping at the Resource Collection JobBlocks of a settlement (vault {@code Jobs/Laborer.md}): who helps
 * at which JobBlock, and the work place of the day they share when the JobBlock has no employee to choose one. Kept in
 * the {@link Settlement}, one {@link Crew} per JobBlock, not saved: a Laborer chooses anew after a reload.
 * <p>
 * A Laborer leaves its crew when a higher Job takes over, when it stops helping, when it dies or is removed
 * ({@link #leave}) and when the JobBlock leaves the settlement.
 */
public final class Laborers {

    private Laborers() {
    }

    /** The Laborers of one JobBlock and the work place they share today. */
    static final class Crew {
        private final Set<UUID> laborers = new LinkedHashSet<>();
        private @Nullable BlockPos place;
        private long placeDay = -1L;
    }

    /**
     * @param settlement the settlement of the JobBlock
     * @param jobBlock   the JobBlock
     * @return how many Laborers help there
     */
    public static int count(Settlement settlement, BlockPos jobBlock) {
        Crew crew = settlement.mutableCrews().get(jobBlock);
        return crew == null ? 0 : crew.laborers.size();
    }

    /**
     * @param settlement the settlement of the JobBlock
     * @param jobBlock   the JobBlock
     * @param settler    a settler
     * @return whether the settler helps at the JobBlock
     */
    public static boolean helps(Settlement settlement, BlockPos jobBlock, UUID settler) {
        Crew crew = settlement.mutableCrews().get(jobBlock);
        return crew != null && crew.laborers.contains(settler);
    }

    /**
     * Lets a settler help at a JobBlock if the crew has room.
     *
     * @param settlement the settlement of the JobBlock
     * @param jobBlock   the JobBlock
     * @param settler    the settler
     * @param max        the most Laborers the JobBlock takes
     * @return whether the settler helps there now (it did before, or it joined)
     */
    public static boolean join(Settlement settlement, BlockPos jobBlock, UUID settler, int max) {
        Crew crew = settlement.mutableCrews().computeIfAbsent(jobBlock.immutable(), pos -> new Crew());
        return crew.laborers.contains(settler) || (crew.laborers.size() < max && crew.laborers.add(settler));
    }

    /**
     * Takes a settler out of every crew (it stops helping, or it died or was removed).
     *
     * @param data    the dimension's settlement data
     * @param settler the settler
     */
    public static void leave(SettlementData data, UUID settler) {
        for (Settlement settlement : data.settlements()) {
            settlement.mutableCrews().values().forEach(crew -> crew.laborers.remove(settler));
            settlement.mutableCrews().values().removeIf(crew -> crew.laborers.isEmpty() && crew.place == null);
        }
    }

    /**
     * The work place of the day the Laborers of a JobBlock share: the one its employee chose (the employee occupies
     * it), else the one the first Laborer chose.
     *
     * @param settlement the settlement of the JobBlock
     * @param jobBlock   the JobBlock
     * @param day        the day
     * @return the work place, empty if nobody chose one for that day
     */
    public static Optional<BlockPos> sharedPlace(Settlement settlement, BlockPos jobBlock, long day) {
        Optional<BlockPos> employees = settlement.mutableOccupied().entrySet().stream()
                .filter(entry -> entry.getValue().jobBlock().equals(jobBlock) && entry.getValue().day() == day)
                .map(java.util.Map.Entry::getKey).findFirst();
        if (employees.isPresent()) {
            return employees;
        }
        Crew crew = settlement.mutableCrews().get(jobBlock);
        return crew != null && crew.placeDay == day ? Optional.ofNullable(crew.place) : Optional.empty();
    }

    /**
     * Remembers the work place the first Laborer of a JobBlock without employee chose, for the others to share.
     *
     * @param settlement the settlement of the JobBlock
     * @param jobBlock   the JobBlock
     * @param place      the work place
     * @param day        the day
     */
    public static void setPlace(Settlement settlement, BlockPos jobBlock, BlockPos place, long day) {
        Crew crew = settlement.mutableCrews().computeIfAbsent(jobBlock.immutable(), pos -> new Crew());
        crew.place = place.immutable();
        crew.placeDay = day;
    }
}
