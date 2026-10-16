package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;

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
 * the day. Production and animation are cheap per-tick counters while working.
 */
final class ResourceCollectionLoop implements CategoryLoop {

    private static final int SWING_INTERVAL = 10;

    /** The step of the loop the settler is in. */
    private enum Step { FETCH_TOOL, WORK }

    private final SettlerWork shell;
    private final Settler settler;
    private final Fatigue fatigue = new Fatigue();

    private Step step = Step.WORK;
    private double production;
    private int swingTimer;
    private double cuProduction;

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
                Optional<SettlementBlock> block = JobBlockSite.at(level, jobBlock).map(JobBlockSite::block);
                if (profile.isPresent()) {
                    if (SettlerTools.best(settler, profile.get()::isTool).isEmpty()) {
                        SettlerTools.take(level, settler, target, profile.get()::isTool);
                    }
                    Optional<Predicate<ItemStack>> secondary = block.flatMap(b -> profile.get().secondaryTool(b));
                    if (secondary.isPresent() && SettlerTools.best(settler, secondary.get()).isEmpty()) {
                        SettlerTools.take(level, settler, target, secondary.get());
                    }
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
        Optional<JobProfile> found = job.jobType().profile();
        Optional<JobBlockSite> site = JobBlockSite.at(level, jobBlock);
        if (found.isEmpty() || site.isEmpty()) {
            return;
        }
        JobProfile profile = found.get();
        SettlementBlock block = site.get().block();
        Workplace workplace = shell.workplace(jobBlock);
        ScanResult scan = workplace.scan();
        JobBuffer buffer = workplace.buffer();
        Optional<ItemStack> tool = SettlerTools.best(settler, profile::isTool);
        if (tool.isEmpty() || buffer.isFull()) {
            return;
        }
        int skillLevel = settler.getSkills().effectiveLevel(job.jobType(), site.get().skillCap());
        double perSecond = profile.speed(skillLevel, tool.get()) * profile.productivity(scan);
        production += perSecond / 20.0;
        while (production >= 1.0) {
            production -= 1.0;
            Optional<ItemStack> current = SettlerTools.best(settler, profile::isTool);
            if (current.isEmpty()) {
                return;
            }
            Optional<ItemStack> secondary = profile.secondaryTool(block).flatMap(kind -> SettlerTools.best(settler, kind));
            Optional<JobProfile.Produced> produced = profile.produce(new JobProfile.Context(level, settler, scan, poi, current.get(), secondary,
                    block, skillLevel));
            if (produced.isEmpty()) {
                continue;
            }
            produced.get().stacks().forEach(buffer::add);
            SettlerTools.wear(level, settler, produced.get().worn());
            if (!produced.get().stacks().isEmpty()) {
                shell.gainExp(level, job, site.get().level());
                skillLevel = settler.getSkills().effectiveLevel(job.jobType(), site.get().skillCap());
            }
        }
        SettlerTools.show(settler, profile::isTool);
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
        cuProduction = 0.0;
    }

    /**
     * Produces the output of some work ticks during catch-up: directly into the drop-off, then the buffer.
     */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        Optional<JobBlockSite> found = JobBlockSite.at(level, job.jobBlock());
        Optional<JobProfile> profile = job.jobType().profile();
        Workplace workplace = shell.workplace(job.jobBlock());
        ScanResult scan = workplace.scan();
        if (found.isEmpty() || profile.isEmpty() || scan == null) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        JobBlockSite site = found.get();
        SettlementBlock block = site.block();
        BlockPos poi = workplace.workPlaceOfDay(level, profile.get(), site, day);
        if (poi == null || (profile.get().needsResources() && scan.isEmpty())) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        Optional<ItemStack> tool = toolForCatchUp(level, profile.get(), site);
        if (tool.isEmpty()) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        int rolls = 0;
        double remaining = 0.0;
        int cap = site.skillCap();
        int skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
        cuProduction += profile.get().speed(skillLevel, tool.get()) * profile.get().productivity(scan) * workTicks / 20.0;
        while (cuProduction >= 1.0) {
            cuProduction -= 1.0;
            tool = toolForCatchUp(level, profile.get(), site);
            if (tool.isEmpty()) {
                shell.endCatchUpJob(job.jobBlock());
                break;
            }
            Optional<ItemStack> secondary = profile.get().secondaryTool(block).flatMap(kind -> SettlerTools.best(settler, kind));
            Optional<JobProfile.Produced> produced = profile.get().produce(new JobProfile.Context(level, settler, scan, poi, tool.get(),
                    secondary, block, skillLevel));
            rolls++;
            if (produced.isEmpty()) {
                continue;
            }
            SettlerTools.wear(level, settler, produced.get().worn());
            if (produced.get().stacks().isEmpty()) {
                continue;
            }
            if (!shell.deliverDirect(level, site, produced.get().stacks())) {
                shell.endCatchUpJob(job.jobBlock());
                // The next job takes over the rest of the time (approximated by the rest of this hour's output).
                remaining = cuProduction * 20.0;
                cuProduction = 0.0;
                break;
            }
            shell.gainExp(level, job, site.level());
            skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
        }
        return new SimulatedWork(rolls, remaining);
    }

    private Optional<ItemStack> toolForCatchUp(ServerLevel level, JobProfile profile, JobBlockSite site) {
        Optional<ItemStack> tool = SettlerTools.best(settler, profile::isTool);
        if (tool.isEmpty() && SettlerTools.findInContainer(level, site.collect(), profile::isTool).isPresent()) {
            SettlerTools.take(level, settler, site.collect(), profile::isTool);
            tool = SettlerTools.best(settler, profile::isTool);
        }
        return tool;
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
