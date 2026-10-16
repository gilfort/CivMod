package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.job.Schedule.Decision;
import com.gilfort.civmod.job.Schedule.Intent;
import com.gilfort.civmod.job.Schedule.MealPurpose;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.JobBlockSite;
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

/**
 * The Settler Shell (vault {@code Systems/Job Categories.md}): the behaviour shared by every Job Category – schedule,
 * walking / anti-stuck, food, sleep, job priorities, EXP, one {@link Workplace} per job (buffer, status, work place of
 * the day, scan) with delivery and the catch-up driver. It picks the first job of the priority list that can work and
 * hands control to the {@link CategoryLoop} of that job's {@link JobCategory}.
 * <p>
 * Decisions run every {@value #DECISION_INTERVAL} ticks (staggered per settler): the {@link Schedule} decides from a
 * snapshot, the shell acts on its intent; movement uses vanilla navigation.
 */
public final class SettlerWork {

    /** Ticks between decisions. */
    public static final int DECISION_INTERVAL = 100;
    /** Work hours in day ticks (06:00–17:30; day time 0 = 06:00). */
    public static final long WORK_END = 11500;
    /** Daily scans that start within this many day ticks are spread randomly over that window. */
    public static final int SCAN_SPREAD_TICKS = 1200;
    /** Reach for walking targets (category loops may use a different one for their steps). */
    static final double REACH = 2.5;
    /** Players within this distance get the level-up message. */
    private static final double LEVEL_UP_RANGE = 32.0;

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
    /** One Workplace per JobBlock the settler works for. */
    private final Map<BlockPos, Workplace> jobWorkplaces = new HashMap<>();
    /** The work loop of each implemented Job Category. */
    private final Map<JobCategory, CategoryLoop> loops = new EnumMap<>(JobCategory.class);
    private final MissionLoop missions;

    private Activity activity = Activity.IDLE;
    private @Nullable SettlerJob activeJob;
    private @Nullable BlockPos target;
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
    /** The food point the settler walks to or visits. */
    private ClerkStorage.@Nullable FoodPoint foodPoint;
    /** Where the settler delivers to (a Clerk drop-off takes at its counter). */
    private ClerkStorage.@Nullable Visit deliveryVisit;
    /** Reach of the current walking target outside job steps. */
    private double targetReach = REACH;

    // Catch-up (vault Systems/Settlement.md): missed time while unloaded.
    /** Minimum missed time that is caught up (2 real minutes). */
    public static final long CATCH_UP_MIN_GAP = 2400;
    private long savedClock = -1;
    private long catchUpFrom = -1;
    private long catchUpTo = -1;
    private boolean catchUpRegistered;
    private boolean quiet;
    private int cuItems;
    private int cuMeals;
    /** Schedule decisions per catch-up step at most (meals in a row; guards against a meal that never ends). */
    private static final int CATCH_UP_DECISIONS = 64;
    private double cuDays;
    /** The day time of the hour being simulated in catch-up. */
    private long cuDayTime;

    /**
     * @param settler the settler
     */
    public SettlerWork(Settler settler) {
        this.settler = settler;
        loops.put(JobCategory.RESOURCE_COLLECTION, new ResourceCollectionLoop(this));
        missions = new MissionLoop(this);
        loops.put(JobCategory.MISSION, missions);
    }

    /**
     * @return the Mission loop (reserved or current mission)
     */
    MissionLoop missions() {
        return missions;
    }

    /**
     * @return the settler
     */
    Settler settler() {
        return settler;
    }

