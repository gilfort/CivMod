package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
import com.gilfort.civmod.settlement.OccupiedPlaces;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Resource Collection Job Category (vault {@code Systems/Resource Collection Jobs.md}); its contract
 * with the Job is the {@link ResourceCollectionProfile}. Per job: fetch the tool (and the inputs of its innate recipes,
 * {@link CollectionRecipes}), take the work place of the day and the scan from the
 * job's {@link Workplace}, walk there (a {@link Roaming} job walks on around it) and produce into its buffer, which the
 * shell delivers at ~80 % or at the end of the day. Production (live every work tick, catch-up per simulated hour) goes through the {@link WorkRules}; this
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
        Stock collect = ClerkStorage.stock(level, site.collect(), dayTime, !shell.counterUnreachable(level, site.collect()));
        Optional<WorkRules.MissingTool> missing = WorkRules.missingTool(level, settler, job, profile, site, collect);
        if (missing.isPresent() && SettlerTools.takeFromBuffer(settler, workplace.buffer(), missing.get().kind(), settler.getWork().toolRule(job, profile))) {
            // A tool the job collected itself goes straight onto the belt.
            missing = WorkRules.missingTool(level, settler, job, profile, site, collect);
        }
        if (missing.isPresent()) {
            // Delivered first: nothing else would deliver a small harvest.
            return shell.deliverable(job) ? JobStep.DELIVER : fetchTool(level, site, missing.get(), dayTime, JobStatus.FETCHING_TOOL);
        }
        BlockPos poi = placeOfDay(level, job, profile, site, workplace, day);
        if (poi == null) {
            if (workplace.noFreeWorkPlace()) {
                return JobStep.blocked(JobStatus.NO_FREE_WORK_PLACE);
            }
            return profile.blockingReason(null).map(JobStep::blocked).orElseGet(() -> JobStep.blocked(JobStatus.NO_WORK_PLACE));
        }
        workplace.ensureScan(level, job.jobType(), profile, site, poi, day, dayTime);
        Optional<BlockingReason> reason = workplace.scan() == null ? Optional.empty() : profile.blockingReason(workplace.scan());
        if (reason.isPresent()) {
            return JobStep.blocked(reason.get());
        }
        if (profile.needsResources() && workplace.noResources(profile)) {
            return JobStep.blocked(JobStatus.NO_RESOURCES);
        }
        ItemStack tool = shell.tool(job, profile).orElse(ItemStack.EMPTY);
        Set<Item> atMax = StockRules.atMax(level, site);
        List<CollectionRecipes.Offer> offered = CollectionRecipes.offered(level, job.jobType(), profile, workplace.scan());
        Optional<JobStatus> paused = WorkRules.pause(level, job.jobType(), profile, workplace, site, tool, atMax, offered).pauseStatus();
        if (paused.isPresent()) {
            return JobStep.blocked(paused.get());
        }
        // The inputs of the innate recipes are fetched like a tool; without them the recipes just leave the roll.
        Optional<Predicate<ItemStack>> input = CollectionRecipes.missingInput(settler,
                CollectionRecipes.allowed(offered, site.block().excluded(job.jobType()), atMax), collect);
        if (input.isPresent()) {
            return fetchTool(level, site, new WorkRules.MissingTool(input.get(), true), dayTime, JobStatus.FETCHING_INPUTS);
        }
        step = Step.WORK;
        showTool(job, profile);
        return JobStep.walk(standTarget(level, profile, site, workplace, poi, day), profile.reach(), JobStatus.WORKING);
    }

    /**
     * Where the settler stands to work: the work place of the day, or for a {@link RoamingProfile} where its
     * {@link Roaming} has it now. The scan anchor and the {@link WorkRules} roll keep their own places.
     */
    private BlockPos standTarget(ServerLevel level, ResourceCollectionProfile profile, JobBlockSite site, Workplace workplace, BlockPos poi, long day) {
        Supplier<Roaming> roaming = profile instanceof RoamingProfile roamingProfile
                ? () -> roamingProfile.roaming(level, site, poi, level.getGameTime()) : () -> Roaming.standingAt(poi);
        return workplace.roamingAt(poi, day, roaming).stand();
    }

    /**
     * The work place of the day: an employee chooses one and occupies it, a Laborer helping at the block shares the one
     * its employee (or the first Laborer) chose ({@link Laborer#place}).
     */
    private @Nullable BlockPos placeOfDay(ServerLevel level, SettlerJob job, ResourceCollectionProfile profile, JobBlockSite site, Workplace workplace, long day) {
        return shell.laborer().isHelper(job) ? shell.laborer().place(level, job, profile, site, workplace, day)
                : workplace.workPlaceOfDay(level, settler, profile, site, day);
    }

    private JobStep fetchTool(ServerLevel level, JobBlockSite site, WorkRules.MissingTool tool, long dayTime, JobStatus status) {
        if (!tool.inCollect()) {
            return JobStep.blocked(JobStatus.NO_TOOL);
        }
        ClerkStorage.Visit visit = shell.reachableVisit(level, site.collect(), ClerkStorage.visit(level, site.collect(), dayTime));
        // Walking on to the same place keeps how it is reached.
        if (step != Step.FETCH_TOOL || toolVisit == null || !toolVisit.target().equals(visit.target())) {
            step = Step.FETCH_TOOL;
            toolVisit = visit;
        }
        return JobStep.walk(toolVisit.target(), toolVisit.reach(), status);
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
                Roaming roaming = shell.workplace(job.jobBlock()).roamingOrStanding(target);
                if (++placeTimer >= SWING_INTERVAL) {
                    placeTimer = 0;
                    if (!stillWorkPlace(level, job, roaming.anchor())) {
                        // Broken: the next decision picks a new free work place at once; the buffer stays.
                        yield JobStep.DONE;
                    }
                }
                if (fatigue.onBreak()) {
                    yield JobStep.STAY;
                }
                Optional<JobStep> moved = roaming.isDue(level, level.getGameTime()) ? moveOn(level, job, roaming) : Optional.empty();
                if (moved.isPresent()) {
                    yield moved.get();
                }
                produce(level, job, target, roaming.rollPlace());
                yield JobStep.WORK;
            }
        };
    }

    /** Asks the roaming whether the settler moves on now; if so, the step walks to the new stand target. */
    private Optional<JobStep> moveOn(ServerLevel level, SettlerJob job, Roaming roaming) {
        Optional<ResourceCollectionProfile> profile = job.jobType().profile(ResourceCollectionProfile.class);
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (profile.isEmpty() || site.isEmpty()) {
            return Optional.empty();
        }
        long day = DayClock.day(level);
        RoamingContext context = new RoamingContext(level, site.get(), settler.blockPosition(), level.getGameTime(),
                pos -> OccupiedPlaces.isFreeFor(site.get().settlement(), pos, settler.getUUID(), day));
        return roaming.moveOn(context).map(stand -> JobStep.walk(stand, profile.get().reach(), JobStatus.WORKING));
    }

    private static boolean stillWorkPlace(ServerLevel level, SettlerJob job, BlockPos target) {
        return job.jobType().profile(ResourceCollectionProfile.class).map(profile -> profile.isWorkPlace(level, target)).orElse(true);
    }

    /**
     * At the collect inventory: takes the tool and the inputs of the innate recipes (at a working Clerk's counter after
     * waiting for its turn).
     */
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
            boolean taken = WorkRules.refill(settler, job, profile.get(), site.get(), stock);
            if (!taken) {
                // Nothing better after all: the next search waits an hour.
                shell.toolNeeds().searched(job, level.getGameTime());
            }
            List<CollectionRecipes.Offer> offered = CollectionRecipes.offered(level, job.jobType(), profile.get(), shell.workplace(job.jobBlock()).scan());
            taken |= CollectionRecipes.refill(settler, CollectionRecipes.allowed(offered, site.get().block().excluded(job.jobType()),
                    StockRules.atMax(level, site.get())), stock);
            if (taken && stock.counter()) {
                ClerkCounter.handOut(level, collect, true);
            }
        }
        toolVisit = null;
        return JobStep.DONE;
    }

    /** Shows the best tool of the job in the hand. */
    private void showTool(SettlerJob job, ResourceCollectionProfile profile) {
        shell.hold(shell.tool(job, profile).map(ItemStack::copy).orElse(ItemStack.EMPTY));
    }

    /**
     * @param stand     where the settler stands: it looks, swings and hits there
     * @param rollPlace the place the roll names ({@link Roaming#rollPlace})
     */
    private void produce(ServerLevel level, SettlerJob job, BlockPos stand, BlockPos rollPlace) {
        BlockPos jobBlock = job.jobBlock();
        settler.getLookControl().setLookAt(Vec3.atCenterOf(stand));
        if (++swingTimer >= SWING_INTERVAL && job.jobType().profile(ResourceCollectionProfile.class).map(ResourceCollectionProfile::swings).orElse(true)) {
            swingTimer = 0;
            settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            BlockState state = level.getBlockState(stand);
            level.playSound(null, stand, state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        Optional<ResourceCollectionProfile> profile = job.jobType().profile(ResourceCollectionProfile.class);
        Optional<JobBlockSite> site = JobBlockSite.at(level, jobBlock);
        if (profile.isEmpty() || site.isEmpty()) {
            return;
        }
        JobBuffer buffer = shell.workplace(jobBlock).buffer();
        // A Laborer works a fraction of the tick: the rolls come slower, the rules stay the same.
        WorkRules.Result result = WorkRules.work(level, settler, job, profile.get(), site.get(), shell.workplace(jobBlock), rollPlace, shell.laborer().speed(job),
                WorkRules.ToolSupply.BELT, new BufferOutput(buffer, stacks -> {
                    stacks.forEach(buffer::add);
                    return !buffer.isFull();
                }));
        if (result.levelUp()) {
            shell.showLevelUp(level, job);
        }
        // The line stays out while the job produces; a job that ended (no tool, buffer full) takes it in.
        if (profile.get() instanceof FishingProfile && result.end() == WorkRules.End.NONE) {
            settler.cast(poi);
        }
        // A job that ended (no tool, buffer full, no resources) is re-planned at the next decision.
        showTool(job, profile.get());
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
            BlockPos poi = placeOfDay(level, job, profile.get(), site, workplace, day);
            if (poi == null) {
                return true;
            }
            workplace.ensureScan(level, job.jobType(), profile.get(), site, poi, day, dayTime);
            return false;
        }
        return true;
    }

    /** The game time of the simulated moment of catch-up (the search throttle of the tools counts in game time). */
    private long simulatedGameTime(ServerLevel level, long day) {
        long simulated = day * DayClock.DAY_TICKS + shell.catchUpDayTime();
        return level.getGameTime() - (level.getOverworldClockTime() - simulated);
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
        if (found.isPresent() && profile.isPresent() && workplace.scan() == null && shell.laborer().isHelper(job)) {
            // A Laborer takes the scan its JobBlock kept for the Job, along with the shared work place.
            placeOfDay(level, job, profile.get(), found.get(), workplace, day);
        }
        BlockPos poi = found.isEmpty() || profile.isEmpty() || workplace.scan() == null ? null
                : placeOfDay(level, job, profile.get(), found.get(), workplace, day);
        if (poi == null) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        JobBlockSite site = found.get();
        // A Laborer works at a fraction of the speed: the same time gives fewer work ticks.
        double speed = shell.laborer().speed(job);
        WorkRules.Result result = WorkRules.work(level, settler, job, profile.get(), site, workplace, poi, workTicks * speed,
                WorkRules.ToolSupply.beltAnd(ClerkStorage.stock(level, site.collect(), shell.catchUpDayTime(), true),
                        () -> ClerkCounter.handOut(level, site.collect(), false), simulatedGameTime(level, day)), new BufferOutput(workplace.buffer(), stacks -> shell.deliverDirect(level, site, stacks)));
        if (result.end() == WorkRules.End.NONE) {
            return new SimulatedWork(result.rolls(), 0.0);
        }
        shell.endCatchUpJob(job.jobBlock());
        // The next job takes over the rest of the time.
        return new SimulatedWork(result.rolls(), result.ticksLeft() / speed);
    }
}
