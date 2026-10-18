package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.registry.ModJobs;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which Jobs a JobBlock offers and how many places each has (vault {@code Systems/JobBlock.md}). A JobBlock offers
 * its own Base Job and, on a Clerk JobBlock, the Deliveryman; every Base Job brings its Specializations from level 2.
 * A Base Job and its Specializations share the Base Job's places (the Mission Board's settlers, whatever Mission Job
 * they have). A downgrade keeps every assignee; a Job with more assignees than places stays full until enough leave.
 */
public final class JobPlaces {

    private JobPlaces() {
    }

    /**
     * One Job of a JobBlock as the job picker and the info tab show it.
     *
     * @param job    the Job
     * @param taken  how many settlers hold one of the places it uses
     * @param places how many places it has at the block's level
     * @param auto   whether jobless settlers take its free places by themselves ({@link AutoAssignment})
     */
    public record Line(Job job, int taken, int places, boolean auto) {

        /** Network codec. */
        public static final StreamCodec<ByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                Job.STREAM_CODEC, Line::job,
                ByteBufCodecs.VAR_INT, Line::taken,
                ByteBufCodecs.VAR_INT, Line::places,
                ByteBufCodecs.BOOL, Line::auto,
                Line::new);

        /**
         * A line with automatic assignment off.
         *
         * @param job    the Job
         * @param taken  how many settlers hold one of the places it uses
         * @param places how many places it has at the block's level
         */
        public Line(Job job, int taken, int places) {
            this(job, taken, places, false);
        }

        /**
         * @return whether no place is free
         */
        public boolean full() {
            return taken >= places;
        }
    }

    /**
     * @param blockJob the Job the JobBlock was made for
     * @return the Base Jobs with own places on that JobBlock, the block's Job first
     */
    static List<Job> baseJobs(Job blockJob) {
        return blockJob == ModJobs.CLERK.get() ? List.of(blockJob, ModJobs.DELIVERYMAN.get()) : List.of(blockJob);
    }

    /**
     * @param blockJob the Job the JobBlock was made for
     * @param level    the JobBlock level
     * @return the Jobs the JobBlock offers at that level, in picker order
     */
    static List<Job> offered(Job blockJob, int level) {
        return baseJobs(blockJob).stream().flatMap(base -> base.offeredAt(level).stream()).toList();
    }

    /**
     * @param blockJob the Job the JobBlock was made for
     * @param job      an offered Job
     * @param level    the JobBlock level
     * @return the places the Job uses at that level (shared with its Base Job and that one's Specializations)
     */
    public static int places(Job blockJob, Job job, int level) {
        Job base = base(job);
        return base == blockJob ? SettlementRules.capacity(blockJob, level) : SettlementRules.deliverymanPlaces(level);
    }

    /**
     * @param block a JobBlock
     * @param job   an offered Job
     * @return how many assignees hold a place the Job uses
     */
    public static int taken(SettlementBlock block, Job job) {
        return holders(block.assignees(), job).size();
    }

    /**
     * @param block a JobBlock
     * @param job   an offered Job
     * @return the assignees holding a place the Job uses, in assignment order
     */
    public static List<SettlementBlock.Assignee> holders(SettlementBlock block, Job job) {
        return holders(block.assignees(), job);
    }

    private static List<SettlementBlock.Assignee> holders(List<SettlementBlock.Assignee> assignees, Job job) {
        Job base = base(job);
        return assignees.stream().filter(assignee -> base(assignee.job()) == base).toList();
    }

    /**
     * @param block a JobBlock
     * @return every Job the block offers at its level with taken / places
     */
    public static List<Line> lines(SettlementBlock block) {
        return lines(block.jobType(), block.level(), block.assignees(), block.autoAssign());
    }

    /**
     * @param blockJob  the Job the JobBlock was made for
     * @param level     the JobBlock level
     * @param assignees the block's assignees
     * @return every Job such a block offers at that level with taken / places, automatic assignment off
     */
    public static List<Line> lines(Job blockJob, int level, List<SettlementBlock.Assignee> assignees) {
        return lines(blockJob, level, assignees, Set.of());
    }

    /**
     * @param blockJob  the Job the JobBlock was made for
     * @param level     the JobBlock level
     * @param assignees the block's assignees
     * @param auto      the Jobs with automatic assignment switched on
     * @return every Job such a block offers at that level with taken / places
     */
    public static List<Line> lines(Job blockJob, int level, List<SettlementBlock.Assignee> assignees, Collection<Job> auto) {
        List<Line> lines = new ArrayList<>();
        for (Job job : offered(blockJob, level)) {
            lines.add(new Line(job, holders(assignees, job).size(), places(blockJob, job, level), auto.contains(job)));
        }
        return List.copyOf(lines);
    }

    /**
     * @param block a JobBlock
     * @param job   a Job
     * @return the line of that Job, if the block offers it at its level
     */
    static Optional<Line> line(SettlementBlock block, Job job) {
        return lines(block).stream().filter(line -> line.job() == job).findFirst();
    }

    private static Job base(Job job) {
        return job.parent().orElse(job);
    }
}
