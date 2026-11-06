package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.block.JobBlockSettlers;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Laborers;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The Laborer fallback of one settler (vault {@code Jobs/Laborer.md}): when no Job of the priority list can work, the
 * settler helps at a Resource Collection JobBlock of its settlement at {@value #SPEED} of an employee's speed. It
 * works through the same {@link ResourceCollectionLoop} as an employee, as a {@link SettlerJob} of the Job the block's
 * employee does (else the Base Job) that is not in its job list: its skill, tools, work rules, buffer and drop-off are
 * the Job's, only the speed, the choice of the block and the work place of the day differ.
 * <p>
 * The block is chosen when the settler falls back: blocks without an employee first, then the fewest Laborers, then the
 * nearest; a block takes at most {@link JobBlockSettlers#laborers} Laborers ({@link Laborers}). A block it cannot work
 * (no tool, nothing to produce, a Stock Max, no work place) is skipped for 30 s. Every decision asks the priority list
 * first, so a higher Job with a task takes the settler over; the next fall back chooses anew. The Laborer does not
 * walk to another block while it can work the one it has.
 * <p>
 * What a Laborer carries stays in its {@link Workplace} of that block like an employee's buffer; once it stopped
 * helping there, the buffer is delivered after work.
 */
final class Laborer {

    /** The share of an employee's speed a Laborer works at. */
    static final double SPEED = 0.25;
    /** Blocks tried in one search. */
    private static final int TRIES = 3;

    private final SettlerWork shell;
    private final Settler settler;
    /** The block and Job the settler helps, null while it helps nowhere (idle, or a Job of its list works). */
    private @Nullable SettlerJob helping;
    /** While no block could be worked, no new search before this game time. */
    private long searchAfter;

    /** A block the settler could help at. */
    private record Candidate(JobBlockSite site, SettlerJob job, boolean employed, int laborers, double distance) {
    }

    Laborer(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    // ---------------------------------------------------------------- state

    /**
     * @return the block and Job the settler helps now, empty while it does not
     */
    Optional<SettlerJob> helping() {
        return Optional.ofNullable(helping);
    }

    /**
     * @param job a job the loops work
     * @return whether the settler works it as a Laborer: it is not in the settler's job list
     */
    boolean isHelper(SettlerJob job) {
        return settler.jobAt(job.jobBlock()).isEmpty();
    }

    /**
     * @param job a job the loops work
     * @return the share of the Job's speed the settler works it at
     */
    double speed(SettlerJob job) {
        return isHelper(job) ? SPEED : 1.0;
    }

    /**
     * What the settler helps at or carries from earlier help (delivered after work).
     *
     * @param level the level
     * @return the helped job, then the jobs it left a buffer at
     */
    List<SettlerJob> extraJobs(ServerLevel level) {
        List<SettlerJob> extra = new ArrayList<>();
        if (helping != null) {
            extra.add(helping);
        }
        for (BlockPos block : shell.workplaceBlocks()) {
            boolean helped = helping != null && helping.jobBlock().equals(block);
            if (!helped && settler.jobAt(block).isEmpty() && !shell.workplace(block).buffer().isEmpty()) {
                site(level, block).ifPresent(site -> extra.add(job(site)));
            }
        }
        return extra;
    }

    // ---------------------------------------------------------------- live decisions

    /**
     * Helps at a block: the one it has if it can work it, else the best block of a new choice.
     *
     * @param level   the level
     * @param day     the day
     * @param dayTime the time of day
     * @return whether the settler is busy helping
     */
    boolean plan(ServerLevel level, long day, long dayTime) {
        long now = level.getGameTime();
        if (helping != null && !valid(level, helping)) {
            release(level);
        }
        if (helping != null) {
            if (!blocked(helping.jobBlock(), now) && shell.plan(level, helping, day, dayTime)) {
                return true;
            }
            stopHelping(level);
        }
        if (now < searchAfter) {
            return false;
        }
        // A block that fails waits 30 s at this Workplace, so the whole list is tried at most once per 30 s.
        for (Candidate candidate : candidates(level, false)) {
            if (begin(level, candidate) && shell.plan(level, candidate.job(), day, dayTime)) {
                return true;
            }
            stopHelping(level);
        }
        searchAfter = now + Workplace.BLOCKED_RETRY;
        return false;
    }

    /**
     * The settler stops helping: a Job of its list works (the next fall back chooses anew), its work day is over or a
     * catch-up run ended. What it carries stays in its buffer and is delivered after work.
     *
     * @param level the level
     */
    void interrupt(ServerLevel level) {
        if (helping != null) {
            stopHelping(level);
        }
    }

    /**
     * The state of a JobBlock the shell forgets.
     *
     * @param jobBlock the JobBlock
     */
    void forgotten(BlockPos jobBlock) {
        if (helping != null && helping.jobBlock().equals(jobBlock) && settler.level() instanceof ServerLevel level) {
            stopHelping(level);
        }
    }

    // ---------------------------------------------------------------- work place and scan

    /**
     * The work place of the day of a helped block: the one its employee chose, else the one the first Laborer chose
     * (Laborers share the place instead of searching and occupy nothing). Also takes the scan the JobBlock kept for the
     * Job, so only a stale one is made again.
     *
     * @param level     the level
     * @param job       the helped job
     * @param profile   the job's profile
     * @param site      the helped JobBlock
     * @param workplace the settler's Workplace of that block
     * @param day       the day
     * @return the work place, null if there is none
     */
    @Nullable BlockPos place(ServerLevel level, SettlerJob job, ResourceCollectionProfile profile, JobBlockSite site, Workplace workplace, long day) {
        Predicate<BlockPos> workPlace = pos -> profile.isWorkPlace(level, pos);
        BlockPos chosen = Laborers.sharedPlace(site.settlement(), site.pos(), day).filter(workPlace).orElse(null);
        long now = level.getGameTime();
        if (chosen == null && Laborers.placeSearchDue(site.settlement(), site.pos(), now)) {
            List<BlockPos> points = site.block().workPoints().stream().filter(workPlace).toList();
            chosen = points.isEmpty() ? profile.autoSearch(level, site.pos(), pos -> true) : points.get(level.getRandom().nextInt(points.size()));
            if (chosen != null) {
                Laborers.setPlace(site.settlement(), site.pos(), chosen, day);
            } else {
                // No Laborer of the block searches again for 30 s.
                Laborers.placeSearchFailed(site.settlement(), site.pos(), now + Workplace.BLOCKED_RETRY);
            }
        }
        workplace.adoptWorkPlace(chosen, day);
        site.block().foundScan(job.jobType()).ifPresent(workplace::adoptScan);
        return chosen;
    }

    // ---------------------------------------------------------------- catch-up

    /**
     * Prepares the help for a catch-up run: a settler without any Job chooses its block and waits for the scan. A
     * settler with Jobs may find none of them can work, so the best blocks it would help get their scan too (taken from
     * the JobBlock when it kept one), without joining a crew.
     *
     * @param level   the level
     * @param day     the day
     * @param dayTime the time of day
     * @return whether it is ready (or has nothing to prepare)
     */
    boolean catchUpReady(ServerLevel level, long day, long dayTime) {
        if (!settler.getJobs().isEmpty()) {
            for (Candidate candidate : candidates(level, true).stream().limit(TRIES).toList()) {
                CategoryLoop loop = shell.loop(candidate.job());
                if (loop != null && !loop.catchUpReady(level, candidate.job(), candidate.site(), day, dayTime)) {
                    return false;
                }
            }
            return true;
        }
        SettlerJob job = catchUpTarget(level);
        CategoryLoop loop = job == null ? null : shell.loop(job);
        Optional<JobBlockSite> site = job == null ? Optional.empty() : site(level, job.jobBlock());
        return loop == null || site.isEmpty() || loop.catchUpReady(level, job, site.get(), day, dayTime);
    }

    /**
     * Simulates the help during catch-up, without walking, at {@value #SPEED} of an employee's speed.
     *
     * @param level     the level
     * @param day       the day
     * @param workTicks the work ticks no Job of the list used
     * @return the rolls made
     */
    int simulate(ServerLevel level, long day, double workTicks) {
        double remaining = workTicks;
        int rolls = 0;
        for (int i = 0; i < TRIES && remaining > 0.0; i++) {
            SettlerJob job = catchUpTarget(level);
            CategoryLoop loop = job == null ? null : shell.loop(job);
            if (loop == null) {
                break;
            }
            CategoryLoop.SimulatedWork simulated = loop.simulateWork(level, job, day, remaining);
            rolls += simulated.rolls();
            remaining = simulated.remaining();
            if (shell.workplace(job.jobBlock()).catchUpEnded()) {
                // This block is done for the run (no tool, nothing to produce, full): the next one takes over.
                stopHelping(level);
            }
        }
        return rolls;
    }

    /** The block the settler helps in a catch-up run: the one it has, else the best of the loaded ones. */
    private @Nullable SettlerJob catchUpTarget(ServerLevel level) {
        if (helping != null && !valid(level, helping)) {
            release(level);
        }
        if (helping != null && !shell.workplace(helping.jobBlock()).catchUpEnded()) {
            return helping;
        }
        for (Candidate candidate : candidates(level, true)) {
            if (begin(level, candidate)) {
                return candidate.job();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- choice

    /**
     * The blocks the settler could help at, best first: without an employee, then the fewest Laborers, then the nearest.
     *
     * @param level   the level
     * @param catchUp whether this is a catch-up run (loaded blocks only, ended ones skipped)
     */
    private List<Candidate> candidates(ServerLevel level, boolean catchUp) {
        Optional<Settlement> settlement = SettlementData.get(level).settlement(settler.getSettlementId());
        if (settlement.isEmpty()) {
            return List.of();
        }
        long now = level.getGameTime();
        List<Candidate> found = new ArrayList<>();
        for (SettlementBlock block : settlement.get().blocks()) {
            if (block.isCenter() || !block.connected() || block.jobType().category() != JobCategory.RESOURCE_COLLECTION
                    || settler.jobAt(block.pos()).isPresent()) {
                continue;
            }
            boolean member = Laborers.helps(settlement.get(), block.pos(), settler.getUUID());
            int others = Laborers.count(settlement.get(), block.pos()) - (member ? 1 : 0);
            if (others >= JobBlockSettlers.laborers(block.jobType()) || helped(block).profile(ResourceCollectionProfile.class).isEmpty()) {
                continue;
            }
            boolean unavailable = catchUp ? !loaded(level, block) || shell.existingWorkplace(block.pos()).filter(Workplace::catchUpEnded).isPresent()
                    : blocked(block.pos(), now);
            if (unavailable) {
                continue;
            }
            found.add(new Candidate(new JobBlockSite(settlement.get(), block), new SettlerJob(helped(block), block.pos()),
                    !block.assignees().isEmpty(), others, settler.blockPosition().distSqr(block.pos())));
        }
        found.sort(Comparator.comparing(Candidate::employed).thenComparingInt(Candidate::laborers).thenComparingDouble(Candidate::distance));
        return found;
    }

    /** Starts helping at a block if it still has room; a block of an earlier choice is left first. */
    private boolean begin(ServerLevel level, Candidate candidate) {
        if (helping != null && !helping.jobBlock().equals(candidate.site().pos())) {
            stopHelping(level);
        }
        if (!Laborers.join(candidate.site().settlement(), candidate.site().pos(), settler.getUUID(),
                JobBlockSettlers.laborers(candidate.site().block().jobType()))) {
            return false;
        }
        helping = candidate.job();
        return true;
    }

    /** The Job a Laborer helps at a block: the one its employee does, else the Base Job. */
    private static Job helped(SettlementBlock block) {
        return block.assignees().stream().findFirst().map(SettlementBlock.Assignee::job).orElse(block.jobType());
    }

    private static SettlerJob job(JobBlockSite site) {
        return new SettlerJob(helped(site.block()), site.pos());
    }

    private Optional<JobBlockSite> site(ServerLevel level, BlockPos block) {
        return JobBlockSite.at(level, block).filter(site -> site.settlement().id() == settler.getSettlementId());
    }

    private boolean blocked(BlockPos block, long now) {
        return shell.existingWorkplace(block).filter(workplace -> workplace.isBlocked(now)).isPresent();
    }

    /** Whether the settler can still help the job: its block is there, nobody gave the settler a job at it, and the Job is the same. */
    private boolean valid(ServerLevel level, SettlerJob job) {
        Optional<JobBlockSite> site = site(level, job.jobBlock());
        return site.isPresent() && settler.jobAt(job.jobBlock()).isEmpty() && helped(site.get().block()) == job.jobType();
    }

    // ---------------------------------------------------------------- leaving

    /** Leaves the crew of the block it helped; what the settler carries stays in its Workplace for the delivery after work. */
    private void stopHelping(ServerLevel level) {
        SettlerJob old = helping;
        helping = null;
        if (old != null) {
            site(level, old.jobBlock()).ifPresentOrElse(
                    site -> Laborers.leave(site.settlement(), old.jobBlock(), settler.getUUID(), level.getGameTime()),
                    () -> Laborers.leave(SettlementData.get(level), settler.getUUID()));
        }
    }

    /**
     * The block cannot be helped any more: the settler leaves its crew. A new Job at the block keeps the Workplace as it
     * is. If the block is still there (the Job it needs changed), what the settler carries stays in its buffer and is
     * delivered after work, only the scan and work place of the old Job go. Of a block that is gone the buffer drops.
     */
    private void release(ServerLevel level) {
        SettlerJob old = helping;
        stopHelping(level);
        if (old == null || settler.jobAt(old.jobBlock()).isPresent()) {
            return;
        }
        Workplace workplace = shell.workplace(old.jobBlock());
        if (workplace.buffer().isEmpty() || site(level, old.jobBlock()).isEmpty()) {
            shell.forget(old.jobBlock());
        } else {
            workplace.forgetWork();
        }
    }

    /** Whether the JobBlock, its collect inventory and its drop-off are loaded (what a catch-up needs). */
    private static boolean loaded(ServerLevel level, SettlementBlock block) {
        return level.isLoaded(block.pos()) && level.isLoaded(block.collect().orElse(block.pos())) && level.isLoaded(block.dropOff().orElse(block.pos()));
    }
}
