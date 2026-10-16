package com.gilfort.civmod.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.job.JobCategory;

import net.minecraft.core.BlockPos;

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
        TOO_MANY
    }

    /** Result of assigning a JobBlock to a settler. */
    public enum AssignResult {
        /** Assigned. */
        OK,
        /** The JobBlock is not registered. */
        NO_JOB_BLOCK,
        /** The JobBlock is not connected to its settlement. */
        UNCONNECTED,
        /** Another settler works this JobBlock (it holds one settler). */
        OCCUPIED,
        /** The JobBlock holds as many settlers as its level allows (Mission Board). */
        FULL,
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
        data.changed();
        return result;
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
     * Assigns the job of a JobBlock to a settler. A settler holds at most one Mission Job; a JobBlock takes settlers up
     * to its {@link SettlementBlock#capacity() capacity}.
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
            return AssignResult.DUPLICATE;
        }
        boolean missionBoard = block.jobType().category() == JobCategory.MISSION;
        if (missionBoard && currentJobs.stream().anyMatch(job -> job.category() == JobCategory.MISSION)) {
            return AssignResult.MISSION_JOB;
        }
        if (currentJobs.contains(block.jobType())) {
            return AssignResult.DUPLICATE;
        }
        if (block.assignees().size() >= block.capacity()) {
            return missionBoard ? AssignResult.FULL : AssignResult.OCCUPIED;
        }
        block.mutableAssignees().add(new SettlementBlock.Assignee(settler, settlerName));
        data.changed();
        return AssignResult.OK;
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
            block.get().mutableAssignees().clear();
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
        boolean removed = block.map(b -> b.mutableAssignees().removeIf(assignee -> assignee.id().equals(settler))).orElse(false);
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
     * @param data     the dimension's settlement data
     * @param jobBlock a JobBlock position
     * @param settler  a settler UUID
     * @return whether the settler is an assignee of that JobBlock
     */
    public static boolean isAssignee(SettlementData data, BlockPos jobBlock, UUID settler) {
        return JobBlockSite.blockAt(data, jobBlock).filter(block -> block.isAssignee(settler)).isPresent();
    }
}
