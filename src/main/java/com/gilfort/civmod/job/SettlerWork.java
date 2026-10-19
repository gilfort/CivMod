package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.job.Schedule.Decision;
import com.gilfort.civmod.job.Schedule.Intent;
import com.gilfort.civmod.job.Schedule.MealPurpose;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.DayClock;
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
import net.minecraft.world.item.Item;
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
    /**
     * Start of the end-of-work window (17:30; day time 0 = 06:00); each settler's work ends at its daily offset inside
     * the window ({@link #dayTimes}).
     */
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

    /** Start of the going-to-bed window (20:00); each settler goes to bed at its daily offset inside the window. */
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
    private final ClerkLoop clerks;
    /** The Deliveryman's loop: a second Logistics loop beside the Clerk's. */
    private final DeliverymanLoop deliverymen;

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
    /** Clerk JobBlocks whose counter this settler found unreachable, with the day (only their block slots that day). */
    private final Map<BlockPos, Long> unreachableCounters = new HashMap<>();
    /** Reach of the current walking target outside job steps. */
    private double targetReach = REACH;
    /** Thought bubbles: sent when what the settler does changes. */
    private final SettlerThoughts thoughts;
    /** What the held item was last chosen for: it changes with the activity (vault Systems/Thought Bubbles.md). */
    private @Nullable HandKey handKey;

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
    /** The day time of the hour being simulated in catch-up ({@link #catchUpDayTime}). */
    private long cuDayTime;

    /**
     * @param settler the settler
     */
    public SettlerWork(Settler settler) {
        this.settler = settler;
        loops.put(JobCategory.RESOURCE_COLLECTION, new ResourceCollectionLoop(this));
        missions = new MissionLoop(this);
        loops.put(JobCategory.MISSION, missions);
        clerks = new ClerkLoop(this);
        loops.put(JobCategory.LOGISTICS, clerks);
        // The Craftsperson is the only Producing Job so far.
        loops.put(JobCategory.PRODUCING, new CraftspersonLoop(this));
        deliverymen = new DeliverymanLoop(this);
        thoughts = new SettlerThoughts(settler);
    }

    /**
     * @return every work loop of this settler (the category loops and the Deliveryman's)
     */
    private List<CategoryLoop> allLoops() {
        List<CategoryLoop> all = new ArrayList<>(loops.values());
        all.add(deliverymen);
        return all;
    }

    /**
     * Runs one decision now (for tests; live decisions run every {@value #DECISION_INTERVAL} ticks).
     *
     * @param level the level
     */
    public void decideNow(ServerLevel level) {
        decide(level);
    }

    /**
     * Decides once as while catching up: effects, messages and thought bubbles are held back (tests).
     *
     * @param level the level
     */
    public void decideQuietly(ServerLevel level) {
        boolean was = quiet;
        quiet = true;
        try {
            decide(level);
        } finally {
            quiet = was;
        }
    }

    /**
     * @return whether the settler is caught up (no effects, messages or sounds)
     */
    boolean quiet() {
        return quiet;
    }

    /** Tells every loop that the settler's work day is over. */
    private void endDay(ServerLevel level) {
        for (SettlerJob job : settler.getJobs()) {
            CategoryLoop loop = loop(job);
            if (loop != null) {
                loop.dayEnded(level, job);
            }
        }
    }

    /**
     * @param day the day
     * @return this settler's waking, end of work and bed times on that day (daily offsets inside the Schedule windows);
     *         a Clerk wakes early (05:30–06:00, its shift opens then) and its work ends with its shift at 18:30, a
     *         settler back from a mission ended its work day on its return
     */
    public Schedule.DayTimes dayTimes(long day) {
        boolean clerk = ownClerk().isPresent();
        Schedule.DayTimes times = Schedule.dayTimes(settler.getUUID(), day, clerk);
        if (clerk) {
            times = times.withWorkEnd(ClerkStorage.SHIFT_END);
        }
        OptionalLong returned = missions.returnedOn(day);
        if (returned.isPresent() && returned.getAsLong() < times.workEnd()) {
            times = times.withWorkEnd(returned.getAsLong());
        }
        return times;
    }

    /**
     * @return whether this settler is a Clerk on a fatigue break (its counter starts no new service)
     */
    boolean clerkOnBreak() {
        return clerks.onBreak();
    }

    /**
     * A hand-out at the counter of this settler's Clerk JobBlock: EXP (base EXP × JobBlock level), in live play with an
     * arm swing and a sound.
     *
     * @param level   the level
     * @param clerk   the Clerk JobBlock
     * @param effects whether to show the swing and sound
     */
    void handedOut(ServerLevel level, BlockPos clerk, boolean effects) {
        Optional<SettlerJob> job = settler.getJobs().stream().filter(found -> found.jobBlock().equals(clerk)).findFirst();
        if (job.isEmpty()) {
            return;
        }
        int jobBlockLevel = jobBlockLevel(job.get());
        gainExp(level, job.get(), job.get().jobType().baseExp() * jobBlockLevel, SettlementRules.skillCap(jobBlockLevel));
        if (effects && !quiet) {
            settler.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            level.playSound(null, settler.getX(), settler.getY(), settler.getZ(), net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.4F, 1.0F);
        }
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
        if (job.jobType() == ModJobs.DELIVERYMAN.get()) {
            return deliverymen;
        }
        CategoryLoop loop = loops.get(job.jobType().category());
        // The Logistics category has two loops: the Clerk's (here) and the Deliveryman's (above).
        return loop == clerks && job.jobType() != ModJobs.CLERK.get() ? null : loop;
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
        allLoops().forEach(loop -> loop.restore(snapshot));
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
     * @return the status of that job as text, naming what it is about (e.g. "Collecting – Lumberjack")
     */
    public net.minecraft.network.chat.Component statusText(BlockPos jobBlock) {
        return existingWorkplace(jobBlock).map(workplace -> workplace.status().displayName(workplace.statusSubject()))
                .orElseGet(JobStatus.IDLE::displayName);
    }

    /**
     * @param job a job
     * @return a Deliveryman's carry capacity in stacks (9 at skill level 1 to 27 at 100), 0 for other jobs
     */
    public int carryStacks(SettlerJob job) {
        return loop(job) == deliverymen ? DeliverymanLoop.carryStacks(effectiveLevel(job)) : 0;
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
        // The loops first: they still see the Workplace (a Producing Job empties its buffer into the drop-off).
        allLoops().forEach(loop -> loop.forget(jobBlock));
        Workplace workplace = jobWorkplaces.remove(jobBlock);
        if (workplace != null && settler.level() instanceof ServerLevel level) {
            workplace.buffer().stacks().forEach(stack -> settler.spawnAtLocation(level, stack.copy()));
        }
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

    /** Decides the next step, then shows what changed (thought bubble, held item). */
    private void decide(ServerLevel level) {
        decideStep(level);
        observe();
    }

    private void decideStep(ServerLevel level) {
        long clock = level.getOverworldClockTime();
        Schedule.Moment moment = moment(clock);
        long dayTime = moment.dayTime();
        long day = moment.day();
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
        if (activity == Activity.SLEEP && !settler.isSleeping()) {
            // Woken by something else than the Schedule (vanilla wakes sleepers on its own): up as after the night,
            // so the Schedule can send it to bed again and nothing holds it in place.
            wake();
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
     * @param clock a clock value
     * @return this settler's day and day time then: a Clerk's day begins at its early waking time before 06:00
     */
    private Schedule.Moment moment(long clock) {
        return Schedule.moment(clock, dayTimes(DayClock.day(clock) + 1));
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
                .anyMatch(job -> !buffer(job.jobBlock()).isEmpty() && !status(job.jobBlock()).waitsForRoom());
        return new Schedule.Snapshot(day, dayTime, food.level(),
                SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE),
                CivModConfig.PROVISION_NUTRITION.get(), SettlerFood.choose(pocketStacks(), food.level()).isPresent(),
                () -> foodLookup.get().isPresent(), () -> clerkToWaitAt(level).isPresent(), settler.isHomeless(),
                settler.isSleeping() && !woke, food.forageDay(), nightSkipped, activity, () -> jobStepHolds(level),
                food.morningDay() == day, food.eatenDay() == day, waitStart(day), deliveryPending, dayTimes(day));
    }

    /**
     * @param day the day
     * @return the day time the settler began waiting for food today, {@link Schedule#NOT_WAITING} if it did not
     */
    private long waitStart(long day) {
        SettlerFood food = settler.getFood();
        return food.waitDay() == day ? food.waitTime() : Schedule.NOT_WAITING;
    }

    /**
     * @return the Clerk JobBlock this settler waits at for food (its own as a Clerk, else the nearest), empty if the
     *         settlement has none
     */
    private Optional<BlockPos> clerkToWaitAt(ServerLevel level) {
        Optional<BlockPos> own = ownClerk();
        if (own.isPresent()) {
            return own;
        }
        return SettlementData.get(level).settlement(settler.getSettlementId())
                .flatMap(settlement -> ClerkStorage.nearestClerk(settlement, settler.blockPosition()));
    }

    private boolean jobStepHolds(ServerLevel level) {
        SettlerJob job = activeJob;
        CategoryLoop loop = job == null ? null : loop(job);
        return activity == Activity.JOB && loop != null && loop.holdsDecision(level, job);
    }

    // ---------------------------------------------------------------- thought bubbles and held item

    /** What the held item depends on. */
    private record HandKey(Activity activity, @Nullable SettlerJob job, JobStatus status) {
    }

    /**
     * @return the last thought bubbles this settler sent (bubbles and problem on / off), oldest first
     */
    public List<ThoughtBubble.Show> recentThoughts() {
        return thoughts.recent();
    }

    /**
     * Shows the Job's thought bubble after an automatic assignment.
     *
     * @param job the job the settler was automatically assigned to
     */
    public void assigned(SettlerJob job) {
        thoughts.assigned(job, !quiet);
    }

    /**
     * Sends the problems that are on to a player that starts seeing the settler.
     *
     * @param player the player
     */
    public void showProblemsTo(net.minecraft.server.level.ServerPlayer player) {
        thoughts.showProblemsTo(player);
    }

    /** Sends the thought bubbles of what changed and lets the held item follow the activity. */
    private void observe() {
        JobStatus status = activeJob == null ? JobStatus.IDLE : status(activeJob.jobBlock());
        thoughts.update(new SettlerThoughts.Now(activeJob, activityBubble(status), problems()), !quiet);
        HandKey key = new HandKey(activity, activeJob, status);
        if (!key.equals(handKey)) {
            handKey = key;
            ItemStack held = heldItem(status);
            if (!ItemStack.matches(held, settler.getItemBySlot(EquipmentSlot.MAINHAND))) {
                settler.setItemSlot(EquipmentSlot.MAINHAND, held);
            }
        }
    }

    private @Nullable ThoughtBubble activityBubble(JobStatus status) {
        return switch (activity) {
            case DELIVER -> ThoughtBubble.DROP_OFF;
            case GO_TO_BED -> ThoughtBubble.BED;
            case FETCH_FOOD, WAIT_FOR_FOOD, EATING -> ThoughtBubble.EATING;
            case JOB -> switch (status) {
                case FETCHING_TOOL, FETCHING_INPUTS, COLLECTING -> ThoughtBubble.COLLECT;
                case CRAFTING -> ThoughtBubble.CRAFTING;
                case DELIVERING, UNLOADING -> ThoughtBubble.DROP_OFF;
                case ON_MISSION -> ThoughtBubble.MISSION;
                default -> null;
            };
            default -> null;
        };
    }

    /** The problems that are on now (one per kind; the first job with it gives the icon). */
    private Map<ThoughtBubble, Item> problems() {
        Map<ThoughtBubble, Item> on = new EnumMap<>(ThoughtBubble.class);
        for (SettlerJob job : settler.getJobs()) {
            Job type = job.jobType();
            switch (status(job.jobBlock())) {
                case NO_TOOL -> on.putIfAbsent(ThoughtBubble.NO_TOOL,
                        type.profile(JobProfile.class).map(JobProfile::toolIcon).orElseGet(() -> type.bubbleIcon().getItem()));
                case DROP_OFF_FULL, STORAGE_FULL -> on.putIfAbsent(ThoughtBubble.DROP_OFF_FULL,
                        SettlerThoughts.problemIcon(ThoughtBubble.DROP_OFF_FULL));
                case NO_WORK_PLACE -> on.putIfAbsent(ThoughtBubble.NO_WORK_PLACE, type.icon().getItem());
                case MISSING_INPUTS -> on.putIfAbsent(ThoughtBubble.MISSING_INPUTS,
                        SettlerThoughts.problemIcon(ThoughtBubble.MISSING_INPUTS));
                default -> {
                }
            }
        }
        if (hungryNoFood) {
            on.put(ThoughtBubble.HUNGRY_NO_FOOD,
                    SettlerThoughts.problemIcon(ThoughtBubble.HUNGRY_NO_FOOD));
        }
        return on;
    }

    /**
     * The item in the hand for what the settler does: working, the job's real tool or its display item (a Craftsperson
     * carrying its result shows the result, a Deliveryman its bundle on the whole tour); anything else (eating, bed,
     * drop-off, fetching) an empty hand. Always a display copy, never an inventory item.
     */
    private ItemStack heldItem(JobStatus status) {
        SettlerJob job = activeJob;
        if (job == null) {
            return ItemStack.EMPTY;
        }
        Job type = job.jobType();
        boolean carriesResult = type.category() == JobCategory.PRODUCING
                && (activity == Activity.DELIVER || (activity == Activity.JOB && status == JobStatus.DELIVERING));
        if (carriesResult) {
            return buffer(job.jobBlock()).stacks().stream().filter(stack -> !stack.isEmpty()).findFirst()
                    .map(stack -> stack.copyWithCount(1)).orElse(ItemStack.EMPTY);
        }
        if (loop(job) == deliverymen && (activity == Activity.DELIVER || activity == Activity.JOB)) {
            return type.displayItem().map(ItemStack::new).orElse(ItemStack.EMPTY);
        }
        if (activity != Activity.JOB || status == JobStatus.FETCHING_TOOL || status == JobStatus.FETCHING_INPUTS
                || status == JobStatus.DELIVERING) {
            return ItemStack.EMPTY;
        }
        Optional<JobProfile> profile = type.profile(JobProfile.class);
        if (profile.isPresent()) {
            return SettlerTools.best(settler, profile.get()::isTool).map(ItemStack::copy).orElse(ItemStack.EMPTY);
        }
        return type.displayItem().map(ItemStack::new).orElse(ItemStack.EMPTY);
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
        if (decision.waitStarted()) {
            food.startWaiting(day, dayTime);
        }
        if (decision.complain()) {
            complain(level, day);
        }
        SettlerJob delivery = null;
        if (decision.afterWork()) {
            endDay(level);
            // Jobs show idle after work, except the first one with something to deliver.
            for (SettlerJob job : settler.getJobs()) {
                if (!buffer(job.jobBlock()).isEmpty() && !status(job.jobBlock()).waitsForRoom()) {
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
            case WAIT_FOR_FOOD -> waitForFood(level, day, dayTime);
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

    /**
     * Waits for food at the queue of the nearest Clerk JobBlock (where its counter is now). A Clerk waits at its own
     * counter: it works its shift meanwhile.
     */
    private void waitForFood(ServerLevel level, long day, long dayTime) {
        if (ownClerk().isPresent()) {
            work(level, day, dayTime);
            return;
        }
        Optional<BlockPos> clerk = clerkToWaitAt(level);
        if (clerk.isEmpty()) {
            stop();
            return;
        }
        ClerkStorage.Visit visit = reachableVisit(level, clerk.get(), ClerkStorage.visit(level, clerk.get(), DayClock.dayTime(level)));
        if (activity != Activity.WAIT_FOR_FOOD || !visit.target().equals(target)) {
            stop();
            moveTo(level, Activity.WAIT_FOR_FOOD, visit.target(), visit.reach());
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
     * @param jobBlock a JobBlock position
     * @param status   the new status of that job
     * @param subject  what the status is about
     */
    void setStatus(BlockPos jobBlock, JobStatus status, net.minecraft.network.chat.Component subject) {
        workplace(jobBlock).setStatus(status, subject);
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
        ClerkStorage.Visit visit;
        activeJob = job;
        if (loop(job) == deliverymen) {
            // A Deliveryman unloads into its store as staff: at the block, no counter.
            visit = ClerkStorage.blockVisit(deliverymen.unloadTarget(level, job));
            workplace(job.jobBlock()).setStatus(JobStatus.UNLOADING, deliverymen.unloadName(level, job));
        } else {
            BlockPos dropOff = JobBlockSite.at(level, job.jobBlock()).map(JobBlockSite::dropOff).orElse(job.jobBlock());
            visit = reachableVisit(level, dropOff, ClerkStorage.visit(level, dropOff, DayClock.dayTime(level)));
            workplace(job.jobBlock()).setStatus(JobStatus.DELIVERING);
        }
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
        if (activity == Activity.IDLE || target == null) {
            return;
        }
        double distance = settler.position().distanceTo(Vec3.atCenterOf(target));
        if (isAtTarget()) {
            // Being there is progress: a settler that stood at its target for long (a Clerk at its counter, a customer
            // in the queue) and is pushed away walks back instead of being teleported at once.
            bestDistance = distance;
            lastProgressTick = level.getGameTime();
            return;
        }
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
        long earliest = catchUpTo - CivModConfig.CATCH_UP_MAX_DAYS.get() * DayClock.DAY_TICKS;
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
            if (!loop.catchUpReady(level, job, site.get(), DayClock.day(clock), DayClock.dayTime(clock))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Prepares a catch-up run.
     */
    void startCatchUp() {
        cuDays = Math.max(0, catchUpTo - catchUpFrom()) / (double) DayClock.DAY_TICKS;
        quiet = true;
        allLoops().forEach(CategoryLoop::startCatchUp);
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
        long hourStart = day * DayClock.DAY_TICKS + hour * 1000L;
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
        // The same daily offset as in live play; the hourly segments only approximate it (accepted).
        Schedule.DayTimes times = dayTimes(day);
        long workEndDayTime = times.workEnd();
        long workEndTick = day * DayClock.DAY_TICKS + workEndDayTime;
        // Not up before its waking time (Schedule): the day starts then. A Clerk's early start before 06:00 is not
        // simulated (hour 0 begins at 06:00; accepted).
        from = Math.max(from, day * DayClock.DAY_TICKS + times.wake());
        if (to <= from) {
            return 0;
        }
        long dayTime = from - day * DayClock.DAY_TICKS;
        cuDayTime = dayTime;
        SettlerFood food = settler.getFood();

        // Start of the hour: back from foraging, the morning plan, a meal if hungry.
        if (decideCatchUp(level, day, dayTime) == Intent.FORAGE) {
            // Away while foraging (no hunger, no work).
            if (to <= workEndTick) {
                return 0;
            }
            // Back at the end of work (the Schedule hands out the food): the rest of the hour is an ordinary evening.
            from = workEndTick;
            dayTime = workEndDayTime;
            decideCatchUp(level, day, dayTime);
        }
        long awake = to - from;
        long work = Math.max(0, Math.min(to, workEndTick) - from);

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
        if (to >= workEndTick) {
            endDay(level);
            // Work is over: the evening meal.
            decideCatchUp(level, day, Math.max(dayTime, workEndDayTime));
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
                Schedule.Snapshot snapshot = catchUpSnapshot(level, day, dayTime, foodLookup, noFood);
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
                if (decision.waitStarted()) {
                    food.startWaiting(day, dayTime);
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
                        ClerkStorage.FoodPoint point = foodLookup.get().orElseThrow();
                        if (visitFoodPoint(level, point, dayTime, true)) {
                            ClerkCounter.handOut(level, point.clerk(), false);
                        }
                        mealPurpose = null;
                    }
                    case START_FORAGING -> {
                        food.startForaging(day);
                        return Intent.FORAGE;
                    }
                    case WAIT_FOR_FOOD -> {
                        // Waiting at a Clerk's queue: no work this hour (a Clerk waits at its own counter, at work);
                        // the Schedule sends it foraging once the 2 hours are over.
                        return ownClerk().isPresent() ? Intent.WORK : Intent.IDLE;
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
    private Schedule.Snapshot catchUpSnapshot(ServerLevel level, long day, long dayTime, FoodLookup foodLookup, boolean noFood) {
        SettlerFood food = settler.getFood();
        return new Schedule.Snapshot(day, dayTime, food.level(),
                SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE),
                CivModConfig.PROVISION_NUTRITION.get(), !noFood && SettlerFood.choose(pocketStacks(), food.level()).isPresent(),
                () -> !noFood && foodLookup.get().isPresent(), () -> clerkToWaitAt(level).isPresent(), settler.isHomeless(),
                false, food.forageDay(), false, Activity.IDLE, () -> false, food.morningDay() == day, food.eatenDay() == day,
                waitStart(day), false, dayTimes(day));
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
     * The day time of the hour being simulated in catch-up, at which Clerk access is checked. Catch-up starts every day
     * at 06:00 at the earliest, when every Clerk (up 05:30–06:00) is already on shift; this also makes the level's day
     * enough for {@link ClerkStorage#working}.
     *
     * @return the simulated day time
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
        // A delivery into a Clerk: its stock rules are checked right away; a harvest in a drop-off may be a pickup.
        StockRules.check(level, site.dropOff(), cuDayTime);
        Pickups.delivered(level, site, !buffer.isEmpty());
        return !buffer.isFull();
    }

    // ---------------------------------------------------------------- food

    private void accountHunger(ServerLevel level) {
        SettlerFood food = settler.getFood();
        if (food.isAway() || missions.isAway()) {
            weightedWorkTicks = 0.0;
            return;
        }
        Schedule.Moment moment = moment(level.getOverworldClockTime());
        long awake = moment.dayTime() < dayTimes(moment.day()).bed() && !settler.isSleeping() ? DECISION_INTERVAL : 0L;
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
        } else if (activity == Activity.WAIT_FOR_FOOD) {
            key = "waiting_for_food";
        } else if (hungryNoFood) {
            key = food.level() <= 0 ? "starving" : "hungry";
        }
        return Optional.ofNullable(key).map(k -> net.minecraft.network.chat.Component.translatable("civmod.settler.status." + k));
    }

    private Optional<ClerkStorage.FoodPoint> foodPointWithFood(ServerLevel level, long dayTime) {
        SettlementData data = SettlementData.get(level);
        return data.settlement(settler.getSettlementId())
                .flatMap(settlement -> ClerkStorage.nearestFood(level, settlement, settler.blockPosition(), dayTime, ownClerk(),
                        clerk -> !counterUnreachable(level, clerk)));
    }

    /**
     * Counters further away than this are assumed reachable: a path search only ends conclusively well within the
     * follow range (32). Further away it is cut off (search range, node budget) and would make a reachable counter
     * unreachable for the whole day, leaving only the Clerk's block slots. Loops that decide again on the way check
     * once the settler comes this close.
     */
    private static final double COUNTER_PATH_CHECK = 16.0;

    /**
     * A visit as this settler can make it: a counter it cannot walk to only offers the Clerk's block slots (checked
     * with one path search when the walk starts, remembered for the day).
     *
     * @param level the level
     * @param pos   the Clerk JobBlock or container visited
     * @param visit the visit by the access rule
     * @return the visit the settler makes
     */
    ClerkStorage.Visit reachableVisit(ServerLevel level, BlockPos pos, ClerkStorage.Visit visit) {
        if (!visit.counter() || ownClerk().filter(pos::equals).isPresent()) {
            return visit;
        }
        if (counterUnreachable(level, pos)) {
            return ClerkStorage.blockVisit(pos);
        }
        if (settler.position().distanceTo(Vec3.atCenterOf(visit.target())) <= COUNTER_PATH_CHECK) {
            net.minecraft.world.level.pathfinder.Path path = settler.getNavigation().createPath(visit.target(), (int) visit.reach());
            // No path at all says nothing: vanilla searches none while the settler is in the air (a jump on its walk).
            // Only a searched path that ends short of the counter makes it unreachable for the day.
            if (path != null && !path.canReach()) {
                unreachableCounters.put(pos.immutable(), DayClock.day(level));
                return ClerkStorage.blockVisit(pos);
            }
        }
        return visit;
    }

    /**
     * @param level the level
     * @param clerk a Clerk JobBlock
     * @return whether this settler found its counter unreachable today
     */
    boolean counterUnreachable(ServerLevel level, BlockPos clerk) {
        Long day = unreachableCounters.get(clerk);
        return day != null && day == DayClock.day(level);
    }

    /**
     * @return the Clerk JobBlock this settler works as Clerk, if any (a Clerk eats from its own storage)
     */
    Optional<BlockPos> ownClerk() {
        return settler.getJobs().stream().filter(job -> job.jobType() == ModJobs.CLERK.get()).map(SettlerJob::jobBlock)
                .findFirst();
    }

    private void startFetchFood(ServerLevel level, ClerkStorage.FoodPoint found, MealPurpose purpose) {
        ClerkStorage.FoodPoint point = new ClerkStorage.FoodPoint(found.clerk(), reachableVisit(level, found.clerk(), found.visit()));
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
     * @return whether a working Clerk handed something out (food from its whole storage at the counter)
     */
    private boolean visitFoodPoint(ServerLevel level, ClerkStorage.FoodPoint point, long dayTime, boolean atCounter) {
        boolean own = ownClerk().filter(point.clerk()::equals).isPresent();
        Stock stock = own ? ClerkStorage.stock(level, point.clerk(), true) : ClerkStorage.stock(level, point.clerk(), dayTime, atCounter);
        if (stock.isEmpty()) {
            return false;
        }
        boolean handedOut = false;
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
                handedOut = true;
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
            handedOut |= !item.isEmpty();
            ItemStack rest = addToPockets(item);
            if (!rest.isEmpty()) {
                stock.insert(rest);
                break;
            }
        }
        return handedOut && stock.counter() && !own;
    }

    /**
     * Waits at the counter of a working Clerk until it is this settler's turn and the service time is over.
     *
     * @param level the level
     * @param clerk the Clerk JobBlock
     * @return whether the settler is served now
     */
    boolean waitAtCounter(ServerLevel level, BlockPos clerk) {
        settler.getNavigation().stop();
        settler.getLookControl().setLookAt(Vec3.atCenterOf(clerk));
        return ClerkCounter.serve(level, clerk, settler.getUUID());
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
     * @param jobBlock a JobBlock of one of the settler's jobs
     * @return the latest scan of that job, empty before the first scan is ready (or without that job)
     */
    public Optional<ScanResult> scan(BlockPos jobBlock) {
        return existingWorkplace(jobBlock).map(Workplace::scan);
    }

    /**
     * @param jobBlock a JobBlock of one of the settler's Resource Collection jobs
     * @return the settler as the Found tab of that JobBlock sees it (best tool, effective skill level), empty without
     *         that job
     */
    public Optional<JobProfile.Worker> worker(BlockPos jobBlock) {
        return settler.getJobs().stream().filter(job -> job.jobBlock().equals(jobBlock)).findFirst()
                .flatMap(job -> job.jobType().profile(JobProfile.class).map(profile -> new JobProfile.Worker(
                        SettlerTools.best(settler, profile::isTool).map(ItemStack::copy).orElse(ItemStack.EMPTY), effectiveLevel(job))));
    }

    /**
     * A found resource of a JobBlock was included again: a job paused with "All resources excluded" or "Nothing to
     * produce" checks again at the next decision instead of after its 30 s.
     *
     * @param jobBlock the JobBlock
     */
    public void recheckExcluded(BlockPos jobBlock) {
        existingWorkplace(jobBlock).filter(workplace -> workplace.status() == JobStatus.ALL_EXCLUDED
                || workplace.status() == JobStatus.NOTHING_TO_PRODUCE).ifPresent(Workplace::unblock);
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
            ClerkStorage.FoodPoint point = foodPoint;
            if (point != null) {
                boolean counter = point.visit().counter() && ownClerk().filter(point.clerk()::equals).isEmpty();
                if (counter && !waitAtCounter(level, point.clerk())) {
                    return;
                }
                if (visitFoodPoint(level, point, DayClock.dayTime(level), point.visit().counter())
                        && counter) {
                    ClerkCounter.handOut(level, point.clerk(), true);
                }
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
        if (activity == Activity.WAIT_FOR_FOOD) {
            // In the queue: stand and face the counter until food arrives or the wait is over (Schedule).
            settler.getNavigation().stop();
            settler.getLookControl().setLookAt(Vec3.atCenterOf(target));
            return;
        }
        if (activeJob == null) {
            return;
        }
        if (activity == Activity.DELIVER) {
            if (loop(activeJob) == deliverymen) {
                deliverymen.unload(level, activeJob);
            } else {
                deliver(level, activeJob.jobBlock(), deliveryVisit != null && deliveryVisit.counter());
            }
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
        Optional<JobBlockSite> site = JobBlockSite.at(level, jobBlock);
        BlockPos dropOff = site.map(JobBlockSite::dropOff).orElse(jobBlock);
        Stock stock = ClerkStorage.stock(level, dropOff, DayClock.dayTime(level), atCounter);
        boolean all = !stock.isEmpty() && buffer.deliver(stack -> stack.getCount() - stock.insert(stack).getCount());
        if (!all) {
            block(level, jobBlock, JobStatus.DROP_OFF_FULL);
        }
        // A harvest in a drop-off: a pickup for the Deliverymen if it is full enough.
        site.ifPresent(found -> Pickups.delivered(level, found, !all && !stock.isEmpty()));
        // A delivery into a Clerk: its stock rules are checked right away.
        StockRules.check(level, dropOff, DayClock.dayTime(level));
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

        /**
         * Starting this goal stops the idle stroll, and the stroll's stop clears the navigation: the path the decision
         * just requested is gone. It is requested again here.
         */
        @Override
        public void start() {
            BlockPos current = target;
            if (current != null && activity != Activity.SLEEP && settler.getNavigation().isDone() && !isAtTarget()) {
                settler.getNavigation().moveTo(current.getX() + 0.5, current.getY(), current.getZ() + 0.5, 0.6);
            }
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
