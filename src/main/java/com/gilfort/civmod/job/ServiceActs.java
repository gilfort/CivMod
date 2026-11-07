package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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

    private ServiceActs() {
    }

    /**
     * @param job     a Job
     * @param profile its Service profile
     * @return the acts of the profile with the datapack's numbers of that Job
     */
    static List<ServiceAct> of(Job job, ServiceProfile profile) {
        Map<String, ServiceActNumbers> numbers = JobBalancing.of(job).serviceActs().orElse(Map.of());
        // Checked every tick of a settler standing at its place: without datapack numbers nothing is copied.
        return numbers.isEmpty() ? profile.acts() : resolve(profile.acts(), numbers, problem -> {
        });
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
     * @param dayTime a tick of the day
     * @return the first stationary act whose window is open then
     */
    static Optional<ServiceAct> stationaryAt(List<ServiceAct> acts, long dayTime) {
        return acts.stream().filter(act -> act.kind() == ServiceAct.Kind.STATIONARY && act.isOpen(dayTime)).findFirst();
    }

    /**
     * @param acts    the acts to play
     * @param dayTime a tick of the day
     * @return the first round act whose window is open then
     */
    static Optional<ServiceAct> roundAt(List<ServiceAct> acts, long dayTime) {
        return acts.stream().filter(act -> act.kind() == ServiceAct.Kind.ROUND && act.isOpen(dayTime)).findFirst();
    }

    /**
     * @param job a Job
     * @return the tick at which the last window of its acts ends (datapack numbers included); empty for a Job without a
     *         Service profile. A settler works until then if that is later than the usual end of work.
     */
    public static OptionalInt windowEnd(Job job) {
        if (job.category() != JobCategory.SERVICE) {
            return OptionalInt.empty();
        }
        return job.profile(ServiceProfile.class).map(profile -> of(job, profile).stream().mapToInt(ServiceAct::end).max())
                .orElse(OptionalInt.empty());
    }

    /**
     * @param job        a Service Job
     * @param blockLevel the level of its JobBlock
     * @return the EXP the settler gets for playing one act: the Job's base EXP times the JobBlock level
     */
    public static int exp(Job job, int blockLevel) {
        return job.baseExp() * blockLevel;
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
