package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.ServiceAct;
import com.gilfort.civmod.api.ServiceEffect;
import com.gilfort.civmod.api.ServiceProfile;

/**
 * The Service acts of a Job as they are played: the profile's acts with the numbers of the datapack
 * ({@link ServiceActNumbers}) put in, and the choice of the act a window opens.
 */
public final class ServiceActs {

    /** The acts with datapack numbers by Job; emptied when the datapacks reload ({@link #reloaded}). */
    private static final Map<Job, List<ServiceAct>> RESOLVED = new ConcurrentHashMap<>();

    private ServiceActs() {
    }

    /**
     * @param job     a Job
     * @param profile its Service profile
     * @return the acts of the profile with the datapack's numbers of that Job
     */
    static List<ServiceAct> of(Job job, ServiceProfile profile) {
        // Asked every tick of a settler standing at its place: the numbers are put in once per Job and reload.
        if (JobBalancing.of(job).serviceActs().isEmpty()) {
            return profile.acts();
        }
        return RESOLVED.computeIfAbsent(job, key -> resolve(profile.acts(), JobBalancing.of(key).serviceActs().orElseThrow(), problem -> {
        }));
    }

    /** Forgets the acts put together so far: called when the datapack numbers of the Jobs were loaded anew. */
    static void reloaded() {
        RESOLVED.clear();
    }

    /**
     * Puts datapack numbers into the declared acts. A change that cannot hold (an unknown act, another number of effects, a
     * window that is empty or leaves the day) is reported and ignored as a whole for that act.
     *
     * @param declared the acts of the profile
     * @param numbers  the numbers by act id
     * @param problems receives a message for each change that was ignored
     * @return the acts to play, in the order of the profile
     */
    public static List<ServiceAct> resolve(List<ServiceAct> declared, Map<String, ServiceActNumbers> numbers, Consumer<String> problems) {
        numbers.keySet().stream().filter(id -> declared.stream().noneMatch(act -> act.id().equals(id)))
                .forEach(id -> problems.accept("service_acts names no act " + id));
        List<ServiceAct> resolved = new ArrayList<>();
        for (ServiceAct act : declared) {
            ServiceActNumbers changes = numbers.get(act.id());
            resolved.add(changes == null ? act : change(act, changes, problems));
        }
        return List.copyOf(resolved);
    }

    private static ServiceAct change(ServiceAct act, ServiceActNumbers changes, Consumer<String> problems) {
        try {
            ServiceAct changed = act.withWindow(changes.start().orElse(act.start()), changes.end().orElse(act.end()))
                    .withRadius(changes.radius().orElse(act.radius()));
            if (changes.effects().isPresent()) {
                List<Map<String, Double>> values = changes.effects().get();
                if (values.size() != act.effects().size()) {
                    throw new IllegalArgumentException("effects lists " + values.size() + " entries, the act has " + act.effects().size());
                }
                List<ServiceEffect> effects = new ArrayList<>();
                for (int i = 0; i < values.size(); i++) {
                    effects.add(act.effects().get(i).withNumbers(values.get(i)));
                }
                changed = changed.withEffects(effects);
            }
            return changed;
        } catch (IllegalArgumentException e) {
            problems.accept("service_acts of " + act.id() + " ignored: " + e.getMessage());
            return act;
        }
    }

    /**
     * @param acts    the acts to play
     * @param kind    the kind of act
     * @param dayTime a tick of the day
     * @return the first act of that kind whose window is open then
     */
    static Optional<ServiceAct> openAt(List<ServiceAct> acts, ServiceAct.Kind kind, long dayTime) {
        return acts.stream().filter(act -> act.kind() == kind && act.isOpen(dayTime)).findFirst();
    }

    /**
     * Fits the acts of a settler's Jobs (datapack numbers included) into its day (vault {@code Systems/Job Categories.md},
     * Decisions of 2026-11-07 and 2026-11-09). A stationary act that opens at or after the usual end of work is an evening
     * appointment: work ends as usual and the settler is called to the act when it opens (the first one with several). Any
     * other window that ends after the usual end of work keeps the settler at work until it closes.
     *
     * @param jobs  the settler's Jobs
     * @param usual the settler's times without the acts
     * @return the times with the acts fitted in; the same times without a Job with a Service profile
     */
    public static Schedule.DayTimes dayTimes(List<Job> jobs, Schedule.DayTimes usual) {
        Schedule.DayTimes times = usual;
        for (Job job : jobs) {
            if (job.category() != JobCategory.SERVICE) {
                continue;
            }
            for (ServiceAct act : job.profile(ServiceProfile.class).map(profile -> of(job, profile)).orElse(List.of())) {
                if (act.kind() == ServiceAct.Kind.STATIONARY && act.start() >= usual.workEnd()) {
                    times = times.withAppointment(new Schedule.Appointment(act.start(), act.end()));
                } else if (act.end() > times.workEnd()) {
                    times = times.withWorkEnd(act.end());
                }
            }
        }
        return times;
    }

    /**
     * Reports the datapack numbers of a Job that cannot hold, when the files are loaded.
     *
     * @param job       the Job
     * @param balancing its merged datapack numbers
     * @param problems  receives a message for each number that will be ignored
     */
    static void validate(Job job, JobBalancing balancing, Consumer<String> problems) {
        if (balancing.serviceActs().isEmpty()) {
            return;
        }
        Optional<ServiceProfile> profile;
        try {
            profile = job.profile(ServiceProfile.class);
        } catch (IllegalStateException e) {
            return;
        }
        if (profile.isEmpty()) {
            problems.accept("service_acts of a Job without a Service profile");
            return;
        }
        resolve(profile.get().acts(), balancing.serviceActs().get(), problems);
    }
}
