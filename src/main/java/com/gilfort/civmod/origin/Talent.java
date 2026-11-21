package com.gilfort.civmod.origin;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.chronicle.Diary;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SkillRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The talent of a settler (vault {@code Systems/Settler Origin.md}): the upbringing favours one or two Base Jobs, and the
 * Specializations of those. A favoured Job gives extra EXP, and the first level-up in one confirms the talent to the player. The
 * talent is a property of the upbringing; whether the player may see it is the {@link com.gilfort.civmod.chronicle.Chronicle}'s flag.
 * A settler without an origin has no talent.
 * <p>
 * {@link #gainExp} is the one place where a settler gains skill EXP, so the bonus and the confirmation apply to every EXP path, live
 * and caught up.
 */
public final class Talent {

    /**
     * What an EXP gain did.
     *
     * @param levels    the levels gained
     * @param exp       the whole EXP points granted: the amount with the bonus and the carried fraction
     * @param confirmed whether this gain confirmed the talent for the first time (the caller shows the thought bubble)
     */
    public record Gain(int levels, int exp, boolean confirmed) {
    }

    private Talent() {
    }

    /**
     * @param settler a settler
     * @return the Base Jobs of its upbringing, empty if it has none
     */
    public static List<Job> baseJobs(Settler settler) {
        return upbringingJobs(settler).stream().flatMap(id -> ModJobs.REGISTRY.getOptional(id).stream()).toList();
    }

    /**
     * @param settler a settler
     * @param job     a Job
     * @return whether the Job is one of the upbringing's Base Jobs or a Specialization of one
     */
    public static boolean favours(Settler settler, Job job) {
        List<Identifier> favoured = upbringingJobs(settler);
        if (favoured.isEmpty()) {
            return false;
        }
        Optional<Job> candidate = Optional.of(job);
        while (candidate.isPresent()) {
            if (favoured.contains(candidate.get().id())) {
                return true;
            }
            candidate = candidate.get().parent();
        }
        return false;
    }

    /**
     * Gives a settler skill EXP: the bonus of a favoured Job is added, and the first level-up in one confirms the talent with a diary
     * entry. The confirmation does not depend on whether anybody watches; the caller shows the thought bubble.
     *
     * @param settler the settler
     * @param job     the Job the EXP is for
     * @param amount  the EXP of the action, without the bonus
     * @param cap     the skill cap of the Job's JobBlock
     * @return the levels gained, the EXP granted and whether the talent was confirmed by this gain
     */
    public static Gain gainExp(Settler settler, Job job, int amount, int cap) {
        SkillRules rules = SkillRules.get();
        boolean favoured = favours(settler, job);
        SettlerSkills.Added added = settler.getSkills().addExp(job, amount, cap, rules, favoured ? rules.talentBonusPercent() : 0);
        return new Gain(added.levels(), added.exp(), added.levels() > 0 && favoured && confirm(settler, job));
    }

    /**
     * Builds the diary text of the confirmation: "I think working as a Lumberjack really suits me."
     *
     * @param params the id of the Job
     * @return the text
     */
    public static Component confirmationText(List<String> params) {
        String saved = params.isEmpty() ? "" : params.get(0);
        Component job = Optional.ofNullable(Identifier.tryParse(saved)).flatMap(ModJobs.REGISTRY::getOptional).<Component>map(Job::indefiniteName)
                .orElseGet(() -> Component.literal(saved));
        return Component.translatable("diary.civmod.talent", job);
    }

    private static boolean confirm(Settler settler, Job job) {
        if (settler.getPast().chronicle().talentConfirmed()) {
            return false;
        }
        settler.getPast().setChronicle(settler.getPast().chronicle().withTalentConfirmed());
        Diary.add(settler, ModDiaryEntryTypes.TALENT.get(), List.of(job.id().toString()));
        return true;
    }

    private static List<Identifier> upbringingJobs(Settler settler) {
        return settler.getPast().origin().flatMap(SettlerOrigin::upbringing)
                .flatMap(id -> OriginData.race(settler.getRace()).flatMap(race -> race.upbringing(id)))
                .map(OriginEntry::jobs).orElse(List.of());
    }
}
