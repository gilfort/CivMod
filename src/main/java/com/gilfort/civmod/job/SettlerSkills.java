package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.Map;

import com.gilfort.civmod.api.Job;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Skill levels and EXP of a settler, one per job type (vault {@code Systems/JobSkill Level.md}). Kept when a job is
 * removed or the settler moves to another JobBlock of the same type.
 */
public final class SettlerSkills {

    /**
     * Level and EXP of one job.
     *
     * @param level the level (1–100)
     * @param exp   EXP towards the next level
     */
    public record Skill(int level, int exp) {

        /** Save codec. */
        public static final Codec<Skill> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("level").forGetter(Skill::level),
                Codec.INT.fieldOf("exp").forGetter(Skill::exp)
        ).apply(instance, Skill::new));

        /** Starting skill. */
        public static final Skill START = new Skill(1, 0);
    }

    /** Save codec. */
    public static final Codec<Map<Job, Skill>> CODEC = Codec.unboundedMap(Job.CODEC, Skill.CODEC);

    private final Map<Job, Skill> skills = new HashMap<>();

    /**
     * @param jobType a job
     * @return the skill in that job
     */
    public Skill get(Job jobType) {
        return skills.getOrDefault(jobType, Skill.START);
    }

    /**
     * @param jobType a job
     * @param skill   the skill
     */
    public void set(Job jobType, Skill skill) {
        skills.put(jobType, skill);
    }

    /**
     * @param jobType a job
     * @param cap     the skill cap of the JobBlock
     * @return the level the settler works at (its level, at most the cap)
     */
    public int effectiveLevel(Job jobType, int cap) {
        return Math.min(get(jobType).level(), cap);
    }

    /**
     * Adds EXP; at the cap excess EXP is discarded.
     *
     * @param jobType a job
     * @param amount  the EXP
     * @param cap     the skill cap of the JobBlock
     * @param rules   the skill rules
     * @return the number of levels gained
     */
    public int addExp(Job jobType, int amount, int cap, SkillRules rules) {
        Skill skill = get(jobType);
        int limit = Math.min(cap, SkillRules.MAX_LEVEL);
        if (skill.level() >= limit || amount <= 0) {
            return 0;
        }
        int level = skill.level();
        int exp = skill.exp() + amount;
        while (level < limit && exp >= rules.expToNext(level)) {
            exp -= rules.expToNext(level);
            level++;
        }
        if (level >= limit) {
            exp = 0;
        }
        int gained = level - skill.level();
        skills.put(jobType, new Skill(level, exp));
        return gained;
    }

    /**
     * @return a copy of all skills (saving)
     */
    public Map<Job, Skill> snapshot() {
        return Map.copyOf(skills);
    }

    /**
     * @param saved saved skills
     */
    public void restore(Map<Job, Skill> saved) {
        skills.clear();
        skills.putAll(saved);
    }
}