    /**
     * @param job a job
     * @return the work loop of the job's category (null if the category is not implemented)
     */
    private @Nullable CategoryLoop loop(SettlerJob job) {
        return loops.get(job.jobType().category());
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
        List<BufferEntry> buffers = new ArrayList<>();
        List<WorkPlaceEntry> workPlaces = new ArrayList<>();
        List<SavedScan> saved = new ArrayList<>();
        jobWorkplaces.forEach((jobBlock, workplace) -> {
            if (!workplace.buffer().isEmpty()) {
                buffers.add(new BufferEntry(jobBlock, workplace.buffer()));
            }
            workplace.savedWorkPlace().ifPresent(workPlaces::add);
            workplace.savedScan().ifPresent(saved::add);
        });
        long clock = settler.level() instanceof ServerLevel level ? level.getOverworldClockTime() : -1L;
        return new BuffersSnapshot(buffers, workPlaces, saved, new CatchUpState(clock, catchUpFrom, catchUpTo), missions.trip());
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
     * Saved work state: buffers, the work places of the day, scans, catch-up and the reserved or current mission.
     *
     * @param entries    the buffers
     * @param workPlaces the work places of the day
     * @param scans      the scans
     * @param catchUp    the catch-up bookkeeping
     * @param trip       the reserved or current mission
     */
    public record BuffersSnapshot(List<BufferEntry> entries, List<WorkPlaceEntry> workPlaces, List<SavedScan> scans, CatchUpState catchUp,
            Optional<MissionTrip> trip) {

        /**
         * @param entries    the buffers
         * @param workPlaces the work places of the day
         */
        public BuffersSnapshot(List<BufferEntry> entries, List<WorkPlaceEntry> workPlaces) {
            this(entries, workPlaces, List.of(), CatchUpState.NONE);
        }

        /**
         * @param entries    the buffers
         * @param workPlaces the work places of the day
         * @param scans      the scans
         * @param catchUp    the catch-up bookkeeping
         */
        public BuffersSnapshot(List<BufferEntry> entries, List<WorkPlaceEntry> workPlaces, List<SavedScan> scans, CatchUpState catchUp) {
            this(entries, workPlaces, scans, catchUp, Optional.empty());
        }

        private static final Codec<BuffersSnapshot> CURRENT = RecordCodecBuilder.create(instance -> instance.group(
                BUFFERS_CODEC.optionalFieldOf("buffers", List.of()).forGetter(BuffersSnapshot::entries),
                WorkPlaceEntry.CODEC.listOf().optionalFieldOf("work_places", List.of()).forGetter(BuffersSnapshot::workPlaces),
                SavedScan.CODEC.listOf().optionalFieldOf("scans", List.of()).forGetter(BuffersSnapshot::scans),
                CatchUpState.CODEC.optionalFieldOf("catch_up", CatchUpState.NONE).forGetter(BuffersSnapshot::catchUp),
                MissionTrip.CODEC.optionalFieldOf("mission").forGetter(BuffersSnapshot::trip)
        ).apply(instance, BuffersSnapshot::new));

        /** Save codec; also reads the earlier format (a plain list of buffers). */
        public static final Codec<BuffersSnapshot> CODEC = Codec.withAlternative(CURRENT,
                BUFFERS_CODEC.xmap(entries -> new BuffersSnapshot(entries, List.of()), BuffersSnapshot::entries));
    }

    /**
     * Restores the saved work state into the Workplaces (status and blocked time are not saved and stay).
     *
     * @param snapshot the saved work state
     */
    public void restore(BuffersSnapshot snapshot) {
        jobWorkplaces.values().forEach(Workplace::clearSaved);
        snapshot.entries().forEach(entry -> workplace(entry.jobBlock()).restoreBuffer(entry.buffer()));
        snapshot.workPlaces().forEach(entry -> workplace(entry.jobBlock()).restoreWorkPlace(entry));
        snapshot.scans().forEach(scan -> workplace(scan.jobBlock()).restoreScan(scan));
        loops.values().forEach(loop -> loop.restore(snapshot));
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
        return existingWorkplace(jobBlock).map(Workplace::status).orElse(JobStatus.IDLE);
    }

    /**
     * @param jobBlock a JobBlock position
     * @return the buffer of that job (empty if none)
     */
    public JobBuffer buffer(BlockPos jobBlock) {
        return existingWorkplace(jobBlock).map(Workplace::buffer).orElseGet(JobBuffer::new);
    }

    /**
     * @return whether the settler is away on a mission (invisible, no AI, no hunger)
     */
    public boolean isAway() {
        return missions.isAway();
    }

    /**
     * @return the mission the settler is away on or waits to leave for, for the GUI
     */
    public Optional<net.minecraft.network.chat.Component> missionStatus() {
        return settler.level() instanceof ServerLevel level ? missions.status(level) : Optional.empty();
    }

    /**
     * @return the reserved or current mission
     */
    public Optional<MissionTrip> missionTrip() {
        return missions.trip();
    }

    /**
     * Ends the mission if its return time has passed (called at every decision; public for tests).
     *
     * @param level the level
     * @param clock the world time
     * @return whether the settler returned
     */
    public boolean returnFromMission(ServerLevel level, long clock) {
        return missions.returnIfDue(level, clock, false);
    }

    /**
     * Fixes the outcome of every following mission instead of rolling it (for tests).
     *
     * @param outcome the outcome
     */
    public void forceMissionOutcome(com.gilfort.civmod.mission.MissionRules.Outcome outcome) {
        missions.forceOutcome(outcome);
    }

    /**
     * @return the job the settler currently works on
     */
    public Optional<SettlerJob> activeJob() {
        return Optional.ofNullable(activeJob);
    }

    /**
     * Forgets everything about a removed job: its whole Workplace goes (the buffer is dropped at the settler's position).
     *
     * @param jobBlock the JobBlock position
     */
    public void forget(BlockPos jobBlock) {
        Workplace workplace = jobWorkplaces.remove(jobBlock);
        if (workplace != null && settler.level() instanceof ServerLevel level) {
            workplace.buffer().stacks().forEach(stack -> settler.spawnAtLocation(level, stack.copy()));
        }
        loops.values().forEach(loop -> loop.forget(jobBlock));
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
        if (missions.departed() && (catchUpFrom >= 0 || !missions.returnIfDue(level, clock, false))) {
            if (missions.isAway()) {
                // Away: nothing to decide until the return (missed time is caught up first).
                return;
            }
            if (activity != Activity.JOB) {
                // Walking out was interrupted (loaded again, or not started): it goes on before anything else.
                missions.resumeLeaving(level);
                return;
            }
        }
        if (settler.isRemoved()) {
            // Died on the mission.
            return;
        }
        // The body first: stay asleep, wake, return to the rest place after a skipped night, keep foraging.
        boolean woke = false;
        Optional<Intent> body = Schedule.wakeUp(snapshot(level, day, dayTime, jumped, false, new FoodLookup(level, dayTime)));
        while (body.isPresent()) {
            Intent intent = body.get();
            if (intent == Intent.SLEEP) {
                return;
            }
            if (intent == Intent.FORAGE) {
                forage(level);
                return;
            }
            if (intent == Intent.END_FORAGING) {
                returnFromForaging();
                stop();
                return;
            }
            if (intent == Intent.WAKE) {
                wake();
                woke = true;
            } else {
                // The night was skipped while this settler was not in bed: appear at the bed as if it slept there.
                teleportToRestPlace(level, day);
            }
            body = Schedule.wakeUp(snapshot(level, day, dayTime, false, woke, new FoodLookup(level, dayTime)));
        }
        SettlerFood food = settler.getFood();
        if (food.level() >= SettlerFood.HUNGRY) {
            hungryNoFood = false;
        }
        checkStuck(level);

        FoodLookup foodLookup = new FoodLookup(level, dayTime);
        Decision decision = Schedule.next(snapshot(level, day, dayTime, false, woke, foodLookup));
        act(level, decision, foodLookup, day, dayTime);
    }

    /**
     * Builds the Schedule snapshot. Food points are looked up (once) only if a rule asks; the job step is asked only
     * during a job step.
     *
     * @param woke whether the settler was just woken in this decision (it counts as awake)
     */
    private Schedule.Snapshot snapshot(ServerLevel level, long day, long dayTime, boolean nightSkipped, boolean woke, FoodLookup foodLookup) {
        SettlerFood food = settler.getFood();
        boolean deliveryPending = settler.getJobs().stream()
                .anyMatch(job -> !buffer(job.jobBlock()).isEmpty() && status(job.jobBlock()) != JobStatus.DROP_OFF_FULL);
        return new Schedule.Snapshot(day, dayTime, food.level(),
                SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE),
                CivModConfig.PROVISION_NUTRITION.get(), SettlerFood.choose(pocketStacks(), food.level()).isPresent(),
                () -> foodLookup.get().isPresent(), settler.isHomeless(), settler.isSleeping() && !woke, food.forageDay(), nightSkipped,
                activity, () -> jobStepHolds(level), food.morningDay() == day, food.eatenDay() == day, deliveryPending);
    }

