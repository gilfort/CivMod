package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Snapshot of a settler shown in its GUI; sent to the client when the menu opens.
 *
 * @param name           the settler name
 * @param status         the status text
 * @param homeless       whether the settler belongs to no settlement
 * @param settlementName the settlement name (empty if homeless)
 * @param jobs           the assigned jobs in priority order (Laborer excluded)
 * @param jobStates      status and buffer per job, aligned with {@code jobs}
 * @param bed            where the settler sleeps
 * @param food           food level, saturation and appetite
 * @param mission        the mission the settler is away on or waits to leave for (empty text if none)
 */
public record SettlerInfo(Component name, Component status, boolean homeless, Component settlementName, List<SettlerJob> jobs,
        List<JobState> jobStates, Component bed, FoodInfo food, Component mission) {

    /**
     * Food state shown in the overview.
     *
     * @param level      the food level (0–20)
     * @param saturation the saturation buffer
     * @param appetite   the appetite multiplier
     */
    public record FoodInfo(int level, float saturation, float appetite) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, FoodInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FoodInfo::level,
                ByteBufCodecs.FLOAT, FoodInfo::saturation,
                ByteBufCodecs.FLOAT, FoodInfo::appetite,
                FoodInfo::new);
    }

    /**
     * Status and buffer of a job.
     *
     * @param status   the job status
     * @param text     the status as text, naming what it is about ("Collecting – Lumberjack")
     * @param carry    a Deliveryman's carry capacity in stacks, 0 for other jobs (their buffer holds
     *                 {@link JobBuffer#CAPACITY} items)
     * @param buffered the number of buffered items
     * @param preview  the buffered stacks (up to 18, a Deliveryman's whole load)
     * @param skill    level and EXP in this job
     * @param switchTo the Jobs the job switcher offers instead at the same JobBlock (empty: no switcher)
     */
    public record JobState(JobStatus status, Component text, int carry, int buffered, List<ItemStack> preview,
            SkillInfo skill, List<Job> switchTo) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, JobState> STREAM_CODEC = StreamCodec.composite(
                JobStatus.STREAM_CODEC, JobState::status,
                ComponentSerialization.STREAM_CODEC, JobState::text,
                ByteBufCodecs.VAR_INT, JobState::carry,
                ByteBufCodecs.VAR_INT, JobState::buffered,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), JobState::preview,
                SkillInfo.STREAM_CODEC, JobState::skill,
                Job.STREAM_CODEC.apply(ByteBufCodecs.list()), JobState::switchTo,
                JobState::new);

        /**
         * @param status   the job status
         * @param text     the status as text
         * @param carry    a Deliveryman's carry capacity in stacks, 0 for other jobs
         * @param buffered the number of buffered items
         * @param preview  the buffered stacks
         * @param skill    level and EXP in this job
         * @param switchTo the Jobs the job switcher offers instead
         */
        public JobState {
            preview = List.copyOf(preview);
            switchTo = List.copyOf(switchTo);
        }

        /**
         * @param job the Job switched to
         * @param old the Job switched from
         * @return the state after the switch: idle, the old Job offered instead of the new one
         */
        JobState switched(Job job, Job old) {
            List<Job> options = new ArrayList<>(switchTo);
            options.replaceAll(option -> option == job ? old : option);
            return new JobState(JobStatus.IDLE, JobStatus.IDLE.displayName(), 0, 0, List.of(), SkillInfo.NONE, options);
        }
    }

    /**
     * Skill of a job.
     *
     * @param level      the skill level
     * @param exp        EXP towards the next level
     * @param needed     EXP needed for the next level
     * @param effective  the level the settler works at (limited by the JobBlock)
     * @param blockLevel the JobBlock level
     */
    public record SkillInfo(int level, int exp, int needed, int effective, int blockLevel) {

        /** No skill data. */
        public static final SkillInfo NONE = new SkillInfo(1, 0, 1, 1, 1);

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, SkillInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SkillInfo::level,
                ByteBufCodecs.VAR_INT, SkillInfo::exp,
                ByteBufCodecs.VAR_INT, SkillInfo::needed,
                ByteBufCodecs.VAR_INT, SkillInfo::effective,
                ByteBufCodecs.VAR_INT, SkillInfo::blockLevel,
                SkillInfo::new);

        /**
         * @return whether the settler works below its skill
         */
        public boolean limited() {
            return effective < level;
        }

        /**
         * @return whether the JobBlock cap is reached (no more EXP)
         */
        public boolean atCap() {
            return level >= com.gilfort.civmod.settlement.SettlementRules.skillCap(blockLevel);
        }
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, SettlerInfo> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.STREAM_CODEC, SettlerInfo::name,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::status,
            ByteBufCodecs.BOOL, SettlerInfo::homeless,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::settlementName,
            SettlerJob.STREAM_CODEC.apply(ByteBufCodecs.list()), SettlerInfo::jobs,
            JobState.STREAM_CODEC.apply(ByteBufCodecs.list()), SettlerInfo::jobStates,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::bed,
            FoodInfo.STREAM_CODEC, SettlerInfo::food,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::mission,
            SettlerInfo::new);

    /**
     * @param settler a settler (server side)
     * @return its current info
     */
    public static SettlerInfo of(Settler settler) {
        Component settlementName = Component.empty();
        Component bed = Component.translatable("civmod.gui.settler.bed.none");
        if (settler.level() instanceof ServerLevel level && !settler.isHomeless()) {
            java.util.Optional<Settlement> settlement = SettlementData.get(level).settlement(settler.getSettlementId());
            settlementName = settlement.map(Settlement::displayName).orElse(Component.empty());
            java.util.Optional<net.minecraft.core.BlockPos> bedPos = settlement.flatMap(s -> s.bedOf(settler.getUUID()));
            if (bedPos.isPresent()) {
                bed = Component.literal(bedPos.get().toShortString());
            } else if (settler.getWork().restPlace().isPresent() || settler.isSleepingWithoutBed()) {
                bed = Component.translatable("civmod.gui.settler.bed.straw");
            }
        }
        List<JobState> states = settler.getJobs().stream().map(job -> {
            JobBuffer buffer = settler.getWork().buffer(job.jobBlock());
            int carry = settler.getWork().carryStacks(job);
            List<Job> switchTo = settler.level() instanceof ServerLevel level ? SettlementWorld.switchOptions(level, job.jobBlock(), settler) : List.of();
            return new JobState(settler.getWork().status(job.jobBlock()), settler.getWork().statusText(job.jobBlock()), carry, buffer.count(),
                    buffer.stacks().stream().limit(Math.max(18, carry)).map(ItemStack::copy).toList(), skillInfo(settler, job), switchTo);
        }).toList();
        com.gilfort.civmod.food.SettlerFood food = settler.getFood();
        float appetite = food.appetite(settler.getRandom(), com.gilfort.civmod.config.CivModConfig.APPETITE_MIN.get(),
                com.gilfort.civmod.config.CivModConfig.APPETITE_MAX.get());
        return new SettlerInfo(settler.getName(), settler.statusText(), settler.isHomeless(), settlementName, settler.getJobs(), states, bed,
                new FoodInfo(food.level(), food.saturation(), appetite),
                settler.getWork().missionStatus().orElse(Component.empty()));
    }

    /**
     * @return the settler's Mission Job entry, if it holds one
     */
    public java.util.Optional<SettlerJob> missionJob() {
        return jobs.stream().filter(job -> job.jobType().category() == JobCategory.MISSION).findFirst();
    }

    private static SkillInfo skillInfo(Settler settler, SettlerJob job) {
        com.gilfort.civmod.job.SettlerSkills.Skill skill = settler.getSkills().get(job.jobType());
        return new SkillInfo(skill.level(), skill.exp(), com.gilfort.civmod.job.SkillRules.get().expToNext(skill.level()),
                settler.getWork().effectiveLevel(job), settler.getWork().jobBlockLevel(job));
    }

    /**
     * @param jobs the new job list
     * @return a copy with the given jobs
     */
    public SettlerInfo withJobs(List<SettlerJob> jobs) {
        List<JobState> states = jobs.stream().map(job -> {
            int index = this.jobs.indexOf(job);
            return index >= 0 && index < jobStates.size() ? jobStates.get(index) : new JobState(JobStatus.IDLE, JobStatus.IDLE.displayName(), 0, 0, List.of(),
                    SkillInfo.NONE, List.of());
        }).toList();
        return new SettlerInfo(name, status, homeless, settlementName, List.copyOf(jobs), states, bed, food, mission);
    }

    /**
     * @param index the index of a job
     * @param job   the Job it switched to (same JobBlock)
     * @return a copy with that job switched; its state idle until the window is opened again
     */
    public SettlerInfo withSwitched(int index, Job job) {
        List<SettlerJob> switchedJobs = new ArrayList<>(jobs);
        List<JobState> states = new ArrayList<>(jobStates);
        SettlerJob old = switchedJobs.get(index);
        switchedJobs.set(index, new SettlerJob(job, old.jobBlock()));
        if (index < states.size()) {
            states.set(index, states.get(index).switched(job, old.jobType()));
        }
        return new SettlerInfo(name, status, homeless, settlementName, List.copyOf(switchedJobs), List.copyOf(states), bed, food, mission);
    }
}
