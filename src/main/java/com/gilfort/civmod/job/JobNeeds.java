package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.StationTypes;
import com.mojang.datafixers.util.Either;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;

/**
 * What one Job needs at a JobBlock, for the job blocks of the Station Types tab (vault {@code Systems/JobBlock.md},
 * decision of 2026-11-15): its station, its tool and its extra station, each with a tick when it is there. Worked out once
 * when the window opens; there is no live update.
 *
 * @param job     the Job
 * @param station the station its main recipe type works at
 * @param tool    the tool its skill asks for
 * @param extra   the station extra that raises its job factor
 */
public record JobNeeds(Job job, Line station, Line tool, Line extra) {

    /** The skill level the tool line is judged for when no settler works the Job at the block. */
    private static final int NEW_SETTLER_SKILL = 1;

    /** Empty block tags already logged, so a window opened again does not repeat the warning. */
    private static final Set<Identifier> EMPTY_TAGS_LOGGED = ConcurrentHashMap.newKeySet();

    /** How one need stands. */
    public enum State {
        /** The Job needs nothing of this kind. */
        NOT_NEEDED,
        /** It is there. */
        PRESENT,
        /** It is missing. */
        MISSING;

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM_CODEC = NeoForgeStreamCodecs.enumCodec(State.class);
    }