    private boolean jobStepHolds(ServerLevel level) {
        SettlerJob job = activeJob;
        CategoryLoop loop = job == null ? null : loop(job);
        return activity == Activity.JOB && loop != null && loop.holdsDecision(level, job);
    }

    /** Acts on a decision of the Schedule. */
    private void act(ServerLevel level, Decision decision, FoodLookup foodLookup, long day, long dayTime) {
        SettlerFood food = settler.getFood();
        if (decision.intent() == Intent.CONTINUE) {
            return;
        }
        if (decision.morningHandled()) {
            food.setMorningDay(day);
        }
        if (decision.eveningMealHandled()) {
            food.setEatenDay(day);
        }
        if (decision.complain()) {
            complain(level, day);
        }
        SettlerJob delivery = null;
        if (decision.afterWork()) {
            // Jobs show idle after work, except the first one with something to deliver.
            for (SettlerJob job : settler.getJobs()) {
                if (!buffer(job.jobBlock()).isEmpty() && status(job.jobBlock()) != JobStatus.DROP_OFF_FULL) {
                    delivery = job;
                    break;
                }
                workplace(job.jobBlock()).setStatus(JobStatus.IDLE);
            }
        } else {
            restPlace = null;
        }
        switch (decision.intent()) {
            case IDLE -> stop();
            case EAT_FROM_POCKETS -> startPocketMeal(false);
            case EAT_UNTIL_FULL -> startPocketMeal(true);
            case FETCH_FOOD -> {
                stop();
                startFetchFood(level, foodLookup.get().orElseThrow(), Objects.requireNonNull(decision.purpose()));
            }
            case START_FORAGING -> startForaging(level, day);
            case DELIVER -> startDelivery(level, Objects.requireNonNull(delivery));
            case REST, GO_TO_BED -> {
                RestPlace rest = ensureRestPlace(level, day);
                if (rest != null) {
                    settler.setHomeTo(rest.pos(), REST_RADIUS);
                }
                if (decision.intent() == Intent.GO_TO_BED && rest != null) {
                    if (activity != Activity.GO_TO_BED && activity != Activity.SLEEP) {
                        stop();
                        moveTo(level, Activity.GO_TO_BED, rest.pos());
                    }
                } else {
                    stop();
                }
            }
            case WORK -> work(level, day, dayTime);
            default -> throw new IllegalStateException("Not a decision of Schedule.next: " + decision);
        }
    }

