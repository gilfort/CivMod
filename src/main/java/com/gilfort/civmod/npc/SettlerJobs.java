package com.gilfort.civmod.npc;

import java.util.ArrayList;
import java.util.List;

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

    private SettlerJobs() {
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
