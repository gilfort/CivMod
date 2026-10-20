package com.gilfort.civmod.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * Rules for work points and job assignment of JobBlocks (vault {@code Systems/Scroll of Assignment.md}).
 * <p>
 * Pure logic on {@link SettlementData}; facts about the world (is the block a valid work place, is it a container)
 * are passed in, so the rules are testable without a world.
 */
public final class JobAssignments {

    private JobAssignments() {
    }

    /** Role of a block for a JobBlock. */
    public enum Role {
        /** No role. */
        NONE,
        /** Work POI. */
        WORK,
        /** Collect inventory (tool supply). */
        COLLECT,
        /** Drop-off inventory. */
        DROP_OFF
    }

    /** Result of cycling a point. */
    public enum PointResult {
        /** Added as work POI. */
        WORK,
        /** Set as collect inventory (replaces a previous one). */
        COLLECT,
        /** Set as drop-off inventory (replaces a previous one). */
        DROP_OFF,
        /** Role removed. */
        REMOVED,
        /** The JobBlock is not registered. */
        NO_JOB_BLOCK,
        /** Outside the settlement's join radius. */
        OUTSIDE,
        /** Neither a valid work place for the job nor a container. */
        INVALID,
        /** Already {@value SettlementBlock#MAX_WORK_POINTS} work POIs. */
        TOO_MANY,
        /** Linked as Clerk storage. */
        STORAGE,
        /** Added as Clerk stand spot. */
        STAND_SPOT,
        /** The Clerk links as many containers as its level allows. */
        STORAGE_FULL,
        /** Already {@value SettlementRules#MAX_STAND_SPOTS} stand spots. */
        TOO_MANY_SPOTS,
        /** The container is storage of another Clerk. */
        TAKEN
    }

    /** Result of assigning a JobBlock to a settler. */
    public enum AssignResult {
        /** Assigned. */
        OK,
        /** The JobBlock is not registered. */
        NO_JOB_BLOCK,
        /** The JobBlock is not connected to its settlement. */
        UNCONNECTED,
        /** Another settler holds the Job's only place. */
        OCCUPIED,
        /** Every place of the Job is taken (several places, e.g. Deliverymen or the Mission Board). */
        FULL,
        /** The JobBlock does not offer the Job (at its level). */
        NOT_OFFERED,
        /** The settler already holds another Job at this JobBlock (one Job per JobBlock). */
        SAME_BLOCK,
        /** The settler already has a Mission Job (at most one). */
        MISSION_JOB,
        /** The settler belongs to another settlement or is homeless. */
        OTHER_SETTLEMENT,
        /** The settler already has this job. */
        DUPLICATE
    }

    /**
     * @param block a JobBlock
     * @param pos   a position
     * @return the role of the position for that JobBlock
     */
    public static Role roleOf(SettlementBlock block, BlockPos pos) {
        if (block.workPoints().contains(pos)) {
            return Role.WORK;
        }
        if (block.collect().filter(pos::equals).isPresent()) {
            return Role.COLLECT;
        }
        if (block.dropOff().filter(pos::equals).isPresent()) {
            return Role.DROP_OFF;
        }
        return Role.NONE;
    }

    /**
     * Cycles the role of a target block: none → work → collect → drop-off → none, skipping roles the target cannot
     * have (work needs a valid work place, collect / drop-off a container; the JobBlock itself is never a work POI).
     *
     * @param data       the dimension's settlement data
     * @param jobBlock   the bound JobBlock
     * @param target     the clicked position
     * @param validWork  whether the target is a valid work place for the job
     * @param container  whether the target is an item container
     * @return the result
     */
    public static PointResult cyclePoint(SettlementData data, BlockPos jobBlock, BlockPos target, boolean validWork, boolean container) {
        Optional<JobBlockSite> site = JobBlockSite.at(data, jobBlock);
        if (site.isEmpty()) {
            return PointResult.NO_JOB_BLOCK;
        }
        SettlementBlock block = site.get().block();
        boolean self = target.equals(jobBlock);
        boolean work = validWork && !self;
        Role current = roleOf(block, target);
        if (current == Role.NONE && !insideBorder(site.get().settlement(), target)) {
            return PointResult.OUTSIDE;
        }
        Role next = switch (current) {
            case NONE -> work ? Role.WORK : container ? Role.COLLECT : null;
            case WORK -> container ? Role.COLLECT : Role.NONE;
            case COLLECT -> Role.DROP_OFF;
            case DROP_OFF -> Role.NONE;
        };
        if (next == null) {
            return PointResult.INVALID;
        }
        if (next == Role.WORK && block.workPoints().size() >= SettlementBlock.MAX_WORK_POINTS) {
            return container ? apply(data, block, target, current, Role.COLLECT) : PointResult.TOO_MANY;
        }
        return apply(data, block, target, current, next);
    }

