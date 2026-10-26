package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.BlockingReason;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Resource Collection Job Category (vault {@code Systems/Resource Collection Jobs.md}); its contract
 * with the Job is the {@link ResourceCollectionProfile}. Per job: fetch the tool, take the work place of the day and the scan from the
 * job's {@link Workplace}, walk there and produce into its buffer, which the shell delivers at ~80 % or at the end of
 * the day. Production (live every work tick, catch-up per simulated hour) goes through the {@link WorkRules}; this
 * loop only adds the live effects (swing, hit sound, level-up) and chooses where output goes.
 */
final class ResourceCollectionLoop implements CategoryLoop {

    private static final int SWING_INTERVAL = 10;

    /** The step of the loop the settler is in. */
    private enum Step { FETCH_TOOL, WORK }

    private final SettlerWork shell;
    private final Settler settler;
    private final Fatigue fatigue = new Fatigue();

    private Step step = Step.WORK;
    private int swingTimer;
    /** Counts work ticks to the next check that the work place still stands. */
    private int placeTimer;
    /** Where the settler fetches its tool (a Clerk serves at its counter). */
    private ClerkStorage.@Nullable Visit toolVisit;

    /**
     * Output of the {@link WorkRules} while the job's buffer has room; the sink takes the items of a roll (live: into
     * the buffer; catch-up: the drop-off first) and says whether more fits.
     */
    private record BufferOutput(JobBuffer buffer, Predicate<List<ItemStack>> sink) implements WorkRules.Output {

        @Override
        public boolean hasRoom() {
            return !buffer.isFull();
        }

        @Override
        public boolean accept(List<ItemStack> stacks) {
            return sink.test(stacks);
        }
    }

    /**
     * @param shell the Settler Shell driving this loop
     */
    ResourceCollectionLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        Workplace workplace = shell.workplace(job.jobBlock());