    /** Works the first job of the priority list that can work; lower jobs are told they were outranked. */
    private void work(ServerLevel level, long day, long dayTime) {
        List<SettlerJob> jobs = settler.getJobs();
        for (int i = 0; i < jobs.size(); i++) {
            SettlerJob job = jobs.get(i);
            if (workplace(job.jobBlock()).isBlocked(level.getGameTime())) {
                continue;
            }
            if (plan(level, job, day, dayTime)) {
                for (SettlerJob lower : jobs.subList(i + 1, jobs.size())) {
                    CategoryLoop loop = loop(lower);
                    if (loop != null) {
                        loop.outranked(level, lower, day, dayTime);
                    }
                }
                return;
            }
        }
        // No job can work: Laborer fallback (idle in the PoC).
        stop();
    }

    /** The nearest food point (Clerk JobBlock) with food, looked up once and only when asked. */
    private final class FoodLookup {

        private final ServerLevel level;
        private final long dayTime;
        private Optional<ClerkStorage.FoodPoint> point = Optional.empty();
        private boolean looked;

        FoodLookup(ServerLevel level, long dayTime) {
            this.level = level;
            this.dayTime = dayTime;
        }

        Optional<ClerkStorage.FoodPoint> get() {
            if (!looked) {
                looked = true;
                point = foodPointWithFood(level, dayTime);
            }
            return point;
        }
    }

    /**
     * Plans the next step of a job through its category loop.
     *
     * @return whether the job can work (the settler is busy with it)
     */
    private boolean plan(ServerLevel level, SettlerJob job, long day, long dayTime) {
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        CategoryLoop loop = loop(job);
        if (site.isEmpty() || loop == null) {
            return false;
        }
        return loop.plan(level, job, site.get(), day, dayTime);
    }

    // ---------------------------------------------------------------- shared parts used by the category loops

    /**
     * @param jobBlock a JobBlock position
     * @return the Workplace of that job (created if missing)
     */
    Workplace workplace(BlockPos jobBlock) {
        return jobWorkplaces.computeIfAbsent(jobBlock, Workplace::new);
    }

    /**
     * @param jobBlock a JobBlock position
     * @return the Workplace of that job, empty if it has none (for queries that must not create one)
     */
    Optional<Workplace> existingWorkplace(BlockPos jobBlock) {
        return Optional.ofNullable(jobWorkplaces.get(jobBlock));
    }

    /**
     * @param job the job the settler works on from now on
     */
    void setActiveJob(SettlerJob job) {
        activeJob = job;
    }

    /**
     * @param jobBlock a JobBlock position
     * @param status   the new status of that job
     */
    void setStatus(BlockPos jobBlock, JobStatus status) {
        workplace(jobBlock).setStatus(status);
    }

    /**
     * @param pos a position
     * @return whether the settler is already walking to (or at) that position for a step of a category loop
     */
    boolean walkingTo(BlockPos pos) {
        return activity == Activity.JOB && pos.equals(target);
    }

    /**
     * Starts walking to the target of a step of a category loop.
     *
     * @param level the level
     * @param pos   the target
     */
    void moveToJob(ServerLevel level, BlockPos pos) {
        moveTo(level, Activity.JOB, pos);
    }

    /**
     * Ends the current step of a category loop and decides again.
     *
     * @param level the level
     */
    void stepDone(ServerLevel level) {
        activity = Activity.IDLE;
        decide(level);
    }

    /**
     * Stops the current step and stays idle while working on a job (e.g. waiting for a departure).
     *
     * @param job the job
     */
    void idleWith(SettlerJob job) {
        stop();
        activeJob = job;
    }

    /**
     * Counts one tick of work of a job for hunger.
     *
     * @param job the job
     */
    void countWorkTick(SettlerJob job) {
        weightedWorkTicks += job.jobType().hungerFactor();
    }

    /**
     * Starts delivering the buffer of a job to its drop-off (or JobBlock).
     *
     * @param level the level
     * @param job   the job
     * @return true (the settler is busy with it)
     */
    boolean startDelivery(ServerLevel level, SettlerJob job) {
        BlockPos dropOff = JobBlockSite.at(level, job.jobBlock()).map(JobBlockSite::dropOff).orElse(job.jobBlock());
        ClerkStorage.Visit visit = ClerkStorage.visit(level, dropOff, Math.floorMod(level.getOverworldClockTime(), 24000L));
        activeJob = job;
        workplace(job.jobBlock()).setStatus(JobStatus.DELIVERING);
        if (activity != Activity.DELIVER || !visit.target().equals(target)) {
            deliveryVisit = visit;
            moveTo(level, Activity.DELIVER, visit.target(), visit.reach());
        }
        return true;
    }

