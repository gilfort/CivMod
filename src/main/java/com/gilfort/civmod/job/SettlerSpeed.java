package com.gilfort.civmod.job;

import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.settlement.Bonuses;
import com.gilfort.civmod.settlement.SettlementData;
import net.minecraft.server.level.ServerLevel;

/**
 * The work speed bonuses of a settler in one place: the blessing of the settlement, the boost of a {@code WorkSpeed}
 * effect and the Entertainment bonus, and the tail every work speed shares (biome bonus times this factor). The base speed
 * stays with the profile of each caller. Live play passes {@link SimulatedSpan#LIVE}; catch-up passes the span it
 * simulates, over which the bonuses are averaged.
 */
public final class SettlerSpeed {
    private SettlerSpeed() {
    }

    /**
     * The factor every work speed is multiplied with: the one place where the speed bonuses add up. The blessing's share
     * ({@link #blessingFactor}), the boost of a {@code WorkSpeed} effect ({@link #speedBoostShare}) and the 2 % per point of
     * Entertainment ({@link EntertainmentRules#speedFactor}) are added, not multiplied. Live play and catch-up both read it;
     * the value is the one of the day being worked, because the decay is taken at wake-up before the work starts.
     *
     * @param work  the settler's work
     * @param level the level
     * @return 1 without any bonus, 1.5 with full Entertainment, a blessing and a 10 % boost
     */
    public static double speedFactor(SettlerWork work, ServerLevel level) {
        return speedFactor(work, level, SimulatedSpan.LIVE);
    }

    /**
     * @param work  the settler's work
     * @param level the level
     * @param span  the simulated work span, {@link SimulatedSpan#LIVE} in live play
     * @return the speed factor, with the bonuses averaged over the span in catch-up
     */
    static double speedFactor(SettlerWork work, ServerLevel level, SimulatedSpan span) {
        return EntertainmentRules.get().speedFactor(work.entertainmentValue(), blessingFactor(work, level, span) + speedBoostShare(work, level, span));
    }

    /**
     * The work speed factor of the settlement's blessing ({@link Bonuses}): live that of now, in catch-up the average over
     * the simulated work time.
     *
     * @param work  the settler's work
     * @param level the level
     * @return 1 without a blessing, else its factor
     */
    public static double blessingFactor(SettlerWork work, ServerLevel level) {
        return blessingFactor(work, level, SimulatedSpan.LIVE);
    }

    /**
     * @param work  the settler's work
     * @param level the level
     * @param span  the simulated work span, {@link SimulatedSpan#LIVE} in live play
     * @return 1 without a blessing, else its factor
     */
    static double blessingFactor(SettlerWork work, ServerLevel level, SimulatedSpan span) {
        return SettlementData.get(level).settlement(work.settler().getSettlementId()).map(settlement -> {
            if (!span.simulated()) {
                long clock = level.getOverworldClockTime();
                return settlement.bonuses().remaining(Bonuses.Kind.WORK_SPEED, clock) > 0 ? Bonuses.Kind.WORK_SPEED.factor() : 1.0;
            }
            return settlement.bonuses().averageFactor(Bonuses.Kind.WORK_SPEED, span.workFrom(), span.workTo());
        }).orElse(1.0);
    }

    /**
     * The share a {@code WorkSpeed} effect of a Service act adds to the speed factor ({@link SpeedBoost}): live that of now,
     * in catch-up the average over the simulated work time.
     *
     * @param work  the settler's work
     * @param level the level
     * @param span  the simulated work span, {@link SimulatedSpan#LIVE} in live play
     * @return 0 without a boost, else its percent as a fraction
     */
    static double speedBoostShare(SettlerWork work, ServerLevel level, SimulatedSpan span) {
        SpeedBoost boost = work.speedBoost();
        return span.simulated() ? boost.averageShare(span.workFrom(), span.workTo()) : boost.share(level.getOverworldClockTime());
    }

    /**
     * The tail every work speed shares.
     *
     * @param base        the base speed of the caller's profile
     * @param bonus       the biome bonus of the station
     * @param speedFactor the factor of {@link #speedFactor}
     * @return the base times the biome speed bonus times the speed factor
     */
    static double withBiome(double base, BiomeBonus bonus, double speedFactor) {
        return base * bonus.speed().value() * speedFactor;
    }
}