        if (workplace.buffer().shouldDeliver()) {
            return JobStep.DELIVER;
        }
        Optional<ResourceCollectionProfile> found = job.jobType().profile(ResourceCollectionProfile.class);
        if (found.isEmpty()) {
            return JobStep.skip();
        }
        ResourceCollectionProfile profile = found.get();
        Optional<WorkRules.MissingTool> missing = WorkRules.missingTool(settler, profile, site,
                ClerkStorage.stock(level, site.collect(), dayTime, !shell.counterUnreachable(level, site.collect())));
        if (missing.isPresent()) {
            return fetchTool(level, site, missing.get(), dayTime);
        }
        BlockPos poi = workplace.workPlaceOfDay(level, settler, profile, site, day);
        if (poi == null) {
            if (workplace.noFreeWorkPlace()) {
                return JobStep.blocked(JobStatus.NO_FREE_WORK_PLACE);
            }
            return profile.blockingReason(null).map(JobStep::blocked).orElseGet(() -> JobStep.blocked(JobStatus.NO_WORK_PLACE));
        }
        workplace.ensureScan(level, profile, site, poi, day, dayTime);
        Optional<BlockingReason> reason = workplace.scan() == null ? Optional.empty() : profile.blockingReason(workplace.scan());
        if (reason.isPresent()) {
            return JobStep.blocked(reason.get());
        }
        if (profile.needsResources() && workplace.foundNothing()) {
            return JobStep.blocked(JobStatus.NO_RESOURCES);
        }
        ItemStack tool = SettlerTools.best(settler, profile::isTool).orElse(ItemStack.EMPTY);
        Optional<JobStatus> paused = WorkRules.pause(level, profile, workplace, site, tool, StockRules.atMax(level, site)).pauseStatus();
        if (paused.isPresent()) {
            return JobStep.blocked(paused.get());
        }
        step = Step.WORK;
        showTool(profile);
        return JobStep.walk(poi, profile.reach(), JobStatus.WORKING);
    }

    private JobStep fetchTool(ServerLevel level, JobBlockSite site, WorkRules.MissingTool tool, long dayTime) {
        if (!tool.inCollect()) {
            return JobStep.blocked(JobStatus.NO_TOOL);
        }
        ClerkStorage.Visit visit = shell.reachableVisit(level, site.collect(), ClerkStorage.visit(level, site.collect(), dayTime));
        // Walking on to the same place keeps how it is reached.
        if (step != Step.FETCH_TOOL || toolVisit == null || !toolVisit.target().equals(visit.target())) {
            step = Step.FETCH_TOOL;
            toolVisit = visit;
        }
        return JobStep.walk(toolVisit.target(), toolVisit.reach(), JobStatus.FETCHING_TOOL);
    }

    /** End-of-work sweep: a drop-off with anything left gets a pickup for the Deliverymen. */
    @Override
    public void dayEnded(ServerLevel level, SettlerJob job) {
        JobBlockSite.at(level, job.jobBlock()).ifPresent(site -> Pickups.sweep(level, site));
    }

    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        if (step == Step.WORK && fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom())) {
            return new JobStep.Pause(JobStatus.PAUSING);
        }
        return JobStep.skip();
    }

    // ---------------------------------------------------------------- per-tick work

    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        return switch (step) {
            case FETCH_TOOL -> fetchToolAt(level, job);
            case WORK -> {
                if (++placeTimer >= SWING_INTERVAL) {
                    placeTimer = 0;
                    if (!stillWorkPlace(level, job, target)) {
                        // Broken: the next decision picks a new free work place at once; the buffer stays.
                        yield JobStep.DONE;
                    }
                }
                if (fatigue.onBreak()) {
                    yield JobStep.STAY;
                }
                produce(level, job, target);
                yield JobStep.WORK;
            }
        };
    }

    private static boolean stillWorkPlace(ServerLevel level, SettlerJob job, BlockPos target) {
        return job.jobType().profile(ResourceCollectionProfile.class).map(profile -> profile.isWorkPlace(level, target)).orElse(true);
    }

    /** At the collect inventory: takes the tool (at a working Clerk's counter after waiting for its turn). */
    private JobStep fetchToolAt(ServerLevel level, SettlerJob job) {
        Optional<ResourceCollectionProfile> profile = job.jobType().profile(ResourceCollectionProfile.class);
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (profile.isPresent() && site.isPresent()) {
            BlockPos collect = site.get().collect();
            boolean counter = toolVisit != null && toolVisit.counter();
            if (counter && !shell.waitAtCounter(level, collect)) {
                // Waiting for the Clerk.
                return JobStep.STAY;
            }
            Stock stock = ClerkStorage.stock(level, collect, DayClock.dayTime(level), counter);
            if (WorkRules.refill(settler, profile.get(), site.get(), stock) && stock.counter()) {
                ClerkCounter.handOut(level, collect, true);
            }
        }
        toolVisit = null;
        return JobStep.DONE;
    }

    /** Shows the best tool of the job in the hand. */
    private void showTool(ResourceCollectionProfile profile) {
        shell.hold(SettlerTools.best(settler, profile::isTool).map(ItemStack::copy).orElse(ItemStack.EMPTY));
    }

    private void produce(ServerLevel level, SettlerJob job, BlockPos poi) {
        BlockPos jobBlock = job.jobBlock();
        settler.getLookControl().setLookAt(Vec3.atCenterOf(poi));
        if (++swingTimer >= SWING_INTERVAL && job.jobType().profile(ResourceCollectionProfile.class).map(ResourceCollectionProfile::swings).orElse(true)) {
            swingTimer = 0;
            settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            BlockState state = level.getBlockState(poi);
            level.playSound(null, poi, state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        Optional<ResourceCollectionProfile> profile = job.jobType().profile(ResourceCollectionProfile.class);
        Optional<JobBlockSite> site = JobBlockSite.at(level, jobBlock);
        if (profile.isEmpty() || site.isEmpty()) {
            return;
        }
        JobBuffer buffer = shell.workplace(jobBlock).buffer();
        WorkRules.Result result = WorkRules.work(level, settler, job, profile.get(), site.get(), shell.workplace(jobBlock), poi, 1.0,
                WorkRules.ToolSupply.BELT, new BufferOutput(buffer, stacks -> {
                    stacks.forEach(buffer::add);
                    return !buffer.isFull();
                }));
        if (result.levelUp()) {
            shell.showLevelUp(level, job);
        }
        // A job that ended (no tool, buffer full, no resources) is re-planned at the next decision.
        showTool(profile.get());
    }

    // ---------------------------------------------------------------- catch-up

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        Optional<ResourceCollectionProfile> profile = job.jobType().profile(ResourceCollectionProfile.class);
        if (profile.isEmpty()) {
            return true;
        }
        if (!level.isLoaded(site.pos()) || !level.isLoaded(site.collect()) || !level.isLoaded(site.dropOff())) {
            return false;
        }
        Workplace workplace = shell.workplace(job.jobBlock());
        if (workplace.scan() == null) {
            BlockPos poi = workplace.workPlaceOfDay(level, settler, profile.get(), site, day);
            if (poi == null) {
                return true;
            }
            workplace.ensureScan(level, profile.get(), site, poi, day, dayTime);
            return false;
        }
        return true;
    }

    /**
     * Produces the output of some work ticks during catch-up through the {@link WorkRules}: directly into the
     * drop-off, then the buffer; missing tools are taken from the collect inventory.
     */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        Optional<JobBlockSite> found = JobBlockSite.at(level, job.jobBlock());
        Optional<ResourceCollectionProfile> profile = job.jobType().profile(ResourceCollectionProfile.class);
        Workplace workplace = shell.workplace(job.jobBlock());
        BlockPos poi = found.isEmpty() || profile.isEmpty() || workplace.scan() == null ? null
                : workplace.workPlaceOfDay(level, settler, profile.get(), found.get(), day);
        if (poi == null) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        JobBlockSite site = found.get();
        WorkRules.Result result = WorkRules.work(level, settler, job, profile.get(), site, workplace, poi, workTicks,
                WorkRules.ToolSupply.beltAnd(ClerkStorage.stock(level, site.collect(), shell.catchUpDayTime(), true),
                        () -> ClerkCounter.handOut(level, site.collect(), false)), new BufferOutput(workplace.buffer(), stacks -> shell.deliverDirect(level, site, stacks)));
        if (result.end() == WorkRules.End.NONE) {
            return new SimulatedWork(result.rolls(), 0.0);
        }
        shell.endCatchUpJob(job.jobBlock());
        // The next job takes over the rest of the time.
        return new SimulatedWork(result.rolls(), result.ticksLeft());
    }
}