    /**
     * Blocks a job for a while with a status.
     *
     * @param level    the level
     * @param jobBlock the JobBlock position
     * @param status   the status explaining why
     */
    void block(ServerLevel level, BlockPos jobBlock, JobStatus status) {
        workplace(jobBlock).block(status, level.getGameTime());
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
        moveTo(level, next, pos, REACH);
    }

    private void moveTo(ServerLevel level, Activity next, BlockPos pos, double reach) {
        targetReach = reach;
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
     * @return the reach for the current target (the category loop's reach in a job step, otherwise the reach the walk
     *         was started with)
     */
    private double reach() {
        CategoryLoop loop = activeJob == null ? null : loop(activeJob);
        if (activity == Activity.JOB && loop != null) {
            return loop.reach(activeJob);
        }
        return targetReach;
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
        for (SettlementBlock block : settlement.get().blocks()) {
            if (ClerkStorage.isClerk(block) && (!level.isLoaded(block.pos())
                    || !ClerkStorage.activeStorage(block).stream().allMatch(level::isLoaded))) {
                // Food points and their storage must be loaded.
                return false;
            }
        }
        long clock = level.getOverworldClockTime();
        for (SettlerJob job : settler.getJobs()) {
            Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock()).filter(found -> found.settlement() == settlement.get());
            CategoryLoop loop = loop(job);
            if (site.isEmpty() || loop == null) {
                continue;
            }
            if (!loop.catchUpReady(level, job, site.get(), Math.floorDiv(clock, 24000L), Math.floorMod(clock, 24000L))) {
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
        loops.values().forEach(CategoryLoop::startCatchUp);
        jobWorkplaces.values().forEach(workplace -> workplace.setCatchUpEnded(false));
        cuItems = 0;
        cuMeals = 0;
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
        // Foraging that is over came back during the run (Schedule); one still going on continues live.
        catchUpFrom = -1;
        catchUpTo = -1;
        catchUpRegistered = false;
        quiet = false;
        jobWorkplaces.values().stream().filter(Workplace::catchUpEnded).forEach(workplace -> {
            workplace.setStatus(JobStatus.DROP_OFF_FULL);
            workplace.setCatchUpEnded(false);
        });
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
        if ((missions.departed() && !missions.returnIfDue(level, to, true)) || settler.isRemoved()) {
            // On the mission all hour (no hunger, no work), or died on it.
            return 0;
        }
        long workEnd = day * 24000L + WORK_END;
        long dayTime = from - day * 24000L;
        cuDayTime = dayTime;
        SettlerFood food = settler.getFood();

        // Start of the hour: back from foraging, the morning plan, a meal if hungry.
        if (decideCatchUp(level, day, dayTime) == Intent.FORAGE) {
            // Away while foraging (no hunger, no work).
            if (to <= workEnd) {
                return 0;
            }
            // Back at the end of work (the Schedule hands out the food): the rest of the hour is an ordinary evening.
            from = workEnd;
            dayTime = WORK_END;
            decideCatchUp(level, day, dayTime);
        }
        long awake = to - from;
        long work = Math.max(0, Math.min(to, workEnd) - from);

        double share = Fatigue.expectedWorkShare();
        double factor = settler.getJobs().stream().filter(job -> !catchUpEnded(job.jobBlock())).findFirst()
                .map(job -> job.jobType().hungerFactor()).orElse(0.0);
        float appetite = food.appetite(level.getRandom(), CivModConfig.APPETITE_MIN.get(), CivModConfig.APPETITE_MAX.get());
        food.exhaust(SettlerFood.exhaustionFor(awake, work * share * factor, CivModConfig.HUNGER_BASE_PER_DAY.get(),
                CivModConfig.HUNGER_PER_WORK_MINUTE.get(), appetite));

        // After the hour's hunger: a meal if hungry; starving stops the work.
        int rolls = 0;
        if (decideCatchUp(level, day, dayTime) == Intent.WORK && work > 0) {
            rolls = simulateWork(level, day, work * share * CivModConfig.CATCH_UP_MULTIPLIER.get());
        }
        if (to >= workEnd) {
            // Work is over: the evening meal.
            decideCatchUp(level, day, Math.max(dayTime, WORK_END));
        }
        return rolls;
    }

    /**
     * Asks the Schedule what the settler does during catch-up and simulates meals and the start of foraging on the
     * spot (no walking, no messages), until the Schedule asks for something else.
     *
     * @param level   the level
     * @param day     the day
     * @param dayTime the day time of the decision
     * @return {@link Intent#FORAGE} while foraging, otherwise the intent of the last decision ({@link Intent#WORK},
     *         {@link Intent#IDLE} when starving, an evening intent after work)
     */
    private Intent decideCatchUp(ServerLevel level, long day, long dayTime) {
        SettlerFood food = settler.getFood();
        int levelBefore = food.level();
        boolean noFood = false;
        try {
            for (int step = 0; step < CATCH_UP_DECISIONS; step++) {
                FoodLookup foodLookup = new FoodLookup(level, dayTime);
                Schedule.Snapshot snapshot = catchUpSnapshot(day, dayTime, foodLookup, noFood);
                Optional<Intent> body = Schedule.wakeUp(snapshot);
                if (body.isPresent()) {
                    // Only foraging settles the body in catch-up (awake, no skipped night).
                    if (body.get() == Intent.FORAGE) {
                        return Intent.FORAGE;
                    }
                    // END_FORAGING: back with the day's food, which is not a meal.
                    int beforeReturn = food.level();
                    returnFromForaging();
                    levelBefore += food.level() - beforeReturn;
                    continue;
                }
                Decision decision = Schedule.next(snapshot);
                if (decision.morningHandled()) {
                    food.setMorningDay(day);
                }
                if (decision.eveningMealHandled()) {
                    food.setEatenDay(day);
                }
                int foodBefore = food.level();
                int pocketsBefore = SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE);
                switch (decision.intent()) {
                    case EAT_FROM_POCKETS -> eatFromPockets(level);
                    case EAT_UNTIL_FULL -> {
                        while (eatFromPockets(level)) {
                            // Until nothing fits any more.
                        }
                    }
                    case FETCH_FOOD -> {
                        mealPurpose = decision.purpose();
                        // No walking or queue in catch-up: the shift alone decides what is reachable.
                        visitFoodPoint(level, foodLookup.get().orElseThrow(), dayTime, true);
                        mealPurpose = null;
                    }
                    case START_FORAGING -> {
                        food.startForaging(day);
                        return Intent.FORAGE;
                    }
                    default -> {
                        return decision.intent();
                    }
                }
                // A meal that found nothing to take counts as no food, so the Schedule decides again without it.
                noFood = food.level() == foodBefore
                        && SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) == pocketsBefore;
            }
            return Intent.IDLE;
        } finally {
            if (food.level() > levelBefore) {
                cuMeals++;
            }
            settler.getInventory().setChanged();
        }
    }

