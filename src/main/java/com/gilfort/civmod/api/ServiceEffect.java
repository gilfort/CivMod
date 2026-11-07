package com.gilfort.civmod.api;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

/**
 * What a {@link ServiceAct} does to its listeners. CivMod's own effects are {@link Entertain}, {@link WorkSpeed} and
 * {@link MobEffect}; an addon writes more by implementing this interface.
 * <p>
 * An effect names the {@link Targets} it is for, and the Service loop hands it only those listeners: the lists of the
 * {@link ServiceContext} it gets hold the settlers and players within the act's radius, and the list of a group the effect
 * does not name is empty. The loop scans for listeners once per act, not per tick; an effect does its work in
 * {@link #apply}.
 * <p>
 * An effect has no state of its own: the same instance serves every settler and every day.
 */
@ApiStatus.Experimental
public interface ServiceEffect {

    /** Who an effect is for. */
    enum Targets {
        /** The settlers of the settlement within the radius. */
        SETTLERS,
        /** The players within the radius. */
        PLAYERS,
        /** Both. */
        BOTH;

        /**
         * @return whether settlers are targets
         */
        public boolean settlers() {
            return this != PLAYERS;
        }

        /**
         * @return whether players are targets
         */
        public boolean players() {
            return this != SETTLERS;
        }
    }

    /**
     * @return who the effect is for
     */
    Targets targets();

    /**
     * Does the effect once for the listeners of an act.
     *
     * @param context the act and its listeners
     */
    void apply(ServiceContext context);

    /**
     * The numbers a datapack may change ({@code service_acts} in {@code civmod_jobs/<job>.json}): name to value. Only the
     * names listed here can be set; the order of the effects of an act is fixed by the profile.
     *
     * @return the numbers of this effect, none by default
     */
    default Map<String, Double> numbers() {
        return Map.of();
    }

    /**
     * @param values numbers by name, as {@link #numbers()} names them; names the effect does not know are ignored
     * @return the effect with those numbers instead of its own (itself by default)
     */
    default ServiceEffect withNumbers(Map<String, Double> values) {
        return this;
    }
}
