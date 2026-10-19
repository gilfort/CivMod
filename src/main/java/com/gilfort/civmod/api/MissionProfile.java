package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * The specifics of a Mission Job (vault {@code Systems/Mission Jobs.md}). Mission Jobs have no JobBlock of their own:
 * they are Specializations of the Adventurer ({@link CivModJobs#ADVENTURER}), offered by the Mission Board from level 2.
 * Which missions a Mission Job may take is not part of the profile: a mission names its Jobs in the datapack
 * ({@code data/<namespace>/civmod/mission/<name>.json}, field {@code jobs}).
 * <p>
 * Every hook is neutral by default; a Mission Job without a profile of its own gets {@link #NEUTRAL}. The outcome
 * chances are computed from the mission's recommended level and the settler's skill first, then passed through the
 * hooks; the results are clamped to {@code 0..1}, and the death chance never exceeds {@code 1 − success}.
 */
@ApiStatus.Experimental
public interface MissionProfile extends CategoryProfile {

    /** The profile of a Mission Job that declares none: every hook unchanged. */
    MissionProfile NEUTRAL = new MissionProfile() {
    };

    @Override
    default JobCategory category() {
        return JobCategory.MISSION;
    }

    /**
     * Modifies the success chance of a mission.
     *
     * @param chance           the success chance from the outcome formula
     * @param recommendedLevel the mission's recommended level
     * @param skill            the settler's effective skill level in this Job
     * @return the success chance to use (unchanged by default)
     */
    default double successChance(double chance, int recommendedLevel, int skill) {
        return chance;
    }

    /**
     * Modifies the death chance of a mission.
     *
     * @param chance           the death chance from the outcome formula
     * @param recommendedLevel the mission's recommended level
     * @param skill            the settler's effective skill level in this Job
     * @return the death chance to use (unchanged by default)
     */
    default double deathChance(double chance, int recommendedLevel, int skill) {
        return chance;
    }
}
