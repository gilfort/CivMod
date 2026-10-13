package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.Pantries;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
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
    /** Tool restrictions by skill level are not active in the PoC (see the open topic in the vault). */
    private static final int TOOL_LEVEL = SkillRules.MAX_LEVEL;
    /** Players within this distance get the level-up message. */
    private static final double LEVEL_UP_RANGE = 32.0;

    private enum Activity { IDLE, FETCH_TOOL, WORK, DELIVER, GO_TO_BED, SLEEP, FETCH_FOOD, EATING, FORAGE }

    /** Why a settler walks to a pantry. */
    private enum MealPurpose { MORNING, HUNGRY, EVENING }

    /** Ticks a meal from the pockets takes. */
    private static final int EAT_TICKS = 30;
    /** Ticks a foraging settler walks before it vanishes. */
    private static final int FORAGE_WALK_TICKS = 600;
    /** Players within this distance get the hunger message. */
    private static final double COMPLAIN_RANGE = 32.0;

    /** Day time at which settlers go to sleep (20:00). */
    public static final long SLEEP_TIME = 14000;
    /** Stroll radius around the bed in the evening. */
    private static final int REST_RADIUS = 5;

    /**
     * Where a settler spends the night.
     *
     * @param pos   the bed head, straw bed spot or ground spot
     * @param bed   whether it is a real bed
     * @param straw the straw bed spot (null for a bed or the bare ground)
     */
    public record RestPlace(BlockPos pos, boolean bed, SettlerBeds.@Nullable StrawSpot straw) {
    }

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
    private final Map<BlockPos, ScanResult> scans = new HashMap<>();
    private final Map<BlockPos, ScanKey> scanKeys = new HashMap<>();
    private final Set<BlockPos> scanning = new HashSet<>();
    private final Map<BlockPos, BlockPos> poiOfDay = new HashMap<>();
    private final Map<BlockPos, Long> poiDay = new HashMap<>();
    private final Fatigue fatigue = new Fatigue();

    /** What a cached scan was made for; a different key triggers a rescan. */
    private record ScanKey(BlockPos center, int radius, long day) {
    }

    private Activity activity = Activity.IDLE;
    private @Nullable SettlerJob activeJob;
    private @Nullable BlockPos target;
    private double production;
    private int swingTimer;
    private double bestDistance = Double.MAX_VALUE;
    private long lastProgressTick;
    private long lastClock = -1;
    private @Nullable RestPlace restPlace;
    private long restPlaceDay = -1;
    private double weightedWorkTicks;
    private int eatTimer;
    private boolean eatUntilFull;
    private boolean hungryNoFood;
    private long forageStart;
    private @Nullable MealPurpose mealPurpose;

    // Catch-up (vault Systems/Settlement.md): missed time while unloaded.
    /** Minimum missed time that is caught up (2 real minutes). */
    public static final long CATCH_UP_MIN_GAP = 2400;
    /** Marks "no day" (days may be negative in tests with a frozen clock). */
    private static final long NO_DAY = Long.MIN_VALUE;
    private long savedClock = -1;
    private long catchUpFrom = -1;
    private long catchUpTo = -1;
    private boolean catchUpRegistered;
    private boolean quiet;
    private double cuProduction;
    private long cuForageDay = NO_DAY;
    private long cuStarvingDay = NO_DAY;
    private long cuMorningDay = NO_DAY;
    private long cuEveningDay = NO_DAY;
    private final Set<BlockPos> cuDone = new HashSet<>();
    private int cuItems;
    private int cuMeals;
    private double cuDays;

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
        List<SavedScan> saved = new ArrayList<>();
        scans.forEach((jobBlock, result) -> {
            ScanKey key = scanKeys.get(jobBlock);
            if (key != null) {
                SavedScan.of(jobBlock, key.center(), key.radius(), key.day(), result).ifPresent(saved::add);
            }
        });
        long clock = settler.level() instanceof ServerLevel level ? level.getOverworldClockTime() : -1L;
        return new BuffersSnapshot(buffers.entrySet().stream().map(e -> new BufferEntry(e.getKey(), e.getValue())).toList(),
                poiOfDay.entrySet().stream().map(e -> new WorkPlaceEntry(e.getKey(), e.getValue(), poiDay.getOrDefault(e.getKey(), -1L))).toList(),
                saved, new CatchUpState(clock, catchUpFrom, catchUpTo));
    }

    /**
     * Catch-up bookkeeping saved with the settler.
     *
     * @param activeClock the clock at saving (the settler was active until then)
     * @param from        start of missed time not caught up yet, -1 if none
     * @param to          end of that missed time
     */
    public record CatchUpState(long activeClock, long from, long to) {
        /** Save codec. */
        public static final Codec<CatchUpState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("active_clock").forGetter(CatchUpState::activeClock),
                Codec.LONG.optionalFieldOf("from", -1L).forGetter(CatchUpState::from),
                Codec.LONG.optionalFieldOf("to", -1L).forGetter(CatchUpState::to)
        ).apply(instance, CatchUpState::new));

        /** No catch-up information (older saves). */
        public static final CatchUpState NONE = new CatchUpState(-1L, -1L, -1L);
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
    public record BuffersSnapshot(List<BufferEntry> entries, List<WorkPlaceEntry> workPlaces, List<SavedScan> scans, CatchUpState catchUp) {

        /**
         * @param entries    the buffers
         * @param workPlaces the work places of the day
         */
        public BuffersSnapshot(List<BufferEntry> entries, List<WorkPlaceEntry> workPlaces) {
            this(entries, workPlaces, List.of(), CatchUpState.NONE);
        }

        private static final Codec<BuffersSnapshot> CURRENT = RecordCodecBuilder.create(instance -> instance.group(
                BUFFERS_CODEC.optionalFieldOf("buffers", List.of()).forGetter(BuffersSnapshot::entries),
                WorkPlaceEntry.CODEC.listOf().optionalFieldOf("work_places", List.of()).forGetter(BuffersSnapshot::workPlaces),
                SavedScan.CODEC.listOf().optionalFieldOf("scans", List.of()).forGetter(BuffersSnapshot::scans),
                CatchUpState.CODEC.optionalFieldOf("catch_up", CatchUpState.NONE).forGetter(BuffersSnapshot::catchUp)
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
        snapshot.scans().forEach(saved -> {
            ScanResult result = saved.result();
            if (result != null) {
                scans.put(saved.jobBlock(), result);
                scanKeys.put(saved.jobBlock(), new ScanKey(saved.center(), saved.radius(), saved.day()));
            }
        });
        savedClock = snapshot.catchUp().activeClock();
        catchUpFrom = snapshot.catchUp().from();
        catchUpTo = snapshot.catchUp().to();
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
     * Called every tick from the settler's AI step; settles hunger and decides every {@value #DECISION_INTERVAL} ticks.
     *
     * @param level the level
     */
    public void aiStep(ServerLevel level) {
        if (savedClock >= 0) {
            // First tick after loading: remember missed time for the catch-up simulation.
            long now = level.getOverworldClockTime();
            if (now - savedClock >= CATCH_UP_MIN_GAP && !settler.isHomeless()) {
                catchUpFrom = catchUpFrom >= 0 ? Math.min(catchUpFrom, savedClock) : savedClock;
                catchUpTo = now;
            }
            savedClock = -1;
        }
        if ((settler.tickCount + settler.getId()) % DECISION_INTERVAL == 0) {
            long profile = com.gilfort.civmod.debug.CivProfiler.start();
            accountHunger(level);
            if (catchUpFrom >= 0 && !catchUpRegistered && catchUpReady(level)) {
                catchUpRegistered = true;
                CatchUp.register(level, settler);
            }
            decide(level);
            com.gilfort.civmod.debug.CivProfiler.stop(com.gilfort.civmod.debug.CivProfiler.Section.DECISIONS, profile);
        }
    }

    /**
     * @return where the settler spends (or spent) the night, if already decided
     */
    public Optional<RestPlace> restPlace() {
        return Optional.ofNullable(restPlace);
    }

    private void decide(ServerLevel level) {
        long clock = level.getOverworldClockTime();
        long dayTime = Math.floorMod(clock, 24000L);
        long day = Math.floorDiv(clock, 24000L);
        boolean jumped = SettlerBeds.nightSkipped(lastClock, clock, DECISION_INTERVAL, WORK_END);
        lastClock = clock;
        boolean night = dayTime >= SLEEP_TIME;
        boolean evening = dayTime >= WORK_END && !night;
        SettlerFood food = settler.getFood();

        if (settler.isSleeping()) {
            if (night) {
                return;
            }
            wake();
        } else if (jumped && !night && !settler.isHomeless() && !food.isForaging()) {
            // The night was skipped while this settler was not in bed: appear at the bed as if it slept there.
            teleportToRestPlace(level, day);
        }
        if (food.isForaging()) {
            forage(level, day, dayTime);
            return;
        }
        if (food.level() >= SettlerFood.HUNGRY) {
            hungryNoFood = false;
        }
        checkStuck(level);
        if (activity == Activity.EATING || activity == Activity.FETCH_FOOD) {
            // A meal in progress is finished first (also after 20:00).
            return;
        }

        if (activity == Activity.WORK && activeJob != null) {
            if (fatigue.advance(DECISION_INTERVAL, level.getRandom())) {
                statuses.put(activeJob.jobBlock(), JobStatus.PAUSING);
                settler.getNavigation().stop();
                return;
            }
        }

        if (settler.isHomeless()) {
            restPlace = null;
            stop();
            if (food.level() < SettlerFood.HUNGRY && !startPocketMeal(false)) {
                complain(level, day);
            }
            return;
        }
        if (evening || night) {
            // After work: deliver what is left, eat, then rest near the bed and sleep from 20:00.
            for (SettlerJob job : settler.getJobs()) {
                if (!buffer(job.jobBlock()).isEmpty() && status(job.jobBlock()) != JobStatus.DROP_OFF_FULL) {
                    startDelivery(level, job);
                    return;
                }
                statuses.put(job.jobBlock(), JobStatus.IDLE);
            }
            if (evening && food.eatenDay() != day) {
                food.setEatenDay(day);
                if (food.level() < SettlerFood.MAX) {
                    Optional<BlockPos> pantry = pantryWithFood(level);
                    if (pantry.isPresent()) {
                        stop();
                        startFetchFood(level, pantry.get(), MealPurpose.EVENING);
                        return;
                    }
                    // Provisions are only eaten in the evening if no pantry has food.
                    if (startPocketMeal(true)) {
                        return;
                    }
                }
            }
            if (food.level() < SettlerFood.HUNGRY && SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) == 0
                    && pantryWithFood(level).isEmpty()) {
                complain(level, day);
            }
            RestPlace rest = ensureRestPlace(level, day);
            if (rest != null) {
                settler.setHomeTo(rest.pos(), REST_RADIUS);
            }
            if (night && rest != null) {
                if (activity != Activity.GO_TO_BED && activity != Activity.SLEEP) {
                    stop();
                    moveTo(level, Activity.GO_TO_BED, rest.pos());
                }
            } else {
                stop();
            }
            return;
        }
        restPlace = null;

        if (food.morningDay() != day) {
            food.setMorningDay(day);
            int provisions = SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE);
            Optional<BlockPos> pantry = pantryWithFood(level);
            MorningPlan plan = morningPlan(food.level(), provisions, CivModConfig.PROVISION_NUTRITION.get(), pantry.isPresent());
            if (plan == MorningPlan.FORAGE) {
                startForaging(level, day);
                return;
            }
            if (plan == MorningPlan.FETCH_PROVISIONS) {
                stop();
                startFetchFood(level, pantry.get(), MealPurpose.MORNING);
                return;
            }
        }
        if (food.level() < SettlerFood.HUNGRY) {
            if (startPocketMeal(false)) {
                return;
            }
            Optional<BlockPos> pantry = pantryWithFood(level);
            if (pantry.isPresent()) {
                stop();
                startFetchFood(level, pantry.get(), MealPurpose.HUNGRY);
                return;
            }
            complain(level, day);
            if (food.level() <= 0) {
                // Starving: no work until there is food again; forages the next day.
                stop();
                return;
            }
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
        Optional<JobProfile> found = JobProfiles.of(job.jobType());
        if (found.isEmpty()) {
            return false;
        }
        JobProfile profile = found.get();
        if (bestTool(profile::isTool).isEmpty()) {
            return fetchTool(level, job, block.get(), profile::isTool);
        }
        Optional<Predicate<ItemStack>> secondary = profile.secondaryTool(block.get());
        if (secondary.isPresent() && bestTool(secondary.get()).isEmpty()
                && findInContainer(level, collectPos(block.get()), secondary.get()).isPresent()) {
            return fetchTool(level, job, block.get(), secondary.get());
        }
        BlockPos poi = poiOfDay(level, profile, block.get(), day);
        if (poi == null) {
            block(level, jobBlock, JobStatus.NO_WORK_PLACE);
            return false;
        }
        ensureScan(level, profile, block.get(), poi, day, dayTime);
        ScanResult scan = scans.get(jobBlock);
        if (profile.needsResources() && scan != null && scan.isEmpty() && !scanning.contains(jobBlock)) {
            block(level, jobBlock, JobStatus.NO_RESOURCES);
            return false;
        }
        activeJob = job;
        if (activity != Activity.WORK || !poi.equals(target)) {
            moveTo(level, Activity.WORK, poi);
        }
        statuses.put(jobBlock, JobStatus.WORKING);
        showTool(profile::isTool);
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

    private boolean fetchTool(ServerLevel level, SettlerJob job, SettlementBlock block, Predicate<ItemStack> kind) {
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

    private @Nullable RestPlace ensureRestPlace(ServerLevel level, long day) {
        if (restPlace != null && restPlaceDay == day && (!restPlace.bed() || SettlerBeds.head(level, restPlace.pos()).isPresent())) {
            return restPlace;
        }
        SettlementData data = SettlementData.get(level);
        Optional<com.gilfort.civmod.settlement.Settlement> settlement = data.settlement(settler.getSettlementId());
        if (settlement.isEmpty()) {
            return null;
        }
        Optional<BlockPos> bed = SettlerBeds.ensureBed(level, data, settlement.get(), settler.getUUID(), settler.blockPosition());
        if (bed.isPresent()) {
            restPlace = new RestPlace(bed.get(), true, null);
        } else {
            BlockPos jobBlock = settler.getJobs().stream().findFirst().map(SettlerJob::jobBlock)
                    .or(() -> settlement.get().anchor().map(SettlementBlock::pos)).orElse(settler.blockPosition());
            restPlace = SettlerBeds.reserveStrawSpot(level, jobBlock, settler.getUUID())
                    .map(spot -> new RestPlace(spot.pos(), false, spot))
                    .orElse(new RestPlace(jobBlock.above(), false, null));
        }
        restPlaceDay = day;
        return restPlace;
    }

    private void teleportToRestPlace(ServerLevel level, long day) {
        RestPlace rest = ensureRestPlace(level, day - 1);
        if (rest != null) {
            Vec3 spot = rest.straw() != null ? Vec3.atBottomCenterOf(rest.pos()) : standingSpot(level, rest.pos());
            settler.teleportTo(spot.x, spot.y, spot.z);
            settler.getNavigation().stop();
        }
        restPlace = null;
    }

    private void sleep(ServerLevel level) {
        RestPlace rest = restPlace;
        settler.getNavigation().stop();
        activity = Activity.SLEEP;
        if (rest != null && rest.bed() && !level.getBlockState(rest.pos()).getValue(BedBlock.OCCUPIED)
                && settler.startSleeping(rest.pos())) {
            return;
        }
        if (rest != null && rest.straw() != null) {
            settler.sleepOnStraw(rest.straw(), true);
        } else {
            settler.sleepOnStraw(new SettlerBeds.StrawSpot(settler.blockPosition(), settler.getDirection()), false);
        }
    }

    private void wake() {
        settler.wakeUp();
        SettlerBeds.releaseStrawSpot(settler.getUUID());
        activity = Activity.IDLE;
        target = null;
        restPlace = null;
    }

    /**
     * @return the area to stroll in during the evening (bed / straw bed spot), if any
     */
    public Optional<BlockPos> restArea() {
        return Optional.ofNullable(restPlace).map(RestPlace::pos);
    }

    /**
     * @return the stroll radius around the rest area
     */
    public static int restRadius() {
        return REST_RADIUS;
    }

    private void stop() {
        activity = Activity.IDLE;
        activeJob = null;
        target = null;
        settler.getNavigation().stop();
        settler.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private void moveTo(ServerLevel level, Activity next, BlockPos pos) {
        stepOffBed(level);
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
            Vec3 spot = standingSpot(level, target, reach());
            settler.teleportTo(spot.x, spot.y, spot.z);
            settler.getNavigation().stop();
            lastProgressTick = level.getGameTime();
        } else if (settler.getNavigation().isDone()) {
            stepOffBed(level);
            settler.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
        }
    }

    /**
     * Finds a free spot to stand on next to a target (work place, container, bed), so a teleport never puts the
     * settler inside a block (e.g. into the trunk above a log) or on top of a bed. Spots are within reach of the
     * target, so the settler can work from there.
     *
     * @param level  the level
     * @param target the target block
     * @return the feet position
     */
    public static Vec3 standingSpot(ServerLevel level, BlockPos target) {
        return standingSpot(level, target, REACH);
    }

    /**
     * Finds a free spot to stand on within the given reach of a target.
     *
     * @param level  the level
     * @param target the target block
     * @param reach  the horizontal reach
     * @return the feet position
     */
    public static Vec3 standingSpot(ServerLevel level, BlockPos target, double reach) {
        BlockState state = level.getBlockState(target);
        if (state.getBlock() instanceof AbstractBedBlock && state.hasProperty(BedBlock.FACING)) {
            Optional<Vec3> standUp = AbstractBedBlock.findStandUpPosition(ModEntityTypes.SETTLER.get(), level, target,
                    state.getValue(BedBlock.FACING), 0.0F);
            if (standUp.isPresent()) {
                return standUp.get();
            }
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int search = (int) Math.floor(reach);
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-search, -2, -search), target.offset(search, 3, search))) {
            int dx = pos.getX() - target.getX();
            int dz = pos.getZ() - target.getZ();
            if (dx * dx + dz * dz <= reach * reach && canStand(level, pos)) {
                // Prefer spots at the target's height.
                double distance = pos.distSqr(target) + 2.0 * Math.abs(pos.getY() - target.getY());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
        }
        if (best != null) {
            return Vec3.atBottomCenterOf(best);
        }
        BlockPos up = target.above();
        for (int i = 0; i < 32 && !canStand(level, up); i++) {
            up = up.above();
        }
        return Vec3.atBottomCenterOf(up);
    }

    static boolean canStand(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getFluidState(pos).isEmpty()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * Gets down from a bed the settler stands on (beds are blocked for the pathfinder): moves to the vanilla stand-up
     * position, like waking up.
     */
    private void stepOffBed(ServerLevel level) {
        if (settler.isSleeping()) {
            return;
        }
        BlockPos feet = settler.blockPosition();
        BlockPos bed = level.getBlockState(feet).getBlock() instanceof AbstractBedBlock ? feet
                : level.getBlockState(feet.below()).getBlock() instanceof AbstractBedBlock ? feet.below() : null;
        if (bed != null) {
            Vec3 spot = standingSpot(level, bed);
            settler.teleportTo(spot.x, spot.y, spot.z);
        }
    }

    private boolean isAtTarget() {
        if (target == null) {
            return false;
        }
        double reach = reach();
        Vec3 center = Vec3.atCenterOf(target);
        double dx = settler.getX() - center.x;
        double dz = settler.getZ() - center.z;
        return dx * dx + dz * dz <= reach * reach && Math.abs(settler.getY() - target.getY()) <= 3.0;
    }

    /**
     * @return the reach for the current target (the job's reach while working, otherwise {@value #REACH})
     */
    private double reach() {
        if (activity == Activity.WORK && activeJob != null) {
            return JobProfiles.of(activeJob.jobType()).map(JobProfile::reach).orElse(REACH);
        }
        return REACH;
    }

    // ---------------------------------------------------------------- catch-up

    /**
     * @return whether missed time is waiting to be caught up
     */
    public boolean hasPendingCatchUp() {
        return catchUpFrom >= 0;
    }

    /**
     * Sets missed time to catch up (tests).
     *
     * @param from clock at which the settler was unloaded
     * @param to   clock at which it was loaded again
     */
    public void setCatchUp(long from, long to) {
        catchUpFrom = from;
        catchUpTo = to;
        catchUpRegistered = false;
    }

    /**
     * @return start of the missed time (capped to the maximum number of days)
     */
    public long catchUpFrom() {
        long earliest = catchUpTo - CivModConfig.CATCH_UP_MAX_DAYS.get() * 24000L;
        return Math.max(catchUpFrom, earliest);
    }

    /**
     * @return end of the missed time
     */
    public long catchUpTo() {
        return catchUpTo;
    }

    /**
     * Whether all blocks the settler needs are loaded and a scan result exists (starts the scan otherwise).
     */
    private boolean catchUpReady(ServerLevel level) {
        SettlementData data = SettlementData.get(level);
        Optional<com.gilfort.civmod.settlement.Settlement> settlement = data.settlement(settler.getSettlementId());
        if (settlement.isEmpty()) {
            catchUpFrom = -1;
            return false;
        }
        for (BlockPos pantry : settlement.get().pantries()) {
            if (!level.isLoaded(pantry)) {
                return false;
            }
        }
        long day = Math.floorDiv(level.getOverworldClockTime(), 24000L);
        for (SettlerJob job : settler.getJobs()) {
            Optional<SettlementBlock> block = settlement.get().block(job.jobBlock());
            Optional<JobProfile> profile = JobProfiles.of(job.jobType());
            if (block.isEmpty() || profile.isEmpty()) {
                continue;
            }
            if (!level.isLoaded(block.get().pos()) || !level.isLoaded(collectPos(block.get()))
                    || !level.isLoaded(block.get().dropOff().orElse(block.get().pos()))) {
                return false;
            }
            if (!scans.containsKey(job.jobBlock())) {
                BlockPos poi = poiOfDay(level, profile.get(), block.get(), day);
                if (poi == null) {
                    continue;
                }
                ensureScan(level, profile.get(), block.get(), poi, day, Math.floorMod(level.getOverworldClockTime(), 24000L));
                return false;
            }
        }
        return true;
    }

    /**
     * Prepares a catch-up run.
     */
    void startCatchUp() {
        cuDays = Math.max(0, catchUpTo - catchUpFrom()) / (double) 24000L;
        quiet = true;
        cuProduction = 0.0;
        cuDone.clear();
        cuItems = 0;
        cuMeals = 0;
        cuForageDay = NO_DAY;
        cuStarvingDay = NO_DAY;
        cuMorningDay = NO_DAY;
        cuEveningDay = NO_DAY;
    }

    /**
     * Ends a catch-up run and logs what happened.
     *
     * @param level the level
     */
    void finishCatchUp(ServerLevel level) {
        CivMod.LOGGER.info("Catch-up {}: {} day(s), {} item(s), {} meal(s), skills {}", settler.getName().getString(),
                String.format(java.util.Locale.ROOT, "%.1f", cuDays), cuItems, cuMeals,
                settler.getSkills().snapshot());
        catchUpFrom = -1;
        catchUpTo = -1;
        catchUpRegistered = false;
        quiet = false;
        cuDone.forEach(jobBlock -> statuses.put(jobBlock, JobStatus.DROP_OFF_FULL));
    }

    /**
     * Simulates one hour of missed time (hour 0 = 06:00).
     *
     * @param level the level
     * @param day   the day
     * @param hour  the hour of the awake day (0–13)
     * @return the number of production rolls
     */
    int simulateHour(ServerLevel level, long day, int hour) {
        long hourStart = day * 24000L + hour * 1000L;
        long from = Math.max(hourStart, catchUpFrom());
        long to = Math.min(hourStart + 1000L, catchUpTo);
        if (to <= from || settler.isHomeless()) {
            return 0;
        }
        // Progress is saved: a settler unloaded during the run continues later instead of repeating hours.
        catchUpFrom = Math.max(catchUpFrom, to);
        long awake = to - from;
        long workEnd = day * 24000L + WORK_END;
        long work = Math.max(0, Math.min(to, workEnd) - from);
        SettlerFood food = settler.getFood();
        int provisionWanted = CivModConfig.PROVISION_NUTRITION.get();

        if (cuMorningDay != day) {
            cuMorningDay = day;
            boolean pocketFood = SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) > 0;
            Optional<BlockPos> pantry = pantryWithFood(level);
            if (food.level() < SettlerFood.HUNGRY && !pocketFood && pantry.isEmpty()) {
                cuForageDay = day;
            } else if (pantry.isPresent()
                    && SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) < provisionWanted) {
                mealPurpose = MealPurpose.MORNING;
                visitPantry(level, pantry.get());
                mealPurpose = null;
            }
        }
        if (cuForageDay == day) {
            if (hour == 11) {
                food.addFood(CivModConfig.FORAGING_FOOD.get());
            }
            return 0;
        }

        double share = Fatigue.expectedWorkShare();
        double factor = settler.getJobs().stream().filter(job -> !cuDone.contains(job.jobBlock())).findFirst()
                .map(job -> job.jobType().hungerFactor()).orElse(0.0);
        float appetite = food.appetite(level.getRandom(), CivModConfig.APPETITE_MIN.get(), CivModConfig.APPETITE_MAX.get());
        food.exhaust(SettlerFood.exhaustionFor(awake, work * share * factor, CivModConfig.HUNGER_BASE_PER_DAY.get(),
                CivModConfig.HUNGER_PER_WORK_MINUTE.get(), appetite));
        if (food.level() < SettlerFood.HUNGRY) {
            simulateMeal(level, false);
        }
        if (food.level() <= 0) {
            cuStarvingDay = day;
        }
        int rolls = 0;
        if (work > 0 && cuStarvingDay != day) {
            rolls = simulateWork(level, day, work * share * CivModConfig.CATCH_UP_MULTIPLIER.get());
        }
        if (hour >= 12 && cuEveningDay != day) {
            cuEveningDay = day;
            simulateMeal(level, true);
            food.setEatenDay(day);
        }
        return rolls;
    }

    /** Eats during catch-up: provisions first (daytime) or the pantry first (evening). */
    private void simulateMeal(ServerLevel level, boolean evening) {
        SettlerFood food = settler.getFood();
        int before = food.level();
        if (!evening) {
            while (food.level() < SettlerFood.HUNGRY) {
                Optional<Integer> choice = SettlerFood.choose(pocketStacks(), food.level());
                if (choice.isEmpty()) {
                    break;
                }
                eatItem(level, settler.getInventory().getItem(Settler.POCKETS_START + choice.get()).split(1), null);
            }
        }
        if (evening || food.level() < SettlerFood.HUNGRY) {
            Optional<BlockPos> pantry = pantryWithFood(level);
            if (pantry.isPresent()) {
                mealPurpose = evening ? MealPurpose.EVENING : MealPurpose.HUNGRY;
                visitPantry(level, pantry.get());
                mealPurpose = null;
            } else if (evening) {
                Optional<Integer> choice;
                while ((choice = SettlerFood.choose(pocketStacks(), food.level())).isPresent()) {
                    eatItem(level, settler.getInventory().getItem(Settler.POCKETS_START + choice.get()).split(1), null);
                }
            }
        }
        if (food.level() > before) {
            cuMeals++;
        }
        settler.getInventory().setChanged();
    }

    /**
     * Produces the output of some work ticks during catch-up: directly into the drop-off, then the buffer.
     *
     * @return the number of rolls
     */
    private int simulateWork(ServerLevel level, long day, double workTicks) {
        SettlementData data = SettlementData.get(level);
        int rolls = 0;
        double remaining = workTicks;
        for (SettlerJob job : settler.getJobs()) {
            if (cuDone.contains(job.jobBlock()) || remaining <= 0.0) {
                continue;
            }
            Optional<SettlementBlock> found = data.settlementAt(job.jobBlock()).flatMap(s -> s.block(job.jobBlock()));
            Optional<JobProfile> profile = JobProfiles.of(job.jobType());
            ScanResult scan = scans.get(job.jobBlock());
            if (found.isEmpty() || profile.isEmpty() || scan == null) {
                cuDone.add(job.jobBlock());
                continue;
            }
            SettlementBlock block = found.get();
            BlockPos poi = poiOfDay(level, profile.get(), block, day);
            if (poi == null || (profile.get().needsResources() && scan.isEmpty())) {
                cuDone.add(job.jobBlock());
                continue;
            }
            Optional<ItemStack> tool = toolForCatchUp(level, profile.get(), block);
            if (tool.isEmpty()) {
                cuDone.add(job.jobBlock());
                continue;
            }
            int cap = SettlementRules.skillCap(block.level());
            int skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
            cuProduction += profile.get().speed(skillLevel, tool.get()) * profile.get().productivity(scan) * remaining / 20.0;
            remaining = 0.0;
            while (cuProduction >= 1.0) {
                cuProduction -= 1.0;
                tool = toolForCatchUp(level, profile.get(), block);
                if (tool.isEmpty()) {
                    cuDone.add(job.jobBlock());
                    break;
                }
                Optional<ItemStack> secondary = profile.get().secondaryTool(block).flatMap(this::bestTool);
                Optional<JobProfile.Produced> produced = profile.get().produce(new JobProfile.Context(level, settler, scan, poi, tool.get(),
                        secondary, block, skillLevel));
                rolls++;
                if (produced.isEmpty()) {
                    continue;
                }
                wear(level, produced.get().worn());
                if (produced.get().stacks().isEmpty()) {
                    continue;
                }
                if (!deliverDirect(level, block, produced.get().stacks())) {
                    cuDone.add(job.jobBlock());
                    // The next job takes over the rest of the time (approximated by the rest of this hour's output).
                    remaining = cuProduction * 20.0;
                    cuProduction = 0.0;
                    break;
                }
                gainExp(level, job, block.level());
                skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
            }
        }
        return rolls;
    }

    private Optional<ItemStack> toolForCatchUp(ServerLevel level, JobProfile profile, SettlementBlock block) {
        Optional<ItemStack> tool = bestTool(profile::isTool);
        if (tool.isEmpty() && findInContainer(level, collectPos(block), profile::isTool).isPresent()) {
            takeTool(level, collectPos(block), profile::isTool);
            tool = bestTool(profile::isTool);
        }
        return tool;
    }

    /**
     * Delivers output straight into the drop-off; what does not fit goes into the buffer.
     *
     * @return false when the buffer is full as well (the job ends)
     */
    private boolean deliverDirect(ServerLevel level, SettlementBlock block, List<ItemStack> stacks) {
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, block.dropOff().orElse(block.pos()), null);
        JobBuffer buffer = buffers.computeIfAbsent(block.pos(), pos -> new JobBuffer());
        for (ItemStack stack : stacks) {
            ItemStack rest = stack.copy();
            if (handler != null) {
                try (Transaction transaction = Transaction.openRoot()) {
                    int inserted = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(rest), rest.getCount(), transaction);
                    transaction.commit();
                    rest.shrink(inserted);
                }
            }
            cuItems += stack.getCount() - rest.getCount();
            if (!rest.isEmpty()) {
                buffer.add(rest);
                cuItems += rest.getCount();
            }
        }
        return !buffer.isFull();
    }

    // ---------------------------------------------------------------- food

    /** What a settler does at the start of a workday regarding food. */
    public enum MorningPlan {
        /** Nothing to do, go to work. */
        WORK,
        /** Fetch provisions from a pantry before work. */
        FETCH_PROVISIONS,
        /** Hungry and no food anywhere: forage all day. */
        FORAGE
    }

    /**
     * Morning decision (vault {@code Systems/Needs.md}).
     *
     * @param level      the food level
     * @param provisions nutrition carried in the pockets
     * @param wanted     nutrition the settler wants to carry
     * @param pantryFood whether a pantry has food
     * @return the plan
     */
    public static MorningPlan morningPlan(int level, int provisions, int wanted, boolean pantryFood) {
        if (level < SettlerFood.HUNGRY && provisions == 0 && !pantryFood) {
            return MorningPlan.FORAGE;
        }
        return provisions < wanted && pantryFood ? MorningPlan.FETCH_PROVISIONS : MorningPlan.WORK;
    }

    private void accountHunger(ServerLevel level) {
        SettlerFood food = settler.getFood();
        if (food.isAway()) {
            weightedWorkTicks = 0.0;
            return;
        }
        long dayTime = Math.floorMod(level.getOverworldClockTime(), 24000L);
        long awake = dayTime < SLEEP_TIME && !settler.isSleeping() ? DECISION_INTERVAL : 0L;
        float appetite = food.appetite(level.getRandom(), CivModConfig.APPETITE_MIN.get(), CivModConfig.APPETITE_MAX.get());
        food.exhaust(SettlerFood.exhaustionFor(awake, weightedWorkTicks, CivModConfig.HUNGER_BASE_PER_DAY.get(),
                CivModConfig.HUNGER_PER_WORK_MINUTE.get(), appetite));
        weightedWorkTicks = 0.0;
    }

    /**
     * @return a food-related status that replaces the job status, if any
     */
    public Optional<net.minecraft.network.chat.Component> foodStatus() {
        SettlerFood food = settler.getFood();
        String key = null;
        if (food.isForaging()) {
            key = "foraging";
        } else if (activity == Activity.EATING) {
            key = "eating";
        } else if (activity == Activity.FETCH_FOOD) {
            key = "fetching_food";
        } else if (hungryNoFood) {
            key = food.level() <= 0 ? "starving" : "hungry";
        }
        return Optional.ofNullable(key).map(k -> net.minecraft.network.chat.Component.translatable("civmod.settler.status." + k));
    }

    private Optional<BlockPos> pantryWithFood(ServerLevel level) {
        SettlementData data = SettlementData.get(level);
        return data.settlement(settler.getSettlementId())
                .flatMap(settlement -> Pantries.nearestWithFood(level, data, settlement, settler.blockPosition()));
    }

    private void startFetchFood(ServerLevel level, BlockPos pantry, MealPurpose purpose) {
        mealPurpose = purpose;
        hungryNoFood = false;
        moveTo(level, Activity.FETCH_FOOD, pantry);
    }

    /**
     * At a pantry: eats until full (not in the morning unless hungry), puts back leftover containers and refills the
     * provisions.
     */
    private void visitPantry(ServerLevel level, BlockPos pantry) {
        Optional<ResourceHandler<ItemResource>> found = Pantries.handler(level, pantry);
        if (found.isEmpty()) {
            return;
        }
        ResourceHandler<ItemResource> handler = found.get();
        SettlerFood food = settler.getFood();
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (stack.is(Items.BOWL) || stack.is(Items.GLASS_BOTTLE)) {
                settler.getInventory().setItem(i, Pantries.insert(handler, stack));
            }
        }
        if (mealPurpose != MealPurpose.MORNING || food.level() < SettlerFood.HUNGRY) {
            while (food.level() < SettlerFood.MAX) {
                Optional<Integer> slot = SettlerFood.choose(Pantries.contents(handler), food.level());
                if (slot.isEmpty()) {
                    break;
                }
                ItemStack item = Pantries.takeOne(handler, slot.get());
                if (item.isEmpty()) {
                    break;
                }
                eatItem(level, item, handler);
            }
        }
        int wanted = CivModConfig.PROVISION_NUTRITION.get();
        while (SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) < wanted) {
            List<ItemStack> contents = Pantries.contents(handler);
            int slot = -1;
            for (int i = 0; i < contents.size(); i++) {
                if (SettlerFood.isEdible(contents.get(i))) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                break;
            }
            ItemStack item = Pantries.takeOne(handler, slot);
            ItemStack rest = addToPockets(item);
            if (!rest.isEmpty()) {
                Pantries.insert(handler, rest);
                break;
            }
        }
    }

    private void eatItem(ServerLevel level, ItemStack item, @Nullable ResourceHandler<ItemResource> pantry) {
        net.minecraft.world.food.FoodProperties properties = item.get(net.minecraft.core.component.DataComponents.FOOD);
        if (properties == null) {
            return;
        }
        settler.getFood().eat(properties);
        hungryNoFood = false;
        eatingEffects(level, item);
        ItemStack rest = SettlerFood.remainder(item);
        if (pantry != null) {
            rest = Pantries.insert(pantry, rest);
        }
        rest = addToPockets(rest);
        if (!rest.isEmpty()) {
            settler.spawnAtLocation(level, rest);
        }
    }

    /**
     * Feeds the settler one item by a player: eaten if allowed, otherwise stored as provisions.
     *
     * @param level the level
     * @param item  one food item
     * @return what is left of the item (not eaten and not stored), plus any leftover container
     */
    public ItemStack feed(ServerLevel level, ItemStack item) {
        SettlerFood food = settler.getFood();
        if (SettlerFood.mayEat(food.level(), SettlerFood.nutrition(item))) {
            net.minecraft.world.food.FoodProperties properties = item.get(net.minecraft.core.component.DataComponents.FOOD);
            if (properties != null) {
                food.eat(properties);
                hungryNoFood = false;
                eatingEffects(level, item);
                return SettlerFood.remainder(item);
            }
        }
        return addToPockets(item);
    }

    private void eatingEffects(ServerLevel level, ItemStack item) {
        if (quiet) {
            return;
        }
        level.playSound(null, settler.getX(), settler.getY(), settler.getZ(), net.minecraft.sounds.SoundEvents.GENERIC_EAT.value(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        if (!item.isEmpty()) {
            level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,
                    net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(item)),
                    settler.getX(), settler.getEyeY() - 0.2, settler.getZ(), 6, 0.2, 0.1, 0.2, 0.05);
        }
    }

    /**
     * @param stack a stack
     * @return what did not fit into the pockets
     */
    private ItemStack addToPockets(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE && !rest.isEmpty(); i++) {
            ItemStack slot = settler.getInventory().getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(rest.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                rest.shrink(move);
            }
        }
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE && !rest.isEmpty(); i++) {
            if (settler.getInventory().getItem(i).isEmpty()) {
                settler.getInventory().setItem(i, rest);
                rest = ItemStack.EMPTY;
            }
        }
        settler.getInventory().setChanged();
        return rest;
    }

    private List<ItemStack> pocketStacks() {
        List<ItemStack> stacks = new ArrayList<>(Settler.POCKETS_SIZE);
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            stacks.add(settler.getInventory().getItem(i));
        }
        return stacks;
    }

    /**
     * Starts eating provisions on the spot.
     *
     * @param untilFull keep eating until full (evening) instead of one item
     * @return whether there was something to eat
     */
    private boolean startPocketMeal(boolean untilFull) {
        if (SettlerFood.choose(pocketStacks(), settler.getFood().level()).isEmpty()) {
            return false;
        }
        stop();
        activity = Activity.EATING;
        eatTimer = EAT_TICKS;
        eatUntilFull = untilFull;
        hungryNoFood = false;
        return true;
    }

    private void tickEating(ServerLevel level) {
        settler.getNavigation().stop();
        Optional<Integer> choice = SettlerFood.choose(pocketStacks(), settler.getFood().level());
        if (choice.isEmpty()) {
            activity = Activity.IDLE;
            return;
        }
        ItemStack stack = settler.getInventory().getItem(Settler.POCKETS_START + choice.get());
        if (--eatTimer % 6 == 0 && eatTimer > 0) {
            eatingEffects(level, stack);
        }
        if (eatTimer > 0) {
            return;
        }
        ItemStack one = stack.split(1);
        settler.getInventory().setChanged();
        eatItem(level, one, null);
        if (eatUntilFull && SettlerFood.choose(pocketStacks(), settler.getFood().level()).isPresent()) {
            eatTimer = EAT_TICKS;
            return;
        }
        activity = Activity.IDLE;
        decide(level);
    }

    private void complain(ServerLevel level, long day) {
        hungryNoFood = true;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, settler.getX(), settler.getY() + 2.1, settler.getZ(),
                1, 0.3, 0.1, 0.3, 0.0);
        if (settler.getFood().complainToday(day)) {
            net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.translatable("civmod.settler.hungry_message",
                    settler.getName());
            for (net.minecraft.server.level.ServerPlayer player : level.players()) {
                if (player.distanceToSqr(settler) <= COMPLAIN_RANGE * COMPLAIN_RANGE) {
                    player.sendOverlayMessage(message);
                }
            }
        }
    }

    private void startForaging(ServerLevel level, long day) {
        stop();
        settler.getFood().startForaging(day);
        forageStart = level.getGameTime();
        moveTo(level, Activity.FORAGE, foragingTarget(level));
    }

    /**
     * Foraging: walk towards the border, vanish after at most {@value #FORAGE_WALK_TICKS} ticks or at the border,
     * reappear there at 17:30 with some food.
     */
    private void forage(ServerLevel level, long day, long dayTime) {
        SettlerFood food = settler.getFood();
        boolean over = dayTime >= WORK_END || day > food.forageDay();
        if (over) {
            if (food.isAway()) {
                settler.setInvisible(false);
            }
            food.addFood(CivModConfig.FORAGING_FOOD.get());
            food.endForaging();
            stop();
            return;
        }
        if (food.isAway()) {
            return;
        }
        if (activity != Activity.FORAGE || target == null) {
            forageStart = level.getGameTime();
            moveTo(level, Activity.FORAGE, foragingTarget(level));
        }
        checkStuck(level);
        if (isAtTarget() || level.getGameTime() - forageStart >= FORAGE_WALK_TICKS) {
            food.vanish(settler.blockPosition());
            settler.getNavigation().stop();
            settler.setInvisible(true);
            target = null;
        }
    }

    private BlockPos foragingTarget(ServerLevel level) {
        Optional<SettlementBlock> anchor = SettlementData.get(level).settlement(settler.getSettlementId()).flatMap(s -> s.anchor());
        if (anchor.isEmpty()) {
            return settler.blockPosition();
        }
        Vec3 center = Vec3.atCenterOf(anchor.get().pos());
        Vec3 direction = settler.position().subtract(center).multiply(1.0, 0.0, 1.0);
        if (direction.lengthSqr() < 1.0) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
            direction = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
        }
        direction = direction.normalize().scale(SettlementRules.joinRadius(anchor.get().level()) + 4.0);
        BlockPos column = BlockPos.containing(center.x + direction.x, center.y, center.z + direction.z);
        if (!level.isLoaded(column)) {
            return settler.blockPosition();
        }
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
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
        if (key.equals(scanKeys.get(jobBlock)) || scanning.contains(jobBlock)) {
            return;
        }
        scanning.add(jobBlock);
        // Spread daily scans over the first real minute of the workday; later starts (e.g. after loading) begin at once.
        long delay = profile.rescanDaily() && dayTime < 1200 ? level.getRandom().nextInt(1200) : 0;
        ScanScheduler.submit(level, profile.createScan(key.center(), key.radius()), delay, result -> {
            scans.put(jobBlock, result);
            scanKeys.put(jobBlock, key);
            scanning.remove(jobBlock);
        });
    }

    /**
     * Found resources of a job for the GUI.
     *
     * @param job the job
     * @return the found block types with whether the current tool can obtain them
     */
    public List<JobProfile.Found> found(SettlerJob job) {
        ScanResult scan = scans.get(job.jobBlock());
        Optional<JobProfile> profile = JobProfiles.of(job.jobType());
        if (scan == null || profile.isEmpty()) {
            return List.of();
        }
        ItemStack tool = bestTool(profile.get()::isTool).orElse(ItemStack.EMPTY);
        return profile.get().found(scan, tool, effectiveLevel(job));
    }

    /**
     * @param job a job
     * @return the level of the JobBlock of that job (1 if unknown)
     */
    public int jobBlockLevel(SettlerJob job) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return SettlementRules.MIN_LEVEL;
        }
        return SettlementData.get(level).settlementAt(job.jobBlock()).flatMap(s -> s.block(job.jobBlock()))
                .map(SettlementBlock::level).orElse(SettlementRules.MIN_LEVEL);
    }

    /**
     * @param job a job
     * @return the level the settler works at in that job (its skill, at most the JobBlock cap)
     */
    public int effectiveLevel(SettlerJob job) {
        return settler.getSkills().effectiveLevel(job.jobType(), SettlementRules.skillCap(jobBlockLevel(job)));
    }

    private void gainExp(ServerLevel level, SettlerJob job, int blockLevel) {
        SkillRules rules = SkillRules.get();
        int gained = settler.getSkills().addExp(job.jobType(), rules.baseExp(job.jobType()) * blockLevel,
                SettlementRules.skillCap(blockLevel), rules);
        if (gained <= 0) {
            return;
        }
        int newLevel = settler.getSkills().get(job.jobType()).level();
        if (quiet) {
            return;
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, settler.getX(), settler.getY() + 2.0, settler.getZ(),
                8, 0.4, 0.3, 0.4, 0.0);
        level.playSound(null, settler.getX(), settler.getY(), settler.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.3F, 1.2F);
        net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.translatable("civmod.settler.level_up",
                settler.getName(), job.jobType().displayName(), newLevel);
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.distanceToSqr(settler) <= LEVEL_UP_RANGE * LEVEL_UP_RANGE) {
                player.sendOverlayMessage(message);
            }
        }
    }

    // ---------------------------------------------------------------- tools

    private Optional<ItemStack> bestTool(Predicate<ItemStack> kind) {
        ItemStack best = ItemStack.EMPTY;
        int maxTier = ToolTiers.maxAllowedTier(TOOL_LEVEL);
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

    private void showTool(Predicate<ItemStack> kind) {
        ItemStack tool = bestTool(kind).map(ItemStack::copy).orElse(ItemStack.EMPTY);
        if (!ItemStack.matches(tool, settler.getItemBySlot(EquipmentSlot.MAINHAND))) {
            settler.setItemSlot(EquipmentSlot.MAINHAND, tool);
        }
    }

    private static Optional<Integer> findInContainer(ServerLevel level, BlockPos pos, Predicate<ItemStack> kind) {
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
            if (kind.test(stack) && ToolTiers.tier(stack) <= ToolTiers.maxAllowedTier(TOOL_LEVEL) && ToolTiers.tier(stack) > bestTier) {
                bestTier = ToolTiers.tier(stack);
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? Optional.empty() : Optional.of(bestIndex);
    }

    private void takeTool(ServerLevel level, BlockPos pos, Predicate<ItemStack> kind) {
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
        if (activity == Activity.EATING) {
            tickEating(level);
            return;
        }
        if (activity == Activity.IDLE || activity == Activity.SLEEP || target == null) {
            return;
        }
        if (!isAtTarget()) {
            return;
        }
        if (activity == Activity.GO_TO_BED) {
            sleep(level);
            return;
        }
        if (activity == Activity.FETCH_FOOD) {
            visitPantry(level, target);
            activity = Activity.IDLE;
            mealPurpose = null;
            decide(level);
            return;
        }
        if (activity == Activity.FORAGE) {
            return;
        }
        if (activeJob == null) {
            return;
        }
        BlockPos jobBlock = activeJob.jobBlock();
        switch (activity) {
            case FETCH_TOOL -> {
                Optional<JobProfile> profile = JobProfiles.of(activeJob.jobType());
                Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(jobBlock).flatMap(s -> s.block(jobBlock));
                if (profile.isPresent()) {
                    if (bestTool(profile.get()::isTool).isEmpty()) {
                        takeTool(level, target, profile.get()::isTool);
                    }
                    Optional<Predicate<ItemStack>> secondary = block.flatMap(b -> profile.get().secondaryTool(b));
                    if (secondary.isPresent() && bestTool(secondary.get()).isEmpty()) {
                        takeTool(level, target, secondary.get());
                    }
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
                weightedWorkTicks += activeJob.jobType().hungerFactor();
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
        settler.getNavigation().stop();
        settler.getLookControl().setLookAt(Vec3.atCenterOf(poi));
        if (++swingTimer >= SWING_INTERVAL && activeJob != null && JobProfiles.of(activeJob.jobType()).map(JobProfile::swings).orElse(true)) {
            swingTimer = 0;
            settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            BlockState state = level.getBlockState(poi);
            level.playSound(null, poi, state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        Optional<JobProfile> found = activeJob == null ? Optional.empty() : JobProfiles.of(activeJob.jobType());
        Optional<SettlementBlock> block = SettlementData.get(level).settlementAt(jobBlock).flatMap(s -> s.block(jobBlock));
        if (found.isEmpty() || block.isEmpty()) {
            return;
        }
        JobProfile profile = found.get();
        ScanResult scan = scans.get(jobBlock);
        JobBuffer buffer = buffers.computeIfAbsent(jobBlock, pos -> new JobBuffer());
        Optional<ItemStack> tool = bestTool(profile::isTool);
        if (tool.isEmpty() || buffer.isFull()) {
            return;
        }
        int skillLevel = settler.getSkills().effectiveLevel(activeJob.jobType(), SettlementRules.skillCap(block.get().level()));
        double perSecond = profile.speed(skillLevel, tool.get()) * profile.productivity(scan);
        production += perSecond / 20.0;
        while (production >= 1.0) {
            production -= 1.0;
            Optional<ItemStack> current = bestTool(profile::isTool);
            if (current.isEmpty()) {
                return;
            }
            Optional<ItemStack> secondary = profile.secondaryTool(block.get()).flatMap(this::bestTool);
            Optional<JobProfile.Produced> produced = profile.produce(new JobProfile.Context(level, settler, scan, poi, current.get(), secondary,
                    block.get(), skillLevel));
            if (produced.isEmpty()) {
                continue;
            }
            produced.get().stacks().forEach(buffer::add);
            wear(level, produced.get().worn());
            if (!produced.get().stacks().isEmpty()) {
                gainExp(level, activeJob, block.get().level());
                skillLevel = settler.getSkills().effectiveLevel(activeJob.jobType(), SettlementRules.skillCap(block.get().level()));
            }
        }
        showTool(profile::isTool);
    }

    private void wear(ServerLevel level, ItemStack tool) {
        double multiplier = CivModConfig.TOOL_WEAR_MULTIPLIER.get();
        int damage = (int) multiplier + (level.getRandom().nextDouble() < multiplier - Math.floor(multiplier) ? 1 : 0);
        if (damage > 0) {
            tool.hurtAndBreak(damage, level, settler, item -> { });
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
            return activity != Activity.IDLE || settler.isSleeping();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (settler.level() instanceof ServerLevel level) {
                long profile = com.gilfort.civmod.debug.CivProfiler.start();
                workTick(level);
                com.gilfort.civmod.debug.CivProfiler.stop(com.gilfort.civmod.debug.CivProfiler.Section.WORK, profile);
            }
        }
    }
}
