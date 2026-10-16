package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Resource Collection Job Category (vault {@code Systems/Resource Collection Jobs.md}); its contract
 * with the Job is the {@link JobProfile}. Per job: fetch the tool, take the work place of the day and the scan from the
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

    /**
     * @param shell the Settler Shell driving this loop
     */
    ResourceCollectionLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public boolean plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        BlockPos jobBlock = job.jobBlock();
        Workplace workplace = shell.workplace(jobBlock);

        if (workplace.buffer().shouldDeliver()) {
            return shell.startDelivery(level, job);
        }
        Optional<JobProfile> found = job.jobType().profile();
        if (found.isEmpty()) {
            return false;
        }
        JobProfile profile = found.get();
        if (SettlerTools.best(settler, profile::isTool).isEmpty()) {
            return fetchTool(level, job, site, profile::isTool);
        }
        Optional<Predicate<ItemStack>> secondary = profile.secondaryTool(site.block());
        if (secondary.isPresent() && SettlerTools.best(settler, secondary.get()).isEmpty()
                && SettlerTools.findInContainer(level, site.collect(), secondary.get()).isPresent()) {
            return fetchTool(level, job, site, secondary.get());
        }
        BlockPos poi = workplace.workPlaceOfDay(level, profile, site, day);
        if (poi == null) {
            shell.block(level, jobBlock, JobStatus.NO_WORK_PLACE);
            return false;
        }
        workplace.ensureScan(level, profile, site, poi, day, dayTime);
        if (profile.needsResources() && workplace.foundNothing()) {
            shell.block(level, jobBlock, JobStatus.NO_RESOURCES);
            return false;
        }
        shell.setActiveJob(job);
        if (step != Step.WORK || !shell.walkingTo(poi)) {
            step = Step.WORK;
            shell.moveToJob(level, poi);
        }
        shell.setStatus(jobBlock, JobStatus.WORKING);
        SettlerTools.show(settler, profile::isTool);
        return true;
    }

    private boolean fetchTool(ServerLevel level, SettlerJob job, JobBlockSite site, Predicate<ItemStack> kind) {
        BlockPos collect = site.collect();
        if (SettlerTools.findInContainer(level, collect, kind).isEmpty()) {
            shell.block(level, job.jobBlock(), JobStatus.NO_TOOL);
            return false;
        }
        shell.setActiveJob(job);
        shell.setStatus(job.jobBlock(), JobStatus.FETCHING_TOOL);
        if (step != Step.FETCH_TOOL || !shell.walkingTo(collect)) {
            step = Step.FETCH_TOOL;
            shell.moveToJob(level, collect);
        }
        return true;
    }

    @Override
    public boolean holdsDecision(ServerLevel level, SettlerJob job) {
        if (step == Step.WORK && fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom())) {
            shell.setStatus(job.jobBlock(), JobStatus.PAUSING);
            settler.getNavigation().stop();
            return true;
        }
        return false;
    }

    @Override
    public double reach(SettlerJob job) {
        return step == Step.WORK ? job.jobType().profile().map(JobProfile::reach).orElse(SettlerWork.REACH) : SettlerWork.REACH;
    }

    // ---------------------------------------------------------------- per-tick work

    @Override
    public void tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        BlockPos jobBlock = job.jobBlock();
        switch (step) {
            case FETCH_TOOL -> {
                Optional<JobProfile> profile = job.jobType().profile();
                Optional<JobBlockSite> site = JobBlockSite.at(level, jobBlock);
                if (profile.isPresent() && site.isPresent()) {
                    WorkRules.refill(level, settler, profile.get(), site.get());
                }
                shell.stepDone(level);
            }
            case WORK -> {
                if (fatigue.onBreak()) {
                    return;
                }
                shell.countWorkTick(job);
                produce(level, job, target);
            }
        }
    }

    private void produce(ServerLevel level, SettlerJob job, BlockPos poi) {
        BlockPos jobBlock = job.jobBlock();
        settler.getNavigation().stop();
        settler.getLookControl().setLookAt(Vec3.atCenterOf(poi));
        if (++swingTimer >= SWING_INTERVAL && job.jobType().profile().map(JobProfile::swings).orElse(true)) {
            swingTimer = 0;
            settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            BlockState state = level.getBlockState(poi);
            level.playSound(null, poi, state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        Optional<JobProfile> profile = job.jobType().profile();
        Optional<JobBlockSite> site = JobBlockSite.at(level, jobBlock);
        if (profile.isEmpty() || site.isEmpty()) {
            return;
        }
        JobBuffer buffer = shell.workplace(jobBlock).buffer();
        WorkRules.Result result = WorkRules.work(level, settler, job, profile.get(), site.get(), shell.workplace(jobBlock), poi, 1.0,
                WorkRules.ToolSupply.BELT, new WorkRules.Output() {
                    @Override
                    public boolean hasRoom() {
                        return !buffer.isFull();
                    }

                    @Override
                    public boolean accept(List<ItemStack> stacks) {
                        stacks.forEach(buffer::add);
                        return !buffer.isFull();
                    }
                });
        if (result.levelUp()) {
            shell.showLevelUp(level, job);
        }
        // A job that ended (no tool, buffer full, no resources) is re-planned at the next decision.
        SettlerTools.show(settler, profile.get()::isTool);
    }

    // ---------------------------------------------------------------- catch-up

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        Optional<JobProfile> profile = job.jobType().profile();
        if (profile.isEmpty()) {
            return true;
        }
        if (!level.isLoaded(site.pos()) || !level.isLoaded(site.collect()) || !level.isLoaded(site.dropOff())) {
            return false;
        }
        Workplace workplace = shell.workplace(job.jobBlock());
        if (workplace.scan() == null) {
            BlockPos poi = workplace.workPlaceOfDay(level, profile.get(), site, day);
            if (poi == null) {
                return true;
            }
            workplace.ensureScan(level, profile.get(), site, poi, day, dayTime);
            return false;
        }
        return true;
    }

    @Override
    public void startCatchUp() {
        // The fractional production lives in each job's Workplace and carries over.
    }

    /**
     * Produces the output of some work ticks during catch-up through the {@link WorkRules}: directly into the
     * drop-off, then the buffer; missing tools are taken from the collect inventory.
     */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        Optional<JobBlockSite> found = JobBlockSite.at(level, job.jobBlock());
        Optional<JobProfile> profile = job.jobType().profile();
        Workplace workplace = shell.workplace(job.jobBlock());
        BlockPos poi = found.isEmpty() || profile.isEmpty() || workplace.scan() == null ? null
                : workplace.workPlaceOfDay(level, profile.get(), found.get(), day);
        if (poi == null) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        JobBlockSite site = found.get();
        JobBuffer buffer = workplace.buffer();
        WorkRules.Result result = WorkRules.work(level, settler, job, profile.get(), site, workplace, poi, workTicks,
                WorkRules.ToolSupply.BELT_AND_COLLECT, new WorkRules.Output() {
                    @Override
                    public boolean hasRoom() {
                        return !buffer.isFull();
                    }

                    @Override
                    public boolean accept(List<ItemStack> stacks) {
                        return shell.deliverDirect(level, site, stacks);
                    }
                });
        if (result.end() == WorkRules.End.NONE) {
            return new SimulatedWork(result.rolls(), 0.0);
        }
        shell.endCatchUpJob(job.jobBlock());
        // The next job takes over the rest of the time.
        return new SimulatedWork(result.rolls(), result.ticksLeft());
    }

    @Override
    public List<JobProfile.Found> found(SettlerJob job) {
        ScanResult scan = shell.existingWorkplace(job.jobBlock()).map(Workplace::scan).orElse(null);
        Optional<JobProfile> profile = job.jobType().profile();
        if (scan == null || profile.isEmpty()) {
            return List.of();
        }
        ItemStack tool = SettlerTools.best(settler, profile.get()::isTool).orElse(ItemStack.EMPTY);
        return profile.get().found(scan, tool, shell.effectiveLevel(job));
    }
}
