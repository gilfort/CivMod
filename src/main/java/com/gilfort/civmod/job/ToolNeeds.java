package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * The tool need of a settler (vault {@code Systems/Needs.md}, decisions of 2026-11-04): one {@link NeedState} per tool
 * Job ({@link ToolStaircase#appliesTo}), judged with the skill of that Job, not with the settler's highest skill. The
 * need works like the others (grace day, downgrade of the work level of that Job, climbing back at once), only the
 * tier it is judged for is the band of that Job's capped skill and the tools it reads are the settler's belt tools of the
 * Job's kind. Kept per JobBlock together with the time of the last search of the collect inventory.
 */
public final class ToolNeeds {

    /** The collect inventory is searched at most once per in-game hour (ticks). */
    static final long SEARCH_INTERVAL = 1000L;

    /** What is kept per tool Job. */
    private static final class Entry {
        private NeedState state = NeedState.START;
        /** The game time the collect inventory may be searched again for a better tool (not saved). */
        private long searchAfter = Long.MIN_VALUE;
    }

    /**
     * One saved entry.
     *
     * @param block the JobBlock of the Job
     * @param state its tool need state
     */
    public record Saved(BlockPos block, NeedState state) {

        public static final Codec<Saved> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("block").forGetter(Saved::block),
                NeedState.CODEC.fieldOf("state").forGetter(Saved::state)
        ).apply(instance, Saved::new));

        /** Save codec of all entries; a save without tool needs starts with none. */
        public static final Codec<List<Saved>> LIST = CODEC.listOf();
    }

    private final Settler settler;
    private final SettlerWork shell;
    private final Map<BlockPos, Entry> entries = new HashMap<>();

    /**
     * @param shell the Settler Shell
     */
    ToolNeeds(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    /**
     * The check of a decision for every tool Job: at wake-up (first check of a day) and the cheap re-check of a Job that
     * can climb. Live play and catch-up take the same step.
     *
     * @param day the day
     */
    void step(long day) {
        Set<BlockPos> current = new HashSet<>();
        for (SettlerJob job : settler.getJobs()) {
            Optional<ResourceCollectionProfile> profile = toolProfile(job);
            if (profile.isEmpty()) {
                continue;
            }
            current.add(job.jobBlock());
            Entry entry = entries.computeIfAbsent(job.jobBlock(), block -> new Entry());
            int skill = shell.cappedLevel(job);
            int tier = NeedState.tierOf(skill);
            boolean morning = day > entry.state.checkedDay();
            if (!morning && entry.state.settled(tier)) {
                continue;
            }
            int highestMet = highestMet(profile.get(), skill);
            entry.state = morning ? entry.state.morning(day, tier, highestMet) : entry.state.climb(tier, highestMet);
        }
        entries.keySet().retainAll(current);
    }

    /**
     * Re-checks one Job at once, after the belt changed (a tool swap in catch-up): it climbs back if the need is met now.
     *
     * @param job the Job
     */
    void recheck(SettlerJob job) {
        Entry entry = entries.get(job.jobBlock());
        Optional<ResourceCollectionProfile> profile = toolProfile(job);
        if (entry == null || profile.isEmpty()) {
            return;
        }
        int skill = shell.cappedLevel(job);
        entry.state = entry.state.climb(NeedState.tierOf(skill), highestMet(profile.get(), skill));
    }

    /**
     * @return the highest band whose tool requirement the belt meets; a settler without any usable tool is not judged here
     *         (it has the status "No tool" and falls back to its next Job), so every band counts as met
     */
    private int highestMet(ResourceCollectionProfile profile, int skill) {
        ToolStaircase.Rule rule = ToolStaircase.forSkill(skill);
        List<ItemStack> tools = SettlerTools.belt(settler).stream().filter(stack -> !stack.isEmpty() && profile.isTool(stack) && rule.allows(stack)).toList();
        return tools.isEmpty() ? NeedState.MAX_TIER : ToolStaircase.highestMet(skill, tools);
    }

    private static Optional<ResourceCollectionProfile> toolProfile(SettlerJob job) {
        return job.jobType().profile(ResourceCollectionProfile.class).filter(ToolStaircase::appliesTo);
    }

    /**
     * @param job   a Job
     * @param level the capped target level of the Job
     * @return the level the settler works at in that Job: lower when its tool does not meet the requirement
     */
    int limit(SettlerJob job, int level) {
        Entry entry = entries.get(job.jobBlock());
        return entry == null ? level : entry.state.limit(level);
    }

    /**
     * @param job  the Job
     * @param time the game time
     * @return whether the collect inventory may be searched for a better tool now (no search found nothing within the
     *         last hour)
     */
    boolean searchDue(SettlerJob job, long time) {
        Entry entry = entries.get(job.jobBlock());
        return entry == null || time >= entry.searchAfter;
    }

    /**
     * A search of the collect inventory found no better tool (or the swap failed): the next one waits an hour. A search
     * that found one is not recorded, so the settler can walk there over several decisions.
     *
     * @param job  the Job
     * @param time the game time
     */
    void searched(SettlerJob job, long time) {
        entries.computeIfAbsent(job.jobBlock(), block -> new Entry()).searchAfter = time + SEARCH_INTERVAL;
    }

    /** How a tool Job's tool need looks to the player. */
    public enum View {
        /** The settler has a usable tool and the need is met. */
        HAPPY,
        /** It has a usable tool that is too weak for its skill: the need is unmet. */
        WEAK,
        /** It has no usable tool: the status "No tool" speaks, the tool need does not judge it. */
        NONE
    }

    /**
     * @param job a Job
     * @return the tool band (1-{@value NeedState#MAX_TIER}) of the settler's skill in that Job if it is a tool Job, else 0
     */
    int band(SettlerJob job) {
        return toolProfile(job).isPresent() ? NeedState.tierOf(shell.cappedLevel(job)) : 0;
    }

    /**
     * @param job a tool Job
     * @return whether its tool need is unmet: on a grace day, or the work level is lowered
     */
    boolean unmet(SettlerJob job) {
        Entry entry = entries.get(job.jobBlock());
        return entry != null && (entry.state.inGrace() || entry.state.workTier() < NeedState.MAX_TIER);
    }

    /**
     * @param job a tool Job
     * @return how the tool need shows
     */
    View view(SettlerJob job) {
        if (shell.tool(job).isEmpty()) {
            return View.NONE;
        }
        return unmet(job) ? View.WEAK : View.HAPPY;
    }

    /**
     * @return the Needs tab rows: one per tool Job, in the tier of the Job's skill
     */
    List<NeedsReport.Row> rows() {
        List<NeedsReport.Row> rows = new ArrayList<>();
        for (SettlerJob job : settler.getJobs()) {
            Optional<ResourceCollectionProfile> profile = toolProfile(job);
            if (profile.isPresent()) {
                rows.add(ToolTexts.row(NeedState.tierOf(shell.cappedLevel(job)), job.jobType().displayName(),
                        new ItemStack(profile.get().toolIcon()), view(job)));
            }
        }
        return rows;
    }

    /**
     * @return the states to save
     */
    List<Saved> snapshot() {
        return entries.entrySet().stream().map(entry -> new Saved(entry.getKey(), entry.getValue().state)).toList();
    }

    /**
     * @param saved the saved states
     */
    void restore(List<Saved> saved) {
        entries.clear();
        for (Saved one : saved) {
            Entry entry = new Entry();
            entry.state = one.state();
            entries.put(one.block(), entry);
        }
    }
}
