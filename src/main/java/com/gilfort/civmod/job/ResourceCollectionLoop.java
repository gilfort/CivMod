package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Resource Collection Job Category (vault {@code Systems/Resource Collection Jobs.md}); its contract
 * with the Job is the {@link JobProfile}. Per job: fetch the tool, choose the work place of the day, scan in the
 * background, walk there and produce into the per-job buffer, which the shell delivers at ~80 % or at the end of the
 * day. Production and animation are cheap per-tick counters while working.
 */
final class ResourceCollectionLoop implements CategoryLoop {

    private static final int SWING_INTERVAL = 10;

    /** The step of the loop the settler is in. */
    private enum Step { FETCH_TOOL, WORK }

    /** What a cached scan was made for; a different key triggers a rescan. */
    private record ScanKey(BlockPos center, int radius, long day) {
    }

    private final SettlerWork shell;
    private final Settler settler;
    private final Map<BlockPos, ScanResult> scans = new HashMap<>();
    private final Map<BlockPos, ScanKey> scanKeys = new HashMap<>();
    /** Scans in flight per JobBlock; a result is only stored while its key is still the pending one. */
    private final Map<BlockPos, ScanKey> scanning = new HashMap<>();
    private final Map<BlockPos, BlockPos> poiOfDay = new HashMap<>();
    private final Map<BlockPos, Long> poiDay = new HashMap<>();
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

    // ---------------------------------------------------------------- persistence

    /**
     * @return the work places of the day for saving
     */
    @Override
    public List<SettlerWork.WorkPlaceEntry> workPlaces() {
        return poiOfDay.entrySet().stream()
                .map(e -> new SettlerWork.WorkPlaceEntry(e.getKey(), e.getValue(), poiDay.getOrDefault(e.getKey(), -1L))).toList();
    }

    /**
     * @return the cached scans for saving
     */
    @Override
    public List<SavedScan> savedScans() {
        List<SavedScan> saved = new ArrayList<>();
        scans.forEach((jobBlock, result) -> {
            ScanKey key = scanKeys.get(jobBlock);
            if (key != null) {
                SavedScan.of(jobBlock, key.center(), key.radius(), key.day(), result).ifPresent(saved::add);
            }
        });
        return saved;
    }