    private static PointResult apply(SettlementData data, SettlementBlock block, BlockPos target, Role from, Role to) {
        BlockPos pos = target.immutable();
        switch (from) {
            case WORK -> block.mutableWorkPoints().remove(pos);
            case COLLECT -> block.setCollect(Optional.empty());
            case DROP_OFF -> block.setDropOff(Optional.empty());
            case NONE -> {
            }
        }
        PointResult result = switch (to) {
            case WORK -> {
                block.mutableWorkPoints().add(pos);
                yield PointResult.WORK;
            }
            case COLLECT -> {
                block.setCollect(Optional.of(pos));
                yield PointResult.COLLECT;
            }
            case DROP_OFF -> {
                block.setDropOff(Optional.of(pos));
                yield PointResult.DROP_OFF;
            }
            case NONE -> PointResult.REMOVED;
        };
        if (from == Role.DROP_OFF || to == Role.DROP_OFF) {
            // A new drop-off: the pickups of the old one go (it no longer holds this job's harvest).
            data.settlementAt(block.pos()).ifPresent(settlement -> settlement.mutableDeliveries()
                    .removeIf(task -> task.jobBlock().equals(block.pos())));
        }
        data.changed();
        return result;
    }

    /**
     * Cycles a point of a Clerk JobBlock (vault {@code Systems/Scroll of Assignment.md}): a container cycles
     * storage → removed, any other block stand spot → removed. Registered JobBlocks never become storage; a container
     * belongs to one Clerk only.
     *
     * @param data      the dimension's settlement data
     * @param clerk     the bound Clerk JobBlock
     * @param target    the clicked position
     * @param container whether the target is an item container
     * @param standable whether a settler can stand on the target (two blocks of air above)
     * @param facing    the direction the Clerk faces on a new stand spot
     * @return the result
     */
    public static PointResult cycleClerkPoint(SettlementData data, BlockPos clerk, BlockPos target, boolean container, boolean standable,
            Direction facing) {
        Optional<JobBlockSite> site = JobBlockSite.at(data, clerk);
        if (site.isEmpty()) {
            return PointResult.NO_JOB_BLOCK;
        }
        SettlementBlock block = site.get().block();
        BlockPos pos = target.immutable();
        if (block.mutableStorage().remove(pos) || block.mutableStandSpots().removeIf(spot -> spot.pos().equals(pos))) {
            data.changed();
            return PointResult.REMOVED;
        }
        if (!insideBorder(site.get().settlement(), pos)) {
            return PointResult.OUTSIDE;
        }
        if (container) {
            if (data.settlementAt(pos).isPresent()) {
                return PointResult.INVALID;
            }
            if (storageOwner(data, pos).isPresent()) {
                return PointResult.TAKEN;
            }
            if (block.storage().size() >= SettlementRules.clerkStorage(block.level())) {
                return PointResult.STORAGE_FULL;
            }
            block.mutableStorage().add(pos);
            data.changed();
            return PointResult.STORAGE;
        }
        if (!standable) {
            return PointResult.INVALID;
        }
        if (block.standSpots().size() >= SettlementRules.MAX_STAND_SPOTS) {
            return PointResult.TOO_MANY_SPOTS;
        }
        block.mutableStandSpots().add(new SettlementBlock.StandSpot(pos, facing));
        data.changed();
        return PointResult.STAND_SPOT;
    }

    /**
     * @param data      the dimension's settlement data
     * @param container a container position
     * @return the Clerk JobBlock the container is linked to as storage
     */
    public static Optional<SettlementBlock> storageOwner(SettlementData data, BlockPos container) {
        return data.settlements().stream().flatMap(settlement -> settlement.blocks().stream())
                .filter(block -> block.storage().contains(container)).findFirst();
    }