    /**
     * The Schedule snapshot during catch-up: awake, idle, nothing left to deliver (output goes straight to the
     * drop-off).
     *
     * @param noFood whether a meal just found nothing to take (no food point or pocket food for this decision)
     */
    private Schedule.Snapshot catchUpSnapshot(long day, long dayTime, FoodLookup foodLookup, boolean noFood) {
        SettlerFood food = settler.getFood();
        return new Schedule.Snapshot(day, dayTime, food.level(),
                SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE),
                CivModConfig.PROVISION_NUTRITION.get(), !noFood && SettlerFood.choose(pocketStacks(), food.level()).isPresent(),
                () -> !noFood && foodLookup.get().isPresent(), settler.isHomeless(), false, food.forageDay(), false,
                Activity.IDLE, () -> false, food.morningDay() == day, food.eatenDay() == day, false);
    }

    /**
     * Eats one item from the pockets on the spot (catch-up).
     *
     * @return whether there was something to eat
     */
    private boolean eatFromPockets(ServerLevel level) {
        Optional<Integer> choice = SettlerFood.choose(pocketStacks(), settler.getFood().level());
        choice.ifPresent(slot -> eatItem(level, settler.getInventory().getItem(Settler.POCKETS_START + slot).split(1), null));
        return choice.isPresent();
    }

    /**
     * Simulates some work ticks during catch-up through the category loops, in job priority order.
     *
     * @return the number of rolls
     */
    private int simulateWork(ServerLevel level, long day, double workTicks) {
        int rolls = 0;
        double remaining = workTicks;
        for (SettlerJob job : settler.getJobs()) {
            if (catchUpEnded(job.jobBlock()) || remaining <= 0.0) {
                continue;
            }
            CategoryLoop loop = loop(job);
            if (loop == null) {
                endCatchUpJob(job.jobBlock());
                continue;
            }
            CategoryLoop.SimulatedWork simulated = loop.simulateWork(level, job, day, remaining);
            rolls += simulated.rolls();
            remaining = simulated.remaining();
        }
        return rolls;
    }

    /**
     * @return the day time of the hour being simulated in catch-up
     */
    long catchUpDayTime() {
        return cuDayTime;
    }

    /**
     * Ends a job for the rest of the catch-up run (it shows "Drop-off full" afterwards).
     *
     * @param jobBlock the JobBlock position
     */
    void endCatchUpJob(BlockPos jobBlock) {
        workplace(jobBlock).setCatchUpEnded(true);
    }

    private boolean catchUpEnded(BlockPos jobBlock) {
        return existingWorkplace(jobBlock).map(Workplace::catchUpEnded).orElse(false);
    }

    /**
     * Delivers output straight into the drop-off; what does not fit goes into the buffer.
     *
     * @param level  the level
     * @param site   the JobBlock site
     * @param stacks the output
     * @return false when the buffer is full as well (the job ends)
     */
    boolean deliverDirect(ServerLevel level, JobBlockSite site, List<ItemStack> stacks) {
        // No walking in catch-up: the shift alone decides whether a Clerk drop-off takes into its linked storage.
        Stock dropOff = ClerkStorage.stock(level, site.dropOff(), cuDayTime, true);
        JobBuffer buffer = workplace(site.pos()).buffer();
        for (ItemStack stack : stacks) {
            ItemStack rest = dropOff.insert(stack);
            cuItems += stack.getCount() - rest.getCount();
            if (!rest.isEmpty()) {
                buffer.add(rest);
                cuItems += rest.getCount();
            }
        }
        return !buffer.isFull();
    }

    // ---------------------------------------------------------------- food

    private void accountHunger(ServerLevel level) {
        SettlerFood food = settler.getFood();
        if (food.isAway() || missions.isAway()) {
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

    private Optional<ClerkStorage.FoodPoint> foodPointWithFood(ServerLevel level, long dayTime) {
        SettlementData data = SettlementData.get(level);
        return data.settlement(settler.getSettlementId())
                .flatMap(settlement -> ClerkStorage.nearestFood(level, settlement, settler.blockPosition(), dayTime, ownClerk(),
                        clerk -> true));
    }

    /**
     * @return the Clerk JobBlock this settler works as Clerk, if any (a Clerk eats from its own storage)
     */
    Optional<BlockPos> ownClerk() {
        return settler.getJobs().stream().filter(job -> job.jobType().category() == JobCategory.LOGISTICS).map(SettlerJob::jobBlock)
                .findFirst();
    }

    private void startFetchFood(ServerLevel level, ClerkStorage.FoodPoint point, MealPurpose purpose) {
        mealPurpose = purpose;
        foodPoint = point;
        hungryNoFood = false;
        moveTo(level, Activity.FETCH_FOOD, point.visit().target(), point.visit().reach());
    }

    /**
     * At a food point: eats until full (not in the morning unless hungry), puts back leftover containers and refills
     * the provisions, from what the access rule lets the settler reach.
     *
     * @param atCounter whether the settler is served at the counter of a working Clerk (catch-up: always)
     */
    private void visitFoodPoint(ServerLevel level, ClerkStorage.FoodPoint point, long dayTime, boolean atCounter) {
        boolean own = ownClerk().filter(point.clerk()::equals).isPresent();
        Stock stock = own ? ClerkStorage.stock(level, point.clerk(), true) : ClerkStorage.stock(level, point.clerk(), dayTime, atCounter);
        if (stock.isEmpty()) {
            return;
        }
        SettlerFood food = settler.getFood();
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (stack.is(Items.BOWL) || stack.is(Items.GLASS_BOTTLE)) {
                settler.getInventory().setItem(i, stock.insert(stack));
            }
        }
        if (Schedule.eatsAtFoodPoint(mealPurpose, food.level())) {
            while (food.level() < SettlerFood.MAX) {
                Optional<Integer> slot = SettlerFood.choose(stock.contents(), food.level());
                if (slot.isEmpty()) {
                    break;
                }
                ItemStack item = stock.takeOne(slot.get());
                if (item.isEmpty()) {
                    break;
                }
                eatItem(level, item, stock);
            }
        }
        int wanted = CivModConfig.PROVISION_NUTRITION.get();
        while (SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) < wanted) {
            List<ItemStack> contents = stock.contents();
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
            ItemStack item = stock.takeOne(slot);
            ItemStack rest = addToPockets(item);
            if (!rest.isEmpty()) {
                stock.insert(rest);
                break;
            }
        }
    }

    private void eatItem(ServerLevel level, ItemStack item, @Nullable Stock stock) {
        net.minecraft.world.food.FoodProperties properties = item.get(net.minecraft.core.component.DataComponents.FOOD);
        if (properties == null) {
            return;
        }
        settler.getFood().eat(properties);
        hungryNoFood = false;
        eatingEffects(level, item);
        ItemStack rest = SettlerFood.remainder(item);
        if (stock != null) {
            rest = stock.insert(rest);
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
        moveTo(level, Activity.FORAGE, outsideTarget(level));
    }

    /**
     * Foraging: walk towards the border, vanish after at most {@value #FORAGE_WALK_TICKS} ticks or at the border.
     * When it is over the Schedule says {@link Intent#END_FORAGING}: see {@link #returnFromForaging}.
     */
    private void forage(ServerLevel level) {
        SettlerFood food = settler.getFood();
        if (food.isAway()) {
            return;
        }
        if (activity != Activity.FORAGE || target == null) {
            forageStart = level.getGameTime();
            moveTo(level, Activity.FORAGE, outsideTarget(level));
        }
        checkStuck(level);
        if (isAtTarget() || level.getGameTime() - forageStart >= FORAGE_WALK_TICKS) {
            food.vanish(settler.blockPosition());
            settler.getNavigation().stop();
            settler.setInvisible(true);
            target = null;
        }
    }

    /**
     * Back from foraging ({@link Intent#END_FORAGING}, live and catch-up): reappears where it vanished, with the day's
     * food.
     */
    private void returnFromForaging() {
        SettlerFood food = settler.getFood();
        if (food.isAway()) {
            settler.setInvisible(false);
        }
        food.addFood(Schedule.foragingFood());
        food.endForaging();
    }

    /**
     * @param level the level
     * @return a spot just outside the settlement, away from its anchor (foraging, leaving for a mission)
     */
    BlockPos outsideTarget(ServerLevel level) {
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

    // ---------------------------------------------------------------- jobs and EXP

    /**
     * Found resources of a job for the GUI.
     *
     * @param job the job
     * @return the found block types with whether the current tool can obtain them
     */
    public List<JobProfile.Found> found(SettlerJob job) {
        CategoryLoop loop = loop(job);
        return loop == null ? List.of() : loop.found(job);
    }

    /**
     * @param job a job
     * @return the level of the JobBlock of that job (1 if unknown)
     */
    public int jobBlockLevel(SettlerJob job) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return SettlementRules.MIN_LEVEL;
        }
        return JobBlockSite.levelAt(level, job.jobBlock());
    }

    /**
     * @param job a job
     * @return the level the settler works at in that job (its skill, at most the JobBlock cap)
     */
    public int effectiveLevel(SettlerJob job) {
        return settler.getSkills().effectiveLevel(job.jobType(), SettlementRules.skillCap(jobBlockLevel(job)));
    }

    /**
     * Grants EXP in a job; a level-up is shown to nearby players.
     *
     * @param level  the level
     * @param job    the job
     * @param amount the EXP
     * @param cap    the skill cap of the job's JobBlock
     */
    void gainExp(ServerLevel level, SettlerJob job, int amount, int cap) {
        SkillRules rules = SkillRules.get();
        int gained = settler.getSkills().addExp(job.jobType(), amount, cap, rules);
        if (gained <= 0 || quiet) {
            return;
        }
        showLevelUp(level, job);
    }

    /**
     * Shows a level-up in a job to nearby players (particles, sound, overlay message).
     *
     * @param level the level
     * @param job   the job
     */
    void showLevelUp(ServerLevel level, SettlerJob job) {
        int newLevel = settler.getSkills().get(job.jobType()).level();
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

    /**
     * Sends a chat message to the players near the settler (not during catch-up).
     *
     * @param level   the level
     * @param message the message
     * @param range   the distance
     */
    void announce(ServerLevel level, net.minecraft.network.chat.Component message, double range) {
        if (quiet) {
            return;
        }
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.distanceToSqr(settler) <= range * range) {
                player.sendSystemMessage(message);
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
            if (foodPoint != null) {
                visitFoodPoint(level, foodPoint, Math.floorMod(level.getOverworldClockTime(), 24000L), foodPoint.visit().counter());
            }
            activity = Activity.IDLE;
            mealPurpose = null;
            foodPoint = null;
            decide(level);
            return;
        }
        if (activity == Activity.FORAGE) {
            return;
        }
        if (activeJob == null) {
            return;
        }
        if (activity == Activity.DELIVER) {
            deliver(level, activeJob.jobBlock(), deliveryVisit != null && deliveryVisit.counter());
            activity = Activity.IDLE;
            deliveryVisit = null;
            decide(level);
        } else if (activity == Activity.JOB) {
            CategoryLoop loop = loop(activeJob);
            if (loop != null) {
                loop.tickAtTarget(level, activeJob, target);
            }
        }
    }

    /**
     * Delivers the buffer of a job into its drop-off, under the access rule if the drop-off is a Clerk.
     *
     * @param atCounter whether the settler stands at the counter of a working Clerk
     */
    private void deliver(ServerLevel level, BlockPos jobBlock, boolean atCounter) {
        JobBuffer buffer = buffer(jobBlock);
        BlockPos dropOff = JobBlockSite.at(level, jobBlock).map(JobBlockSite::dropOff).orElse(jobBlock);
        Stock stock = ClerkStorage.stock(level, dropOff, Math.floorMod(level.getOverworldClockTime(), 24000L), atCounter);
        boolean all = !stock.isEmpty() && buffer.deliver(stack -> stack.getCount() - stock.insert(stack).getCount());
        if (!all) {
            block(level, jobBlock, JobStatus.DROP_OFF_FULL);
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
