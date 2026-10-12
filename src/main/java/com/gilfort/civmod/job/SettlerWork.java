package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Work behaviour of a settler (vault {@code Systems/Resource Collection Jobs.md}): picks the first job of its
 * priority list that can work, walks to tools / work place / drop-off and produces resources.
 * <p>
 * Decisions run every {@value #DECISION_INTERVAL} ticks (staggered per settler); movement uses vanilla navigation,
 * production and animation are cheap per-tick counters while working.
 */
public final class SettlerWork {

    /** Ticks between decisions. */
    public static final int DECISION_INTERVAL = 100;
    /** Ticks a blocked job waits before it is checked again. */
    private static final int BLOCKED_RETRY = 600;
    /** Work hours in day ticks (06:00–17:30; day time 0 = 06:00). */
    public static final long WORK_END = 11500;
    private static final double REACH = 2.5;
    private static final int SWING_INTERVAL = 10;
    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    private static final int AUTO_SEARCH_VERTICAL = 30;
    /** Skill level used until EXP exists (Story 8). */
    private static final int SKILL_LEVEL = 1;

    private enum Activity { IDLE, FETCH_TOOL, WORK, DELIVER }

    /** Save codec of the per-job buffers. */
    private static final Codec<List<BufferEntry>> BUFFERS_CODEC = RecordCodecBuilder.<BufferEntry>create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("job_block").forGetter(BufferEntry::jobBlock),
            JobBuffer.CODEC.fieldOf("items").forGetter(BufferEntry::buffer)
    ).apply(instance, BufferEntry::new)).listOf();

    /**
     * A saved buffer.
     *
     * @param jobBlock the JobBlock of the job
     * @param buffer   the buffer
     */
    public record BufferEntry(BlockPos jobBlock, JobBuffer buffer) {
    }

    private final Settler settler;
    private final Map<BlockPos, JobBuffer> buffers = new HashMap<>();
    private final Map<BlockPos, JobStatus> statuses = new HashMap<>();
    private final Map<BlockPos, Long> blockedUntil = new HashMap<>();
    private final Map<BlockPos, WoodScan.Result> scans = new HashMap<>();
    private final Map<BlockPos, Long> scanDay = new HashMap<>();
    private final Set<BlockPos> scanning = new HashSet<>();
    private final Map<BlockPos, BlockPos> poiOfDay = new HashMap<>();
    private final Map<BlockPos, Long> poiDay = new HashMap<>();
    private final Fatigue fatigue = new Fatigue();

    private Activity activity = Activity.IDLE;
    private @Nullable SettlerJob activeJob;
    private @Nullable BlockPos target;
    private double production;
    private int swingTimer;
    private double bestDistance = Double.MAX_VALUE;
    private long lastProgressTick;

    /**
     * @param settler the settler
     */
    public SettlerWork(Settler settler) {
        this.settler = settler;
    }

    /**
     * @return the goal that drives movement and work while the settler has something to do
     */
    public Goal goal() {
        return new WorkGoal();
    }

    // ---------------------------------------------------------------- persistence

    /**
     * @return the codec value of the buffers
     */
    public BuffersSnapshot snapshot() {
        return new BuffersSnapshot(buffers.entrySet().stream().map(e -> new BufferEntry(e.getKey(), e.getValue())).toList(),
                poiOfDay.entrySet().stream().map(e -> new WorkPlaceEntry(e.getKey(), e.getValue(), poiDay.getOrDefault(e.getKey(), -1L))).toList());
    }

    /**
     * The work place of the day of a job.
     *
     * @param jobBlock the JobBlock of the job
     * @param poi      the work place
     * @param day      the day it was chosen for
     */
    public record WorkPlaceEntry(BlockPos jobBlock, BlockPos poi, long day) {
        /** Save codec. */
        public static final Codec<WorkPlaceEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("job_block").forGetter(WorkPlaceEntry::jobBlock),
                BlockPos.CODEC.fieldOf("poi").forGetter(WorkPlaceEntry::poi),
                Codec.LONG.fieldOf("day").forGetter(WorkPlaceEntry::day)
        ).apply(instance, WorkPlaceEntry::new));
    }

    /**
     * Saved work state: buffers and the work places of the day.
     *
     * @param entries    the buffers
     * @param workPlaces the work places of the day
     */
    public record BuffersSnapshot(List<BufferEntry> entries, List<WorkPlaceEntry> workPlaces) {
        private static final Codec<BuffersSnapshot> CURRENT = RecordCodecBuilder.create(instance -> instance.group(
                BUFFERS_CODEC.optionalFieldOf("buffers", List.of()).forGetter(BuffersSnapshot::entries),
                WorkPlaceEntry.CODEC.listOf().optionalFieldOf("work_places", List.of()).forGetter(BuffersSnapshot::workPlaces)
        ).apply(instance, BuffersSnapshot::new));

        /** Save codec; also reads the earlier format (a plain list of buffers). */
        public static final Codec<BuffersSnapshot> CODEC = Codec.withAlternative(CURRENT,
                BUFFERS_CODEC.xmap(entries -> new BuffersSnapshot(entries, List.of()), BuffersSnapshot::entries));
    }

    /**
     * Restores saved buffers.
     *
     * @param snapshot the saved buffers
     */
    public void restore(BuffersSnapshot snapshot) {
        buffers.clear();
        snapshot.entries().forEach(entry -> buffers.put(entry.jobBlock(), entry.buffer()));
        poiOfDay.clear();
        poiDay.clear();
        snapshot.workPlaces().forEach(entry -> {
            poiOfDay.put(entry.jobBlock(), entry.poi());
            poiDay.put(entry.jobBlock(), entry.day());
        });
    }

    // ---------------------------------------------------------------- queries for the GUI

    /**
     * @param jobBlock a JobBlock position
     * @return the status of that job
     */
    public JobStatus status(BlockPos jobBlock) {
        return statuses.getOrDefault(jobBlock, JobStatus.IDLE);
    }

    /**
     * @param jobBlock a JobBlock position
     * @return the buffer of that job (empty if none)
     */
    public JobBuffer buffer(BlockPos jobBlock) {
        return buffers.getOrDefault(jobBlock, new JobBuffer());
    }

    /**
     * @return the job the settler currently works on
     */
    public Optional<SettlerJob> activeJob() {
        return Optional.ofNullable(activeJob);
    }

    /**
     * Forgets everything about a removed job (its buffer is dropped at the settler's position).
     *
     * @param jobBlock the JobBlock position
     */
    public void forget(BlockPos jobBlock) {
        JobBuffer buffer = buffers.remove(jobBlock);
        if (buffer != null && settler.level() instanceof ServerLevel level) {
            buffer.stacks().forEach(stack -> settler.spawnAtLocation(level, stack.copy()));
        }
        statuses.remove(jobBlock);
        scans.remove(jobBlock);
        poiOfDay.remove(jobBlock);
        if (activeJob != null && activeJob.jobBlock().equals(jobBlock)) {
            stop();
        }
    }

    // ---------------------------------------------------------------- decisions

    /**
     * Called every tick from the settler's AI step; decides every {@value #DECISION_INTERVAL} ticks.
     *
     * @param level the level
     */
    public void aiStep(ServerLevel level) {
        if ((settler.tickCount + settler.getId()) % DECISION_INTERVAL == 0) {
            decide(level);
        }
    }

    private void decide(ServerLevel level) {
        long dayTime = Math.floorMod(level.getOverworldClockTime(), 24000L);
        long day = Math.floorDiv(level.getOverworldClockTime(), 24000L);
        checkStuck(level);

        if (activity == Activity.WORK && activeJob != null) {
            if (fatigue.advance(DECISION_INTERVAL, level.getRandom())) {
                statuses.put(activeJob.jobBlock(), JobStatus.PAUSING);
                settler.getNavigation().stop();
                return;
            }
        }

        if (dayTime >= WORK_END || settler.isHomeless()) {
            // After work: deliver what is left, then free time.
            for (SettlerJob job : settler.getJobs()) {
                if (!buffer(job.jobBlock()).isEmpty() && status(job.jobBlock()) != JobStatus.DROP_OFF_FULL) {
                    startDelivery(level, job);
                    return;
                }
                statuses.put(job.jobBlock(), JobStatus.IDLE);
            }
            stop();
            return;
        }

        for (SettlerJob job : settler.getJobs()) {
            if (blockedUntil.getOrDefault(job.jobBlock(), 0L) > level.getGameTime()) {
                continue;
            }
            if (plan(level, job, day, dayTime)) {
                return;
            }
        }
        // No job can work: Laborer fallback (idle in the PoC).
        stop();
    }

    /**
     * Plans the next step of a job.
     *
     * @return whether the job can work (the settler is busy with it)
     */
    private boolean plan(ServerLevel level, SettlerJob job, long day, long dayTime) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> block = data.settlementAt(job.jobBlock()).flatMap(s -> s.block(job.jobBlock()));
        if (block.isEmpty()) {
            return false;
        }
        BlockPos jobBlock = job.jobBlock();
        JobBuffer buffer = buffers.computeIfAbsent(jobBlock, pos -> new JobBuffer());

        if (buffer.shouldDeliver()) {
            return startDelivery(level, job);
        }
        if (bestTool(level, ItemStackPredicates.AXE).isEmpty()) {
            return fetchTool(level, job, block.get(), ItemStackPredicates.AXE);
        }
        if (block.get().collectLeaves() && bestTool(level, ItemStackPredicates.SHEARS).isEmpty()
                && findInContainer(level, collectPos(block.get()), ItemStackPredicates.SHEARS).isPresent()) {
            return fetchTool(level, job, block.get(), ItemStackPredicates.SHEARS);
        }
        BlockPos poi = poiOfDay(level, block.get(), day);
        if (poi == null) {
            block(level, jobBlock, JobStatus.NO_WORK_PLACE);
            return false;
        }
        ensureScan(level, block.get(), day, dayTime);
        activeJob = job;
        if (activity != Activity.WORK || !poi.equals(target)) {
            moveTo(level, Activity.WORK, poi);
        }
        statuses.put(jobBlock, JobStatus.WORKING);
        showTool(level, ItemStackPredicates.AXE);
        return true;
    }

    private boolean startDelivery(ServerLevel level, SettlerJob job) {
        Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(job.jobBlock()).flatMap(s -> s.block(job.jobBlock()));
        BlockPos dropOff = block.flatMap(SettlementBlock::dropOff).orElse(job.jobBlock());
        activeJob = job;
        statuses.put(job.jobBlock(), JobStatus.DELIVERING);
        if (activity != Activity.DELIVER || !dropOff.equals(target)) {
            moveTo(level, Activity.DELIVER, dropOff);
        }
        return true;
    }

    private boolean fetchTool(ServerLevel level, SettlerJob job, SettlementBlock block, ItemStackPredicates kind) {
        BlockPos collect = collectPos(block);
        if (findInContainer(level, collect, kind).isEmpty()) {
            block(level, job.jobBlock(), JobStatus.NO_TOOL);
            return false;
        }
        activeJob = job;
        statuses.put(job.jobBlock(), JobStatus.FETCHING_TOOL);
        if (activity != Activity.FETCH_TOOL || !collect.equals(target)) {
            moveTo(level, Activity.FETCH_TOOL, collect);
        }
        return true;
    }

    private static BlockPos collectPos(SettlementBlock block) {
        return block.collect().orElse(block.pos());
    }

    private void block(ServerLevel level, BlockPos jobBlock, JobStatus status) {
        statuses.put(jobBlock, status);
        blockedUntil.put(jobBlock, level.getGameTime() + BLOCKED_RETRY);
        if (activeJob != null && activeJob.jobBlock().equals(jobBlock)) {
            stop();
        }
    }

    private void stop() {
        activity = Activity.IDLE;
        activeJob = null;
        target = null;
        settler.getNavigation().stop();
        settler.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private void moveTo(ServerLevel level, Activity next, BlockPos pos) {
        activity = next;
        target = pos.immutable();
        bestDistance = Double.MAX_VALUE;
        lastProgressTick = level.getGameTime();
        settler.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6);
    }

    private void checkStuck(ServerLevel level) {
        if (activity == Activity.IDLE || target == null || isAtTarget()) {
            return;
        }
        double distance = settler.position().distanceTo(Vec3.atCenterOf(target));
        if (distance < bestDistance - 1.0) {
            bestDistance = distance;
            lastProgressTick = level.getGameTime();
        } else if (level.getGameTime() - lastProgressTick > CivModConfig.ANTI_STUCK_SECONDS.get() * 20L) {
            Vec3 spot = Vec3.atBottomCenterOf(target.above());
            settler.teleportTo(spot.x, spot.y, spot.z);
            settler.getNavigation().stop();
            lastProgressTick = level.getGameTime();
        } else if (settler.getNavigation().isDone()) {
            settler.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
        }
    }

    private boolean isAtTarget() {
        if (target == null) {
            return false;
        }
        Vec3 center = Vec3.atCenterOf(target);
        double dx = settler.getX() - center.x;
        double dz = settler.getZ() - center.z;
        return dx * dx + dz * dz <= REACH * REACH && Math.abs(settler.getY() - target.getY()) <= 3.0;
    }

    // ---------------------------------------------------------------- work places and scans

    private @Nullable BlockPos poiOfDay(ServerLevel level, SettlementBlock block, long day) {
        BlockPos current = poiOfDay.get(block.pos());
        if (current != null && poiDay.getOrDefault(block.pos(), -1L) == day && isLog(level, current)) {
            return current;
        }
        List<BlockPos> candidates = new ArrayList<>(block.workPoints().stream().filter(pos -> isLog(level, pos)).toList());
        BlockPos chosen = candidates.isEmpty() ? autoSearch(level, block.pos())
                : candidates.get(level.getRandom().nextInt(candidates.size()));
        if (chosen != null) {
            poiOfDay.put(block.pos(), chosen);
            poiDay.put(block.pos(), day);
        }
        return chosen;
    }

    private static boolean isLog(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.LOGS);
    }

    /**
     * Finds the nearest log around a JobBlock (±{@value #AUTO_SEARCH_HORIZONTAL} / ±{@value #AUTO_SEARCH_VERTICAL});
     * runs at most once per day and job, sections without logs are skipped via their palette.
     */
    private static @Nullable BlockPos autoSearch(ServerLevel level, BlockPos center) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int sx = SectionPos.blockToSectionCoord(center.getX() - AUTO_SEARCH_HORIZONTAL);
                sx <= SectionPos.blockToSectionCoord(center.getX() + AUTO_SEARCH_HORIZONTAL); sx++) {
            for (int sz = SectionPos.blockToSectionCoord(center.getZ() - AUTO_SEARCH_HORIZONTAL);
                    sz <= SectionPos.blockToSectionCoord(center.getZ() + AUTO_SEARCH_HORIZONTAL); sz++) {
                if (!level.hasChunk(sx, sz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(sx, sz);
                for (int sy = SectionPos.blockToSectionCoord(center.getY() - AUTO_SEARCH_VERTICAL);
                        sy <= SectionPos.blockToSectionCoord(center.getY() + AUTO_SEARCH_VERTICAL); sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= chunk.getSectionsCount()) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(index);
                    if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(BlockTags.LOGS))) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (!section.getBlockState(x, y, z).is(BlockTags.LOGS)) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(SectionPos.sectionToBlockCoord(sx) + x, SectionPos.sectionToBlockCoord(sy) + y,
                                        SectionPos.sectionToBlockCoord(sz) + z);
                                if (Math.abs(pos.getX() - center.getX()) > AUTO_SEARCH_HORIZONTAL
                                        || Math.abs(pos.getZ() - center.getZ()) > AUTO_SEARCH_HORIZONTAL
                                        || Math.abs(pos.getY() - center.getY()) > AUTO_SEARCH_VERTICAL) {
                                    continue;
                                }
                                double distance = pos.distSqr(center);
                                if (distance < bestDistance) {
                                    bestDistance = distance;
                                    best = pos;
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private void ensureScan(ServerLevel level, SettlementBlock block, long day, long dayTime) {
        BlockPos jobBlock = block.pos();
        if (scanDay.getOrDefault(jobBlock, -1L) == day || scanning.contains(jobBlock)) {
            return;
        }
        BlockPos origin = block.workPoints().isEmpty() ? poiOfDay.getOrDefault(jobBlock, jobBlock) : block.workPoints().get(0);
        scanning.add(jobBlock);
        // Spread scans over the first real minute of the workday; later starts (e.g. after loading) begin at once.
        long delay = dayTime < 1200 ? level.getRandom().nextInt(1200) : 0;
        ScanScheduler.submit(level, new WoodScan(origin, SettlementRules.joinRadius(block.level())), delay, result -> {
            scans.put(jobBlock, result);
            scanDay.put(jobBlock, day);
            scanning.remove(jobBlock);
        });
    }

    // ---------------------------------------------------------------- tools

    private Optional<ItemStack> bestTool(ServerLevel level, ItemStackPredicates kind) {
        ItemStack best = ItemStack.EMPTY;
        int maxTier = ToolTiers.maxAllowedTier(SKILL_LEVEL);
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (stack.isEmpty() || !kind.test(stack) || ToolTiers.tier(stack) > maxTier) {
                continue;
            }
            if (best.isEmpty() || ToolTiers.tier(stack) > ToolTiers.tier(best)) {
                best = stack;
            }
        }
        return best.isEmpty() ? Optional.empty() : Optional.of(best);
    }

    private void showTool(ServerLevel level, ItemStackPredicates kind) {
        ItemStack tool = bestTool(level, kind).map(ItemStack::copy).orElse(ItemStack.EMPTY);
        if (!ItemStack.matches(tool, settler.getItemBySlot(EquipmentSlot.MAINHAND))) {
            settler.setItemSlot(EquipmentSlot.MAINHAND, tool);
        }
    }

    private static Optional<Integer> findInContainer(ServerLevel level, BlockPos pos, ItemStackPredicates kind) {
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        if (handler == null) {
            return Optional.empty();
        }
        int bestIndex = -1;
        int bestTier = -1;
        for (int i = 0; i < handler.size(); i++) {
            ItemResource resource = handler.getResource(i);
            if (resource.isEmpty()) {
                continue;
            }
            ItemStack stack = resource.toStack();
            if (kind.test(stack) && ToolTiers.tier(stack) <= ToolTiers.maxAllowedTier(SKILL_LEVEL) && ToolTiers.tier(stack) > bestTier) {
                bestTier = ToolTiers.tier(stack);
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? Optional.empty() : Optional.of(bestIndex);
    }

    private void takeTool(ServerLevel level, BlockPos pos, ItemStackPredicates kind) {
        int freeSlot = -1;
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            if (settler.getInventory().getItem(i).isEmpty()) {
                freeSlot = i;
                break;
            }
        }
        Optional<Integer> index = findInContainer(level, pos, kind);
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        if (freeSlot < 0 || index.isEmpty() || handler == null) {
            return;
        }
        ItemResource resource = handler.getResource(index.get());
        try (Transaction transaction = Transaction.openRoot()) {
            if (handler.extract(index.get(), resource, 1, transaction) == 1) {
                settler.getInventory().setItem(freeSlot, resource.toStack(1));
                transaction.commit();
            }
        }
    }

    // ---------------------------------------------------------------- per-tick work

    private void workTick(ServerLevel level) {
        if (activity == Activity.IDLE || target == null || activeJob == null) {
            return;
        }
        if (!isAtTarget()) {
            return;
        }
        BlockPos jobBlock = activeJob.jobBlock();
        switch (activity) {
            case FETCH_TOOL -> {
                takeTool(level, target, ItemStackPredicates.AXE);
                Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(jobBlock).flatMap(s -> s.block(jobBlock));
                if (block.map(SettlementBlock::collectLeaves).orElse(false) && bestTool(level, ItemStackPredicates.SHEARS).isEmpty()) {
                    takeTool(level, target, ItemStackPredicates.SHEARS);
                }
                activity = Activity.IDLE;
                decide(level);
            }
            case DELIVER -> {
                deliver(level, jobBlock, target);
                activity = Activity.IDLE;
                decide(level);
            }
            case WORK -> {
                if (fatigue.onBreak()) {
                    return;
                }
                produce(level, jobBlock, target);
            }
            default -> {
            }
        }
    }

    private void deliver(ServerLevel level, BlockPos jobBlock, BlockPos dropOff) {
        JobBuffer buffer = buffer(jobBlock);
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, dropOff, null);
        boolean all = handler != null && buffer.deliver(stack -> {
            try (Transaction transaction = Transaction.openRoot()) {
                int inserted = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(stack), stack.getCount(), transaction);
                transaction.commit();
                return inserted;
            }
        });
        if (!all) {
            block(level, jobBlock, JobStatus.DROP_OFF_FULL);
        }
    }

    private void produce(ServerLevel level, BlockPos jobBlock, BlockPos poi) {
        settler.getLookControl().setLookAt(Vec3.atCenterOf(poi));
        if (++swingTimer >= SWING_INTERVAL) {
            swingTimer = 0;
            settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            BlockState state = level.getBlockState(poi);
            level.playSound(null, poi, state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        WoodScan.Result scan = scans.getOrDefault(jobBlock, WoodScan.Result.EMPTY);
        JobBuffer buffer = buffers.computeIfAbsent(jobBlock, pos -> new JobBuffer());
        Optional<ItemStack> axe = bestTool(level, ItemStackPredicates.AXE);
        if (axe.isEmpty() || buffer.isFull()) {
            return;
        }
        double perSecond = LumberjackOutput.speed(SKILL_LEVEL) * ToolTiers.multiplier(ToolTiers.tier(axe.get()))
                * LumberjackOutput.productivity(scan.totalLogs());
        production += perSecond / 20.0;
        Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(jobBlock).flatMap(s -> s.block(jobBlock));
        boolean leavesEnabled = block.map(SettlementBlock::collectLeaves).orElse(false);
        Identifier poiLog = BuiltInRegistries.BLOCK.getKey(level.getBlockState(poi).getBlock());
        while (production >= 1.0) {
            production -= 1.0;
            Optional<ItemStack> currentAxe = bestTool(level, ItemStackPredicates.AXE);
            if (currentAxe.isEmpty()) {
                return;
            }
            Optional<ItemStack> shears = bestTool(level, ItemStackPredicates.SHEARS);
            boolean silkTouch = hasSilkTouch(level, currentAxe.get());
            Optional<LumberjackOutput.Produced> produced = LumberjackOutput.produce(scan, poiLog,
                    leavesEnabled && shears.isPresent(), silkTouch, level.getRandom());
            if (produced.isEmpty()) {
                return;
            }
            buffer.add(produced.get().stack());
            ItemStack worn = produced.get().kind() == LumberjackOutput.Kind.LEAVES && !silkTouch ? shears.orElse(currentAxe.get()) : currentAxe.get();
            wear(level, worn);
        }
        showTool(level, ItemStackPredicates.AXE);
    }

    private void wear(ServerLevel level, ItemStack tool) {
        double multiplier = CivModConfig.TOOL_WEAR_MULTIPLIER.get();
        int damage = (int) multiplier + (level.getRandom().nextDouble() < multiplier - Math.floor(multiplier) ? 1 : 0);
        if (damage > 0) {
            tool.hurtAndBreak(damage, level, settler, item -> { });
        }
    }

    private static boolean hasSilkTouch(ServerLevel level, ItemStack stack) {
        Holder<Enchantment> silkTouch = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        return stack.getEnchantmentLevel(silkTouch) > 0;
    }

    /** Tool kinds used by the Lumberjack. */
    enum ItemStackPredicates {
        /** Axes. */
        AXE,
        /** Shears. */
        SHEARS;

        boolean test(ItemStack stack) {
            return switch (this) {
                case AXE -> stack.is(net.minecraft.tags.ItemTags.AXES);
                case SHEARS -> stack.is(Items.SHEARS) || stack.is(net.neoforged.neoforge.common.Tags.Items.TOOLS_SHEAR);
            };
        }
    }

    /**
     * Drives movement and work while the settler is busy; blocks idle strolling.
     */
    private final class WorkGoal extends Goal {

        WorkGoal() {
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return activity != Activity.IDLE;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (settler.level() instanceof ServerLevel level) {
                workTick(level);
            }
        }
    }
}