    /**
     * One line of a job block.
     *
     * @param text  the line ("Station: Crafting Table")
     * @param icon  the item shown on it, empty for none
     * @param state whether it is there
     */
    public record Line(Component text, ItemStack icon, State state) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Line::text,
                ItemStack.OPTIONAL_STREAM_CODEC, Line::icon,
                State.STREAM_CODEC, Line::state,
                Line::new);

        private static Line none(String key) {
            return new Line(Component.translatable(key + ".none"), ItemStack.EMPTY, State.NOT_NEEDED);
        }

        /** @return whether the need is there ({@link State#PRESENT}) */
        public boolean present() {
            return state == State.PRESENT;
        }
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, JobNeeds> STREAM_CODEC = StreamCodec.composite(
            Job.STREAM_CODEC, JobNeeds::job,
            Line.STREAM_CODEC, JobNeeds::station,
            Line.STREAM_CODEC, JobNeeds::tool,
            Line.STREAM_CODEC, JobNeeds::extra,
            JobNeeds::new);

    /**
     * The needs of the Base Job and of every Specialization it can have at a JobBlock; the window shows the ones that are
     * unlocked, so unlocking an item needs no second read.
     *
     * @param level the level
     * @param pos   the JobBlock position
     * @return one entry per Job, the Base Job first, empty when the position is no JobBlock of a settlement
     */
    public static List<JobNeeds> of(ServerLevel level, BlockPos pos) {
        Optional<JobBlockSite> site = JobBlockSite.at(level, pos);
        if (site.isEmpty()) {
            return List.of();
        }
        Job base = site.get().block().jobType();
        List<Job> jobs = new ArrayList<>(List.of(base));
        jobs.addAll(StationTypes.specializations(base));
        List<JobNeeds> needs = new ArrayList<>();
        for (Job job : jobs) {
            needs.add(of(level, site.get(), job));
        }
        return List.copyOf(needs);
    }

    private static JobNeeds of(ServerLevel level, JobBlockSite site, Job job) {
        Optional<BlockPos> station = nearestStation(level, site, job);
        return new JobNeeds(job, stationLine(job, station), toolLine(level, site, job), extraLine(level, job, station));
    }

    // ---------------------------------------------------------------- station

    private static Optional<TagKey<Block>> stationTag(Job job) {
        return job.profile(ProducingProfile.class).map(profile -> profile.station(profile.recipeTypes().getFirst()));
    }

    private static Optional<BlockPos> nearestStation(ServerLevel level, JobBlockSite site, Job job) {
        return stationTag(job).flatMap(tag -> ProducingLoop.stationsAround(level, site, tag).stream().findFirst());
    }

    private static Line stationLine(Job job, Optional<BlockPos> found) {
        Optional<TagKey<Block>> tag = stationTag(job);
        if (tag.isEmpty()) {
            return Line.none("civmod.gui.station_types.need.station");
        }
        return firstBlock(tag.get())
                .map(shown -> line("civmod.gui.station_types.need.station", shown, found.isPresent()))
                .orElseGet(() -> Line.none("civmod.gui.station_types.need.station"));
    }

    // ---------------------------------------------------------------- tool

    /** The worker whose tool rule counts for a tool line: the one with the highest allowed tier. */
    private record Worker(ToolStaircase.Rule rule, boolean carries) {
    }

    /**
     * The tool a Resource Collection Job asks for first: the tier the tool supply orders ({@link ToolStaircase#askedTier}) for
     * the Job's worker at this block with the highest allowed tier, or for a new settler without a worker. Ticked when that
     * worker carries an allowed tool. A Job outside the tool staircase names none.
     */
    private static Line toolLine(ServerLevel level, JobBlockSite site, Job job) {
        Optional<ResourceCollectionProfile> profile = job.profile(ResourceCollectionProfile.class).filter(ToolStaircase::appliesTo);
        if (profile.isEmpty()) {
            return Line.none("civmod.gui.station_types.need.tool");
        }
        Worker worker = strongestWorker(level, site, job, profile.get())
                .orElseGet(() -> new Worker(ToolStaircase.rule(profile.get(), NEW_SETTLER_SKILL), false));
        int tier = ToolStaircase.askedTier(ToolOrders.kindOf(profile.get()), worker.rule());
        String tierKey = tier == ToolStaircase.NO_TIER ? "civmod.gui.station_types.tier.none" : "civmod.gui.station_types.tier." + tier;
        return new Line(Component.translatable("civmod.gui.station_types.need.tool", Component.translatable(tierKey)),
                new ItemStack(profile.get().toolIcon()), worker.carries() ? State.PRESENT : State.MISSING);
    }

    private static Optional<Worker> strongestWorker(ServerLevel level, JobBlockSite site, Job job, ResourceCollectionProfile profile) {
        Optional<Worker> strongest = Optional.empty();
        for (SettlementBlock.Assignee assignee : site.block().assignees()) {
            if (assignee.job() != job || !(level.getEntity(assignee.id()) instanceof Settler settler)) {
                continue;
            }
            for (SettlerJob held : settler.getJobs()) {
                ToolStaircase.Rule rule = settler.getWork().toolRule(held, profile);
                boolean here = held.jobType() == job && held.jobBlock().equals(site.pos());
                if (here && strongest.map(worker -> rule.allowed() > worker.rule().allowed()).orElse(true)) {
                    strongest = Optional.of(new Worker(rule, settler.getWork().tool(held).isPresent()));
                }
            }
        }
        return strongest;
    }

    // ---------------------------------------------------------------- extra station

    private static Line extraLine(ServerLevel level, Job job, Optional<BlockPos> station) {
        Optional<StationExtra> extra = JobBalancing.of(job).stationExtras().flatMap(extras -> extras.stream()
                .max(Comparator.comparingDouble(StationExtra::jobFactor)));
        if (extra.isEmpty()) {
            return Line.none("civmod.gui.station_types.need.extra");
        }
        Either<TagKey<Block>, Block> entry = extra.get().blocks().getFirst();
        Optional<Block> shown = entry.map(JobNeeds::firstBlock, Optional::of);
        boolean near = station.isPresent() && StationExtras.near(level, station.get(), extra.get());
        return shown.map(block -> line("civmod.gui.station_types.need.extra", block, near))
                .orElseGet(() -> Line.none("civmod.gui.station_types.need.extra"));
    }

    // ---------------------------------------------------------------- shared

    private static Line line(String key, Block shown, boolean present) {
        return new Line(Component.translatable(key, shown.getName()), new ItemStack(shown), present ? State.PRESENT : State.MISSING);
    }

    /**
     * The block of a tag that stands for it on a line: the first by registry order. An empty tag is logged once and gives
     * no block, so the line reads "none" instead of naming air.
     */
    private static Optional<Block> firstBlock(TagKey<Block> tag) {
        Optional<Block> first = BuiltInRegistries.BLOCK.stream().filter(block -> block.defaultBlockState().is(tag)).findFirst();
        if (first.isEmpty() && EMPTY_TAGS_LOGGED.add(tag.location())) {
            CivMod.LOGGER.warn("The block tag {} holds no block: a job block line that names it shows none", tag.location());
        }
        return first;
    }
}
