package com.gilfort.civmod.npc;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.job.JobCategory;

/**
 * Edits of a settler's job priority list, applied identically on client and server (menu button clicks).
 */
public final class SettlerJobs {

    /** Button id base: move job {@code i} up. */
    public static final int MOVE_UP = 100;
    /** Button id base: move job {@code i} down. */
    public static final int MOVE_DOWN = 200;
    /** Button id base: remove job {@code i}. */
    public static final int REMOVE = 300;
    /** Upper bound of the job button id range. */
    public static final int END = 400;
    /** Button id base: set the Mission Job to offered Mission Job {@code i} ({@link com.gilfort.civmod.menu.SettlerInfo#missionJobs()}). */
    public static final int SET_MISSION_JOB = 400;
    /** Upper bound of the Mission Job button id range. */
    public static final int SET_MISSION_JOB_END = 500;

    private SettlerJobs() {
    }

    /**
     * Sets the settler's Mission Job (vault {@code Systems/Mission Jobs.md}): the job of its Mission Board entry is
     * replaced in place, keeping board and priority.
     *
     * @param jobs       the jobs in priority order (Laborer excluded)
     * @param missionJob the new Mission Job
     * @return the edited list (unchanged without a Mission Job)
     */
    public static List<SettlerJob> withMissionJob(List<SettlerJob> jobs, Job missionJob) {
        return jobs.stream().map(job -> job.jobType().category() == JobCategory.MISSION ? new SettlerJob(missionJob, job.jobBlock()) : job)
                .toList();
    }

    /**
     * Applies a job button to a job list.
     *
     * @param jobs     the jobs in priority order (Laborer excluded)
     * @param buttonId the menu button id
     * @return the edited list, or the same list if the button does not apply
     */
    public static List<SettlerJob> apply(List<SettlerJob> jobs, int buttonId) {
        List<SettlerJob> result = new ArrayList<>(jobs);
        if (buttonId >= MOVE_UP && buttonId < MOVE_DOWN) {
            int index = buttonId - MOVE_UP;
            if (index > 0 && index < result.size()) {
                result.add(index - 1, result.remove(index));
            }
        } else if (buttonId >= MOVE_DOWN && buttonId < REMOVE) {
            int index = buttonId - MOVE_DOWN;
            if (index >= 0 && index < result.size() - 1) {
                result.add(index + 1, result.remove(index));
            }
        } else if (buttonId >= REMOVE && buttonId < END) {
            int index = buttonId - REMOVE;
            if (index >= 0 && index < result.size()) {
                result.remove(index);
            }
        }
        return List.copyOf(result);
    }
}
