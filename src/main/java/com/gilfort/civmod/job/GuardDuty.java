package com.gilfort.civmod.job;

import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;

/**
 * The rules that make a Guard a settler on duty around the clock (vault {@code Jobs/Guard.md}, "Guards work 24/7"): its
 * day has no end of work and no bedtime, and the duty EXP it earns for every in-game hour. Pure rules; the Guard loop
 * ({@link GuardLoop}) and the Settler Shell apply them.
 */
public final class GuardDuty {

    private GuardDuty() {
    }

    /**
     * @param settler a settler
     * @return whether it holds a Job of the Guard category, so it is on duty day and night
     */
    static boolean holdsGuardJob(Settler settler) {
        return settler.getJobs().stream().anyMatch(job -> job.jobType().category() == JobCategory.GUARD);
    }

    /**
     * The day times of a Guard: it is up from the start of the day and neither ends its work nor goes to bed, so the
     * Schedule never sends it to the Tavern, to bed or to the evening rest.
     *
     * @param settler the settler
     * @param usual   its usual day times
     * @return the day times of a Guard if the settler holds a Guard Job, else the usual ones
     */
    static Schedule.DayTimes dayTimes(Settler settler, Schedule.DayTimes usual) {
        return holdsGuardJob(settler) ? new Schedule.DayTimes(0L, DayClock.DAY_TICKS, DayClock.DAY_TICKS) : usual;
    }

    /**
     * @param ticks    the time on duty
     * @param baseExp  the Job's base EXP
     * @param jobLevel the level of the Guard Post
     * @return the duty EXP: the base EXP times the level of the Guard Post for every in-game hour, rounded down
     */
    public static int dutyExp(long ticks, int baseExp, int jobLevel) {
        return (int) (Math.max(0L, ticks) * baseExp * jobLevel / DayClock.HOUR_TICKS);
    }
}
