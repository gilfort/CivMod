package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.StationTypes;
import com.mojang.datafixers.util.Either;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

    /** How one need stands. */
    public enum State {
        /** The Job needs nothing of this kind. */
        NOT_NEEDED,
        /** It is there. */
        PRESENT,
        /** It is missing. */
        MISSING;

        /** Network codec. */
        public static final StreamCodec<ByteBuf, State> STREAM_CODEC = NeoForgeStreamCodecs.enumCodec(State.class);
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
        return line("civmod.gui.station_types.need.station", firstBlock(tag.get()), found.isPresent());
    }

    // ---------------------------------------------------------------- tool

    /**
     * The tool a Resource Collection Job asks for: the tier of the strongest settler working it here (the first tier
     * without one), a tick when one of them carries a usable tool. A Job outside the tool staircase names none.
     */
    private static Line toolLine(ServerLevel level, JobBlockSite site, Job job) {
        Optional<ResourceCollectionProfile> profile = job.profile(ResourceCollectionProfile.class).filter(ToolStaircase::appliesTo);
        if (profile.isEmpty()) {
            return Line.none("civmod.gui.station_types.need.tool");
        }
        int band = 1;
        boolean carries = false;
        for (SettlementBlock.Assignee assignee : site.block().assignees()) {
            if (assignee.job() == job && level.getEntity(assignee.id()) instanceof Settler settler) {
                Optional<SettlerJob> held = settler.getJobs().stream()
                        .filter(entry -> entry.jobType() == job && entry.jobBlock().equals(site.pos())).findFirst();
                if (held.isPresent()) {
                    band = Math.max(band, settler.getWork().toolBand(held.get()));
                    carries |= settler.getWork().tool(held.get()).isPresent();
                }
            }
        }
        return new Line(Component.translatable("civmod.gui.settler.tool.band." + band), new ItemStack(profile.get().toolIcon()),
                carries ? State.PRESENT : State.MISSING);
    }

    // ---------------------------------------------------------------- extra station

    private static Line extraLine(ServerLevel level, Job job, Optional<BlockPos> station) {
        Optional<StationExtra> extra = JobBalancing.of(job).stationExtras().flatMap(extras -> extras.stream()
                .max(Comparator.comparingDouble(StationExtra::jobFactor)));
        if (extra.isEmpty()) {
            return Line.none("civmod.gui.station_types.need.extra");
        }
        Either<TagKey<Block>, Block> entry = extra.get().blocks().getFirst();
        Block shown = entry.map(JobNeeds::firstBlock, block -> block);
        boolean near = station.isPresent() && StationExtras.near(level, station.get(), extra.get());
        return line("civmod.gui.station_types.need.extra", shown, near);
    }

    // ---------------------------------------------------------------- shared

    private static Line line(String key, Block shown, boolean present) {
        return new Line(Component.translatable(key, shown.getName()), new ItemStack(shown), present ? State.PRESENT : State.MISSING);
    }

    /** The block of a tag that stands for it on a line: the first by registry order. */
    private static Block firstBlock(TagKey<Block> tag) {
        return BuiltInRegistries.BLOCK.stream().filter(block -> block.defaultBlockState().is(tag)).findFirst().orElse(Blocks.AIR);
    }
}