    /**
     * Removes a block that is gone as storage or stand spot of every Clerk.
     *
     * @param data      the dimension's settlement data
     * @param container the container position
     * @return whether it was linked
     */
    public static boolean removePointsAt(SettlementData data, BlockPos container) {
        boolean removed = false;
        for (Settlement settlement : data.settlements()) {
            for (SettlementBlock block : settlement.blocks()) {
                removed |= block.mutableStorage().remove(container);
                removed |= block.mutableStandSpots().removeIf(spot -> spot.pos().equals(container));
            }
        }
        if (removed) {
            data.changed();
        }
        return removed;
    }

    /**
     * @param settlement a settlement
     * @param pos        a position
     * @return whether the position lies within the join radius of a connected JobBlock
     */
    public static boolean insideBorder(Settlement settlement, BlockPos pos) {
        return settlement.blocks().stream().anyMatch(block -> block.connected()
                && SettlementRules.horizontalDistance(pos, block.pos()) <= SettlementRules.joinRadius(block.level()));
    }

    /**
     * Assigns the Base Job of a JobBlock to a settler ({@link #assign(SettlementData, BlockPos, UUID, String, int, Collection, Job)}).
     *
     * @param data         the dimension's settlement data
     * @param jobBlock     the JobBlock position
     * @param settler      the settler UUID
     * @param settlerName  the settler name (for display)
     * @param settlementId the settler's settlement id ({@code -1} if homeless)
     * @param currentJobs  the job types the settler already has
     * @return the result
     */
    public static AssignResult assign(SettlementData data, BlockPos jobBlock, UUID settler, String settlerName, int settlementId,
            Collection<Job> currentJobs) {
        return JobBlockSite.blockAt(data, jobBlock)
                .map(block -> assign(data, jobBlock, settler, settlerName, settlementId, currentJobs, block.jobType()))
                .orElse(AssignResult.NO_JOB_BLOCK);
    }

    /**
     * Checks whether a settler may take any Job of a JobBlock, before the job picker opens: the block is connected, in
     * the settler's settlement, and the settler holds no Job there yet.
     *
     * @param data         the dimension's settlement data
     * @param jobBlock     the JobBlock position
     * @param settler      the settler UUID
     * @param settlementId the settler's settlement id ({@code -1} if homeless)
     * @return {@link AssignResult#OK} or the reason the settler cannot join
     */
    public static AssignResult check(SettlementData data, BlockPos jobBlock, UUID settler, int settlementId) {
        Optional<JobBlockSite> site = JobBlockSite.at(data, jobBlock);
        if (site.isEmpty()) {
            return AssignResult.NO_JOB_BLOCK;
        }
        SettlementBlock block = site.get().block();
        if (!block.connected()) {
            return AssignResult.UNCONNECTED;
        }
        if (site.get().settlement().id() != settlementId) {
            return AssignResult.OTHER_SETTLEMENT;
        }
        if (block.isAssignee(settler)) {
            return AssignResult.SAME_BLOCK;
        }
        return AssignResult.OK;
    }

    /**
     * Assigns one Job of a JobBlock to a settler (the job picker's choice). A settler holds at most one Job per JobBlock,
     * each Job once and at most one Mission Job; a Job takes settlers up to its places ({@link JobPlaces}).
     *
     * @param data         the dimension's settlement data
     * @param jobBlock     the JobBlock position
     * @param settler      the settler UUID
     * @param settlerName  the settler name (for display)
     * @param settlementId the settler's settlement id ({@code -1} if homeless)
     * @param currentJobs  the job types the settler already has
     * @param job          the chosen Job
     * @return the result
     */
    public static AssignResult assign(SettlementData data, BlockPos jobBlock, UUID settler, String settlerName, int settlementId,
            Collection<Job> currentJobs, Job job) {
        AssignResult checked = check(data, jobBlock, settler, settlementId);
        if (checked != AssignResult.OK) {
            return checked;
        }
        SettlementBlock block = JobBlockSite.blockAt(data, jobBlock).orElseThrow();
        Optional<JobPlaces.Line> line = JobPlaces.line(block, job);
        if (line.isEmpty()) {
            return AssignResult.NOT_OFFERED;
        }
        if (job.category() == JobCategory.MISSION && currentJobs.stream().anyMatch(current -> current.category() == JobCategory.MISSION)) {
            return AssignResult.MISSION_JOB;
        }
        if (currentJobs.contains(job)) {
            return AssignResult.DUPLICATE;
        }
        if (line.get().full()) {
            // "Occupied by <name>" names the holder of a single place; the Mission Board always reports itself full.
            return line.get().places() == 1 && job.category() != JobCategory.MISSION ? AssignResult.OCCUPIED : AssignResult.FULL;
        }
        block.mutableAssignees().add(new SettlementBlock.Assignee(settler, settlerName, job));
        if (job == ModJobs.DELIVERYMAN.get()) {
            // A Clerk with own Deliverymen serves itself: it is no Served Clerk any more.
            JobBlockSite.at(data, jobBlock).ifPresent(site -> site.settlement().blocks()
                    .forEach(other -> other.mutableServed().remove(jobBlock)));
        }
        data.changed();
        return AssignResult.OK;
    }

