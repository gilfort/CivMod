package com.gilfort.civmod.api;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

/**
 * The specifics of a Service Job (vault {@code Systems/Job Categories.md}, Service): the {@link ServiceAct}s it plays. The
 * Service loop runs them for every Job whose profile is a {@code ServiceProfile}; a Service Job without one has no loop
 * (the Administrator).
 * <p>
 * The Job's JobBlock is its place: a stationary act is played by the settler standing next to it, within the act's window
 * and the settler's work hours (the work day ends at 17:30 to 18:00, or with the latest window of the Job's acts if that is
 * later). The settler gets the Job's base EXP times the JobBlock level
 * for every act it plays. The Priest and the Tavern Host keep loops of their own and have no profile.
 */
@ApiStatus.Experimental
public interface ServiceProfile extends CategoryProfile {

    @Override
    default JobCategory category() {
        return JobCategory.SERVICE;
    }

    /**
     * @return the acts of the Job, in the order they are checked; at most one stationary act is played at a time (the
     *         first one whose window is open)
     */
    List<ServiceAct> acts();

    /**
     * @param acts the acts
     * @return a profile with those acts
     */
    static ServiceProfile of(ServiceAct... acts) {
        List<ServiceAct> list = List.of(acts);
        return () -> list;
    }
}