    /**
     * Restores saved work places and scans.
     *
     * @param snapshot the saved work state
     */
    @Override
    public void restore(SettlerWork.BuffersSnapshot snapshot) {
        poiOfDay.clear();
        poiDay.clear();
        snapshot.workPlaces().forEach(entry -> {
            poiOfDay.put(entry.jobBlock(), entry.poi());
            poiDay.put(entry.jobBlock(), entry.day());
        });
        snapshot.scans().forEach(scan -> {
            ScanResult result = scan.result();
            if (result != null) {
                scans.put(scan.jobBlock(), result);
                scanKeys.put(scan.jobBlock(), new ScanKey(scan.center(), scan.radius(), scan.day()));
            }
        });
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public boolean plan(ServerLevel level, SettlerJob job, SettlementBlock block, long day, long dayTime) {
        BlockPos jobBlock = job.jobBlock();
        JobBuffer buffer = shell.jobBuffer(jobBlock);

        if (buffer.shouldDeliver()) {
            return shell.startDelivery(level, job);
        }
        Optional<JobProfile> found = job.jobType().profile();
        if (found.isEmpty()) {
            return false;
        }
        JobProfile profile = found.get();
        if (SettlerTools.best(settler, profile::isTool).isEmpty()) {
            return fetchTool(level, job, block, profile::isTool);
        }
        Optional<Predicate<ItemStack>> secondary = profile.secondaryTool(block);
        if (secondary.isPresent() && SettlerTools.best(settler, secondary.get()).isEmpty()
                && SettlerTools.findInContainer(level, collectPos(block), secondary.get()).isPresent()) {
            return fetchTool(level, job, block, secondary.get());
        }
        BlockPos poi = poiOfDay(level, profile, block, day);
        if (poi == null) {
            shell.block(level, jobBlock, JobStatus.NO_WORK_PLACE);
            return false;
        }
        ensureScan(level, profile, block, poi, day, dayTime);
        ScanResult scan = scans.get(jobBlock);
        if (profile.needsResources() && scan != null && scan.isEmpty() && !scanning.containsKey(jobBlock)) {
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

    private boolean fetchTool(ServerLevel level, SettlerJob job, SettlementBlock block, Predicate<ItemStack> kind) {
        BlockPos collect = collectPos(block);
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

    private static BlockPos collectPos(SettlementBlock block) {
        return block.collect().orElse(block.pos());
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
                Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(jobBlock).flatMap(s -> s.block(jobBlock));
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
        Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(jobBlock).flatMap(s -> s.block(jobBlock));
        if (found.isEmpty() || block.isEmpty()) {
            return;
        }
        JobProfile profile = found.get();
        ScanResult scan = scans.get(jobBlock);
        JobBuffer buffer = shell.jobBuffer(jobBlock);
        Optional<ItemStack> tool = SettlerTools.best(settler, profile::isTool);
        if (tool.isEmpty() || buffer.isFull()) {
            return;
        }
        int skillLevel = settler.getSkills().effectiveLevel(job.jobType(), SettlementRules.skillCap(block.get().level()));
        double perSecond = profile.speed(skillLevel, tool.get()) * profile.productivity(scan);
        production += perSecond / 20.0;
        while (production >= 1.0) {
            production -= 1.0;
            Optional<ItemStack> current = SettlerTools.best(settler, profile::isTool);
            if (current.isEmpty()) {
                return;
            }
            Optional<ItemStack> secondary = profile.secondaryTool(block.get()).flatMap(kind -> SettlerTools.best(settler, kind));
            Optional<JobProfile.Produced> produced = profile.produce(new JobProfile.Context(level, settler, scan, poi, current.get(), secondary,
                    block.get(), skillLevel));
            if (produced.isEmpty()) {
                continue;
            }
            produced.get().stacks().forEach(buffer::add);
            SettlerTools.wear(level, settler, produced.get().worn());
            if (!produced.get().stacks().isEmpty()) {
                shell.gainExp(level, job, block.get().level());
                skillLevel = settler.getSkills().effectiveLevel(job.jobType(), SettlementRules.skillCap(block.get().level()));
            }
        }
        SettlerTools.show(settler, profile::isTool);
    }

    // ---------------------------------------------------------------- catch-up

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, SettlementBlock block, long day, long dayTime) {
        Optional<JobProfile> profile = job.jobType().profile();
        if (profile.isEmpty()) {
            return true;
        }
        if (!level.isLoaded(block.pos()) || !level.isLoaded(collectPos(block)) || !level.isLoaded(block.dropOff().orElse(block.pos()))) {
            return false;
        }
        if (!scans.containsKey(job.jobBlock())) {
            BlockPos poi = poiOfDay(level, profile.get(), block, day);
            if (poi == null) {
                return true;
            }
            ensureScan(level, profile.get(), block, poi, day, dayTime);
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
        Optional<SettlementBlock> found = SettlementData.get(level).settlementAt(job.jobBlock()).flatMap(s -> s.block(job.jobBlock()));
        Optional<JobProfile> profile = job.jobType().profile();
        ScanResult scan = scans.get(job.jobBlock());
        if (found.isEmpty() || profile.isEmpty() || scan == null) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        SettlementBlock block = found.get();
        BlockPos poi = poiOfDay(level, profile.get(), block, day);
        if (poi == null || (profile.get().needsResources() && scan.isEmpty())) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        Optional<ItemStack> tool = toolForCatchUp(level, profile.get(), block);
        if (tool.isEmpty()) {
            shell.endCatchUpJob(job.jobBlock());
            return new SimulatedWork(0, workTicks);
        }
        int rolls = 0;
        double remaining = 0.0;
        int cap = SettlementRules.skillCap(block.level());
        int skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
        cuProduction += profile.get().speed(skillLevel, tool.get()) * profile.get().productivity(scan) * workTicks / 20.0;
        while (cuProduction >= 1.0) {
            cuProduction -= 1.0;
            tool = toolForCatchUp(level, profile.get(), block);
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
            if (!shell.deliverDirect(level, block, produced.get().stacks())) {
                shell.endCatchUpJob(job.jobBlock());
                // The next job takes over the rest of the time (approximated by the rest of this hour's output).
                remaining = cuProduction * 20.0;
                cuProduction = 0.0;
                break;
            }
            shell.gainExp(level, job, block.level());
            skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
        }
        return new SimulatedWork(rolls, remaining);
    }

    private Optional<ItemStack> toolForCatchUp(ServerLevel level, JobProfile profile, SettlementBlock block) {
        Optional<ItemStack> tool = SettlerTools.best(settler, profile::isTool);
        if (tool.isEmpty() && SettlerTools.findInContainer(level, collectPos(block), profile::isTool).isPresent()) {
            SettlerTools.take(level, settler, collectPos(block), profile::isTool);
            tool = SettlerTools.best(settler, profile::isTool);
        }
        return tool;
    }

    // ---------------------------------------------------------------- work places and scans

    private @Nullable BlockPos poiOfDay(ServerLevel level, JobProfile profile, SettlementBlock block, long day) {
        BlockPos current = poiOfDay.get(block.pos());
        if (current != null && poiDay.getOrDefault(block.pos(), -1L) == day && profile.isWorkPlace(level, current)) {
            return current;
        }
        List<BlockPos> candidates = new ArrayList<>(block.workPoints().stream().filter(pos -> profile.isWorkPlace(level, pos)).toList());
        BlockPos chosen = candidates.isEmpty() ? profile.autoSearch(level, block.pos())
                : candidates.get(level.getRandom().nextInt(candidates.size()));
        if (chosen != null) {
            poiOfDay.put(block.pos(), chosen);
            poiDay.put(block.pos(), day);
        }
        return chosen;
    }

    private void ensureScan(ServerLevel level, JobProfile profile, SettlementBlock block, BlockPos poi, long day, long dayTime) {
        BlockPos jobBlock = block.pos();
        ScanKey key = new ScanKey(profile.scanCenter(block, poi), SettlementRules.joinRadius(block.level()), profile.rescanDaily() ? day : -1L);
        if (key.equals(scanKeys.get(jobBlock)) || scanning.containsKey(jobBlock)) {
            return;
        }
        scanning.put(jobBlock, key);
        // Spread daily scans over the first real minute of the workday; later starts (e.g. after loading) begin at once.
        long delay = profile.rescanDaily() && dayTime < SettlerWork.SCAN_SPREAD_TICKS ? level.getRandom().nextInt(SettlerWork.SCAN_SPREAD_TICKS) : 0;
        ScanScheduler.submit(level, profile.createScan(key.center(), key.radius()), delay, result -> {
            // Identity check: a scan of a forgotten job (possibly re-added since with an equal key) must not land.
            if (scanning.get(jobBlock) != key) {
                return;
            }
            scans.put(jobBlock, result);
            scanKeys.put(jobBlock, key);
            scanning.remove(jobBlock);
        });
    }

    @Override
    public void forget(BlockPos jobBlock) {
        scans.remove(jobBlock);
        scanKeys.remove(jobBlock);
        scanning.remove(jobBlock);
        poiOfDay.remove(jobBlock);
        poiDay.remove(jobBlock);
    }

    @Override
    public List<JobProfile.Found> found(SettlerJob job) {
        ScanResult scan = scans.get(job.jobBlock());
        Optional<JobProfile> profile = job.jobType().profile();
        if (scan == null || profile.isEmpty()) {
            return List.of();
        }
        ItemStack tool = SettlerTools.best(settler, profile.get()::isTool).orElse(ItemStack.EMPTY);
        return profile.get().found(scan, tool, shell.effectiveLevel(job));
    }
}