    /**
     * The job switcher's choices (vault {@code Systems/Jobs.md}): the other Jobs unlocked at the settler's JobBlock it
     * may switch to. A Job sharing the settler's places (same Base Job) always has room; a Job with own places (the
     * Clerk JobBlock's Deliveryman) needs a free one. Jobs the settler holds at other JobBlocks and a second Mission Job
     * are left out, like in the job picker.
     *
     * @param data      the dimension's settlement data
     * @param jobBlock  the JobBlock the settler works
     * @param settler   the settler UUID
     * @param otherJobs the Jobs the settler holds at other JobBlocks
     * @return the Jobs it may switch to, in picker order; empty if it does not work the JobBlock
     */
    public static List<Job> switchOptions(SettlementData data, BlockPos jobBlock, UUID settler, Collection<Job> otherJobs) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(data, jobBlock);
        Optional<SettlementBlock.Assignee> own = block.flatMap(found -> found.assignee(settler));
        if (own.isEmpty()) {
            return List.of();
        }
        Job current = own.get().job();
        boolean otherMission = otherJobs.stream().anyMatch(job -> job.category() == JobCategory.MISSION);
        return JobPlaces.lines(block.get()).stream()
                .filter(line -> line.job() != current && !otherJobs.contains(line.job()))
                .filter(line -> !(otherMission && line.job().category() == JobCategory.MISSION))
                .filter(line -> base(line.job()) == base(current) || !line.full())
                .map(JobPlaces.Line::job).toList();
    }

    /**
     * Switches the Job a settler holds at a JobBlock to one of its {@link #switchOptions}, keeping its place in the
     * block's assignee order. On a Mission Board its mission assignments are removed (the missions stay locked), as
     * the new Mission Job may not be allowed on them.
     *
     * @param data      the dimension's settlement data
     * @param jobBlock  the JobBlock the settler works
     * @param settler   the settler UUID
     * @param otherJobs the Jobs the settler holds at other JobBlocks
     * @param job       the new Job
     * @return whether the Job was switched
     */
    public static boolean switchJob(SettlementData data, BlockPos jobBlock, UUID settler, Collection<Job> otherJobs, Job job) {
        if (!switchOptions(data, jobBlock, settler, otherJobs).contains(job)) {
            return false;
        }
        JobBlockSite.blockAt(data, jobBlock).orElseThrow().setAssigneeJob(settler, job);
        MissionOffers.removeAssignee(data, jobBlock, settler);
        if (job == ModJobs.DELIVERYMAN.get()) {
            JobBlockSite.at(data, jobBlock).ifPresent(site -> site.settlement().blocks().forEach(other -> other.mutableServed().remove(jobBlock)));
        }
        data.changed();
        return true;
    }

    /**
     * @param data     the dimension's settlement data
     * @param jobBlock a JobBlock position
     * @param settler  a settler UUID
     * @param job      a Job
     * @return whether the settler holds that Job at another JobBlock of the same settlement
     */
    public static boolean holdsElsewhere(SettlementData data, BlockPos jobBlock, UUID settler, Job job) {
        return JobBlockSite.at(data, jobBlock).stream().flatMap(site -> site.settlement().blocks().stream())
                .filter(block -> !block.pos().equals(jobBlock))
                .anyMatch(block -> block.assignee(settler).filter(assignee -> assignee.job() == job).isPresent());
    }

    private static Job base(Job job) {
        return job.parent().orElse(job);
    }

    /**
     * Frees a JobBlock of all its settlers (and removes their mission assignments, see {@link #unassign(SettlementData, BlockPos, UUID)}).
     *
     * @param data     the dimension's settlement data
     * @param jobBlock the JobBlock position
     * @return the settlers that worked it
     */
    public static List<UUID> unassign(SettlementData data, BlockPos jobBlock) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(data, jobBlock);
        List<UUID> previous = block.map(b -> b.assignees().stream().map(SettlementBlock.Assignee::id).toList()).orElse(List.of());
        if (!previous.isEmpty()) {
            block.get().clearAssignees();
            previous.forEach(settler -> MissionOffers.removeAssignee(data, jobBlock, settler));
            data.changed();
        }
        return previous;
    }

    /**
     * Removes one settler from a JobBlock; on a Mission Board its mission assignments are removed (the missions stay
     * locked).
     *
     * @param data     the dimension's settlement data
     * @param jobBlock the JobBlock position
     * @param settler  the settler UUID
     * @return whether the settler worked it
     */
    public static boolean unassign(SettlementData data, BlockPos jobBlock, UUID settler) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(data, jobBlock);
        boolean removed = block.map(b -> b.removeAssignee(settler)).orElse(false);
        if (removed) {
            MissionOffers.removeAssignee(data, jobBlock, settler);
            data.changed();
        }
        return removed;
    }

    /**
     * Toggles leaf collection of a Lumberjack Block.
     *
     * @param data     the dimension's settlement data
     * @param jobBlock the JobBlock position
     * @return the new state
     */
    public static boolean toggleLeaves(SettlementData data, BlockPos jobBlock) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(data, jobBlock);
        block.ifPresent(b -> {
            b.setCollectLeaves(!b.collectLeaves());
            data.changed();
        });
        return block.map(SettlementBlock::collectLeaves).orElse(false);
    }

    /**
     * Excludes or includes a found resource of a Resource Collection JobBlock (vault
     * {@code Systems/Resource Collection Jobs.md}): the job no longer produces an excluded type.
     *
     * @param data     the dimension's settlement data
     * @param jobBlock the JobBlock position
     * @param type     the found resource type
     * @return whether the type is excluded now
     */
    public static boolean toggleExcluded(SettlementData data, BlockPos jobBlock, Identifier type) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(data, jobBlock);
        boolean excluded = block.map(found -> found.toggleExcluded(type)).orElse(false);
        block.ifPresent(found -> data.changed());
        return excluded;
    }

    /**
     * Keeps the latest scan of a settler working a JobBlock on the JobBlock, so its Found tab shows it without a
     * settler.
     *
     * @param data     the dimension's settlement data
     * @param jobBlock the JobBlock position
     * @param scan     the scan
     */
    public static void rememberScan(SettlementData data, BlockPos jobBlock, SavedScan scan) {
        JobBlockSite.blockAt(data, jobBlock).filter(block -> !block.foundScan().equals(Optional.of(scan))).ifPresent(block -> {
            block.setFoundScan(scan);
            data.changed();
        });
    }

    /**
     * Toggles who carries a Producing JobBlock's results: its settler itself or Deliverymen (vault
     * {@code Jobs/Craftsperson.md}).
     *
     * @param data     the dimension's settlement data
     * @param jobBlock the JobBlock position
     * @return the new state: whether Deliverymen carry them
     */
    public static boolean toggleDelivery(SettlementData data, BlockPos jobBlock) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(data, jobBlock).filter(Patterns::isProducing);
        block.ifPresent(b -> {
            b.setByDeliveryman(!b.byDeliveryman());
            data.changed();
        });
        return block.map(SettlementBlock::byDeliveryman).orElse(false);
    }

    /**
     * @param data     the dimension's settlement data
     * @param jobBlock a JobBlock position
     * @param settler  a settler UUID
     * @return whether the settler is an assignee of that JobBlock
     */
    public static boolean isAssignee(SettlementData data, BlockPos jobBlock, UUID settler) {
        return JobBlockSite.blockAt(data, jobBlock).filter(block -> block.isAssignee(settler)).isPresent();
    }
}
