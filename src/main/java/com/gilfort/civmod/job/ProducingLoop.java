package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.CraftPlan;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.ItemCounts;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.Fuels;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.OccupiedPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Work loop of the Producing Job Category (vault {@code Systems/Producing Jobs.md}, {@code Jobs/Craftsperson.md}) for
 * every Job with a {@link ProducingProfile} (the Craftsperson is the first). A round: take the highest-priority request
 * the Job has a usable checked pattern for (up to {@value #MAX_TRIES} tries; a request it lacks inputs for is skipped for
 * 30 s), claim what it can make in one round, fetch the inputs (collect inventory first, then the posting Clerk's storage
 * under the access rule) into its buffer, work intermediates and the item at the station of each recipe type (the
 * profile's work time per recipe, its "execute recipe" hook) and deliver to the posting Clerk; what does
 * not fit goes to its own drop-off. On "Delivery: Deliveryman" the item goes into its own drop-off with a result
 * delivery for the posting Clerk ({@link Deliveries#postResult}), and it keeps working. There it also does not fetch
 * from stores itself: the inputs its collect inventory lacks are asked for as input deliveries ({@link Deliveries#postInput})
 * from its Home Clerk first, then from the other Clerks of the settlement; while Deliverymen bring them into its collect
 * inventory it works its next priority ("Waiting for inputs").
 * <p>
 * The claim is released when the settler switches job, the day ends, it leaves the JobBlock or dies; fetched inputs
 * go to its own drop-off (leaving the JobBlock: straight into it while it is loaded; death: the death path). The round
 * lives in the job's {@link Workplace}. Catch-up runs the same round without walking.
 */
final class ProducingLoop implements CategoryLoop {

    /** Requests tried per decision. */
    static final int MAX_TRIES = 3;
    /** How long a request the producer lacks inputs for is skipped (30 s). */
    static final long SKIP_TICKS = 600L;
    /** Stations are searched within this distance of the JobBlock. */
    private static final int STATION_RADIUS = 16;
    /** ... and this many blocks up and down. */
    private static final int STATION_HEIGHT = 8;
    /** Arm swing and sound every 0.5 s. */
    private static final int SWING_INTERVAL = 10;
    /** How long a crafted item is shown in the hand. */
    private static final int HAND_TICKS = 20;

    /**
     * Result of picking a request.
     *
     * @param round  the round started, empty if none
     * @param more   whether more requests are left to try at the next decision
     * @param status the job's status without a round ("Missing inputs", "No request"), empty with one
     */
    private record Pick(Optional<Round> round, boolean more, Optional<JobStatus> status) {
    }

    private final SettlerWork shell;
    private final Settler settler;
    private final Fatigue fatigue = new Fatigue();
    /** Where the current fetch or delivery step is made. */
    private ClerkStorage.@Nullable Visit visit;
    private int swingTimer;
    private int handTimer;
    /** Claims saved before loading belong to rounds that are gone: released at the next chance. */
    private boolean releasePending;

    /**
     * @param shell the Settler Shell driving this loop
     */
    ProducingLoop(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public JobStep plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        BlockPos jobBlock = job.jobBlock();
        releaseIfRestored(level);
        Patterns.uncheckFiltered(level, jobBlock, job.jobType());
        Patterns.enforceLimit(level, jobBlock, job.jobType(), settler.getUUID(), profile(job).checkLimit(shell.effectiveLevel(job)));
        Workplace workplace = shell.workplace(jobBlock);
        if (workplace.round() == null) {
            if (workplace.dropOffPending() || (workplace.buffer().shouldDeliver() && deliverable(job, workplace.buffer()))) {
                workplace.setDropOffPending(false);
                return JobStep.DELIVER;
            }
            BlockPos ofDay = station(level, job, site, day);
            if (ofDay == null) {
                return JobStep.blocked(noStation(workplace));
            }
            Pick pick = start(level, job, site, stations(level, job, site, day, ofDay), level.getGameTime(), dayTime, false);
            if (pick.round().isEmpty()) {
                return pick.status().map(JobStep::skip).orElseGet(JobStep::skip);
            }
            workplace.setRound(pick.round().get());
        }
        Round round = Objects.requireNonNull(workplace.round());
        Optional<JobStatus> waiting = round.phase == Round.Phase.WAIT ? waitForInputs(level, site, round, dayTime, level.getGameTime())
                : Optional.empty();
        if (waiting.isPresent()) {
            // Waiting for input deliveries: the next priority works meanwhile.
            return JobStep.skip(waiting.get());
        }
        return switch (round.phase) {
            // WAIT: the inputs arrived (above), the round fetches them at the collect inventory.
            case WAIT, FETCH -> {
                BlockPos source = round.sources.getFirst();
                yield walk(shell.reachableVisit(level, source, ClerkStorage.visit(level, source, dayTime)), JobStatus.FETCHING_INPUTS);
            }
            case CRAFT -> {
                BlockPos station = stepStation(level, job, site, round, day);
                if (station == null) {
                    release(level, jobBlock);
                    yield JobStep.blocked(noStation(workplace));
                }
                round.chargeStations = chargeStations(level, job, site, round, station, day);
                yield walk(new ClerkStorage.Visit(station, SettlerWork.REACH, false), fatigue.onBreak() ? JobStatus.PAUSING : JobStatus.CRAFTING);
            }
            case DELIVER -> {
                BlockPos target = site.block().byDeliveryman() ? site.dropOff() : target(SettlementData.get(level), round.request).orElse(site.dropOff());
                yield walk(shell.reachableVisit(level, target, ClerkStorage.visit(level, target, dayTime)), JobStatus.DELIVERING);
            }
        };
    }

    /** Walks to where the step is made; walking on to the same place keeps how it is reached. */
    private JobStep walk(ClerkStorage.Visit next, JobStatus status) {
        ClerkStorage.Visit current = visit;
        if (current == null || !current.target().equals(next.target())) {
            current = next;
            visit = next;
        }
        return JobStep.walk(current.target(), current.reach(), status);
    }

    /**
     * Picks the highest-priority request with a checked pattern that is not skipped, up to {@value #MAX_TRIES} tries,
     * and claims what can be made of it in one round.
     *
     * A Job burning fuel claims only what the listed fuel in its buffer and in stock covers at its stations (their
     * Remaining Heat first) and fetches that fuel with the inputs; without any it reports "Missing: Fuel".
     *
     * @param stations the stations of the main recipe type worked in parallel (the station of the day first)
     * @param now      the time for skipping (live: game time, catch-up: the simulated clock)
     * @param catchUp  whether the producer reaches every Clerk's counter (catch-up)
     */
    private Pick start(ServerLevel level, SettlerJob job, JobBlockSite site, List<BlockPos> stations, long now, long dayTime, boolean catchUp) {
        BlockPos jobBlock = job.jobBlock();
        SettlementData data = SettlementData.get(level);
        Settlement settlement = site.settlement();
        Workplace workplace = shell.workplace(jobBlock);
        Map<UUID, Long> skips = workplace.skips();
        skips.values().removeIf(until -> until <= now || until - now > SKIP_TICKS);
        ProducingProfile profile = profile(job);
        List<Pattern> checked = Patterns.usable(level, site.block(), job.jobType());
        JobBuffer buffer = workplace.buffer();
        Map<Item, Integer> held = buffer.counts();
        int room = Math.max(0, JobBuffer.CAPACITY - buffer.count());
        int tries = 0;
        boolean more = false;
        // A request it can make that waits out its skip still counts: "Missing inputs", not "No request".
        boolean skipped = false;
        boolean fuelLacking = false;
        Optional<List<Item>> fuels = profile.fuel().map(defaults -> Fuels.of(site.block()));
        boolean byDeliveryman = site.block().byDeliveryman();
        // On "Deliveryman": every store, read once at the first try.
        Map<BlockPos, Map<Item, Integer>> stores = null;
        for (Request request : Requests.claimable(settlement)) {
            if (checked.stream().noneMatch(pattern -> pattern.output() == request.item())) {
                continue;
            }
            if (skips.containsKey(request.id())) {
                skipped = true;
                continue;
            }
            if (tries >= MAX_TRIES) {
                more = true;
                break;
            }
            tries++;
            Map<Item, Integer> collect = new HashMap<>(stock(level, site.collect(), dayTime, catchUp).counts());
            // Results waiting there for a Deliveryman (drop-off = collect inventory) are no inputs.
            Deliveries.waitingAt(settlement, site.collect()).forEach((item, count) -> collect.computeIfPresent(item,
                    (key, there) -> there > count ? there - count : null));
            Optional<BlockPos> clerk = byDeliveryman ? Optional.empty() : request.target().filter(target -> !target.equals(site.collect()));
            Map<Item, Integer> available = new HashMap<>(collect);
            clerk.ifPresent(target -> ItemCounts.addAll(available, stock(level, target, dayTime, catchUp).counts()));
            if (byDeliveryman) {
                if (stores == null) {
                    stores = stores(level, site, dayTime);
                }
                stores.values().forEach(counts -> ItemCounts.addAll(available, counts));
            }
            int amount = CraftPlan.most(level, profile, checked, request.item(), request.amount(), held, available, room);
            if (amount <= 0) {
                // What the whole open request misses ("2× stone pickaxe" → "Missing: 6× Cobblestone").
                CraftPlan failed = CraftPlan.plan(level, profile, checked, request.item(), request.amount(), held, available);
                Requests.setMissing(data, settlement, request.id(), failed.missing());
                skips.put(request.id(), now + SKIP_TICKS);
                fuelLacking = false;
                continue;
            }
            if (fuels.isPresent()) {
                amount = fuelCovered(level, job, fuels.get(), stations, checked, request.item(), amount, held, available);
                if (amount <= 0) {
                    // "Missing: Fuel": the request names the first listed fuel.
                    FuelNeed need = fuelNeed(level, job, fuels.get(), stations, CraftPlan.plan(level, profile, checked, request.item(), 1, held, available),
                            held, available);
                    Requests.setMissing(data, settlement, request.id(), fuels.get().isEmpty() ? List.of()
                            : List.of(new Request.Missing(fuels.get().getFirst(), Math.clamp(need.missing(), 1, 64))));
                    skips.put(request.id(), now + SKIP_TICKS);
                    fuelLacking = true;
                    continue;
                }
            }
            int claimed = Requests.claim(data, settlement, request.id(), settler.getUUID(), settler.getName().getString(), amount);
            if (claimed <= 0) {
                continue;
            }
            CraftPlan plan = CraftPlan.plan(level, profile, checked, request.item(), claimed, held, available);
            Map<Item, Integer> takes = new LinkedHashMap<>(plan.takes());
            fuels.ifPresent(list -> fuelNeed(level, job, list, stations, plan, held, available).takes()
                    .forEach((item, count) -> ItemCounts.add(takes, item, count)));
            workplace.setMissingFuel(false);
            List<BlockPos> sources = new ArrayList<>();
            Map<Item, Integer> rest = new HashMap<>(takes);
            if (!allot(rest, collect).isEmpty()) {
                sources.add(site.collect());
            }
            if (!rest.isEmpty() && clerk.isPresent()) {
                sources.add(clerk.get());
            }
            List<UUID> inputs = new ArrayList<>();
            if (stores != null) {
                for (Map.Entry<BlockPos, Map<Item, Integer>> store : stores.entrySet()) {
                    Map<Item, Integer> items = allot(rest, store.getValue());
                    if (!items.isEmpty()) {
                        inputs.add(Deliveries.postInput(data, settlement, store.getKey(), jobBlock, site.collect(), items, level.getGameTime()));
                    }
                }
                if (!inputs.isEmpty()) {
                    // Everything is taken at the collect inventory once the input deliveries arrived.
                    sources = List.of(site.collect());
                }
            }
            return new Pick(Optional.of(new Round(request.id(), request.item(), plan, takes, sources, inputs)), true, Optional.empty());
        }
        if (tries > 0) {
            workplace.setMissingFuel(fuelLacking);
        }
        JobStatus missing = workplace.missingFuel() ? JobStatus.MISSING_FUEL : JobStatus.MISSING_INPUTS;
        return new Pick(Optional.empty(), more, Optional.of(tries > 0 || skipped ? missing : JobStatus.NO_REQUEST));
    }

    /** The fuel a plan needs: what to fetch by item, and how many of the first listed fuel are missing. */
    private record FuelNeed(Map<Item, Integer> takes, int missing) {
    }

    /**
     * The largest part of an amount whose heat the listed fuel covers (buffer and stock, the stations' Remaining Heat
     * first).
     *
     * @return the amount to claim, 0 if not even one item is covered
     */
    private int fuelCovered(ServerLevel level, SettlerJob job, List<Item> fuels, List<BlockPos> stations, List<Pattern> checked, Item item,
            int amount, Map<Item, Integer> held, Map<Item, Integer> available) {
        ProducingProfile profile = profile(job);
        int low = 0;
        int high = amount;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            CraftPlan plan = CraftPlan.plan(level, profile, checked, item, middle, held, available);
            if (fuelNeed(level, job, fuels, stations, plan, held, available).missing() == 0) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * Simulates the heat of a plan the way its charges pay it ({@link #startCharges}): the executions of a step of the
     * main recipe type go round the stations in order, other steps run at the first; a station short of heat burns the
     * next fuel unit, the listed fuels in priority order (the buffer's units of a fuel before the stock's).
     */
    private FuelNeed fuelNeed(ServerLevel level, SettlerJob job, List<Item> fuels, List<BlockPos> stations, CraftPlan plan,
            Map<Item, Integer> held, Map<Item, Integer> available) {
        int[] heat = stations.stream().mapToInt(pos -> Furnaces.heat(level, pos)).toArray();
        BlockPos first = stations.getFirst();
        Map<Item, Integer> inBuffer = new HashMap<>();
        Map<Item, Integer> inStock = new HashMap<>();
        Map<Item, Integer> burn = new HashMap<>();
        for (Item fuel : fuels) {
            int burnTime = Furnaces.burnTime(level, first, new ItemStack(fuel));
            burn.put(fuel, burnTime);
            if (burnTime > 0) {
                inBuffer.put(fuel, held.getOrDefault(fuel, 0));
                inStock.put(fuel, Math.max(0, available.getOrDefault(fuel, 0) - plan.takes().getOrDefault(fuel, 0)));
            }
        }
        Map<Item, Integer> takes = new LinkedHashMap<>();
        int missing = 0;
        RecipeType<?> main = profile(job).recipeTypes().getFirst();
        for (CraftPlan.Step step : plan.steps()) {
            Optional<RecipeHolder<?>> recipe = recipe(level, step.pattern());
            int cost = recipe.map(Furnaces::heatOf).orElse(Furnaces.STANDARD_HEAT);
            int lanes = recipe.filter(holder -> holder.value().getType() != main).isPresent() ? 1 : heat.length;
            for (int execution = 0; execution < step.crafts(); execution++) {
                int lane = execution % lanes;
                while (heat[lane] < cost) {
                    Optional<Item> unit = fuels.stream().filter(fuel -> inBuffer.getOrDefault(fuel, 0) + inStock.getOrDefault(fuel, 0) > 0).findFirst();
                    if (unit.isEmpty()) {
                        int firstBurn = fuels.isEmpty() ? 0 : burn.get(fuels.getFirst());
                        if (firstBurn <= 0) {
                            return new FuelNeed(takes, Integer.MAX_VALUE);
                        }
                        missing++;
                        heat[lane] += firstBurn;
                        continue;
                    }
                    Item fuel = unit.get();
                    if (inBuffer.getOrDefault(fuel, 0) > 0) {
                        inBuffer.merge(fuel, -1, Integer::sum);
                    } else {
                        inStock.merge(fuel, -1, Integer::sum);
                        ItemCounts.add(takes, fuel, 1);
                    }
                    heat[lane] += burn.get(fuel);
                }
                heat[lane] -= cost;
            }
        }
        return new FuelNeed(takes, missing);
    }

    /** Takes what a source has off the needed items; what it gives, by item. */
    private static Map<Item, Integer> allot(Map<Item, Integer> needed, Map<Item, Integer> source) {
        Map<Item, Integer> given = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : List.copyOf(needed.entrySet())) {
            int used = Math.min(entry.getValue(), source.getOrDefault(entry.getKey(), 0));
            if (used > 0) {
                given.put(entry.getKey(), used);
                if (used == entry.getValue()) {
                    needed.remove(entry.getKey());
                } else {
                    needed.put(entry.getKey(), entry.getValue() - used);
                }
            }
        }
        return given;
    }

    /**
     * On "Deliveryman": what every Clerk of the settlement holds for the Deliverymen of the producer's Home Clerk –
     * the whole storage of the Clerks their Warehouse handles (staff), the counter under the access rule elsewhere –
     * the Home Clerk first, then by distance. A Clerk that is the collect inventory is counted there already.
     */
    private static Map<BlockPos, Map<Item, Integer>> stores(ServerLevel level, JobBlockSite site, long dayTime) {
        Settlement settlement = site.settlement();
        Optional<BlockPos> home = site.block().homeClerk();
        List<BlockPos> staff = home.flatMap(clerk -> Warehouses.warehouseOf(settlement, clerk))
                .map(warehouse -> Warehouses.handled(settlement, warehouse)).orElse(List.of());
        List<BlockPos> clerks = settlement.blocks().stream().filter(ClerkStorage::isClerk).map(SettlementBlock::pos)
                .filter(pos -> !pos.equals(site.collect()))
                .sorted(Comparator.<BlockPos>comparingInt(pos -> home.filter(pos::equals).isPresent() ? 0 : 1)
                        .thenComparingDouble(pos -> pos.distSqr(site.pos())))
                .toList();
        Map<BlockPos, Map<Item, Integer>> stores = new LinkedHashMap<>();
        for (BlockPos clerk : clerks) {
            Stock stock = staff.contains(clerk) ? ClerkStorage.stock(level, clerk, true) : ClerkStorage.stock(level, clerk, dayTime, true);
            stores.put(clerk, stock.counts());
        }
        return stores;
    }

    /**
     * A round waiting for its input deliveries: once none is left (they arrived) it fetches everything at the collect
     * inventory. An input delivery no Deliveryman has taken yet whose store no longer holds its inputs gives the round
     * up ("Missing", skipped a while, like any missing input); a taken one holds its reservation, a shortfall at its
     * pickup frees it again and the next check sees it.
     *
     * @param now the time for skipping (live: game time, catch-up: the simulated clock)
     * @return the job's status while it still waits ("Missing inputs", "Waiting for inputs"), empty once the inputs
     *         arrived (the round fetches now)
     */
    private Optional<JobStatus> waitForInputs(ServerLevel level, JobBlockSite site, Round round, long dayTime, long now) {
        Settlement settlement = site.settlement();
        List<DeliveryTask> open = round.inputs.stream().map(id -> Deliveries.get(settlement, id)).flatMap(Optional::stream).toList();
        if (open.isEmpty()) {
            round.phase = Round.Phase.FETCH;
            return Optional.empty();
        }
        long day = DayClock.day(level);
        List<Request.Missing> lacking = new ArrayList<>();
        for (DeliveryTask task : open) {
            if (task.carried() || task.takerOn(day).isPresent()) {
                continue;
            }
            Map<Item, Integer> there = (Deliveries.staff(settlement, task) ? ClerkStorage.stock(level, task.source(), true)
                    : ClerkStorage.stock(level, task.source(), dayTime, true)).counts();
            task.input().orElseThrow().items().forEach((item, count) -> {
                int missing = count - there.getOrDefault(item, 0);
                if (missing > 0) {
                    lacking.add(new Request.Missing(item, missing));
                }
            });
        }
        if (!lacking.isEmpty()) {
            fail(level, site.pos(), round, lacking, now);
            return Optional.of(JobStatus.MISSING_INPUTS);
        }
        return Optional.of(JobStatus.WAITING_FOR_INPUTS);
    }

    private Stock stock(ServerLevel level, BlockPos pos, long dayTime, boolean catchUp) {
        return ClerkStorage.stock(level, pos, dayTime, catchUp || !shell.counterUnreachable(level, pos));
    }

    @Override
    public void outranked(ServerLevel level, SettlerJob job, long day, long dayTime) {
        releaseToDropOff(level, job);
    }

    @Override
    public void dayEnded(ServerLevel level, SettlerJob job) {
        releaseToDropOff(level, job);
    }

    /** Releases the claim; the fetched inputs go to the own drop-off before the next round. */
    private void releaseToDropOff(ServerLevel level, SettlerJob job) {
        Workplace workplace = shell.workplace(job.jobBlock());
        if (release(level, job.jobBlock()) && deliverable(job, workplace.buffer())) {
            workplace.setDropOffPending(true);
        }
    }

    /** Crafting at the station: players cannot open it meanwhile. */
    @Override
    public boolean usesTarget(SettlerJob job) {
        Round round = shell.workplace(job.jobBlock()).round();
        return round != null && round.phase == Round.Phase.CRAFT;
    }

    /** A further parallel station with a charge running is in use too while the settler works. */
    @Override
    public boolean usesPlace(SettlerJob job, BlockPos pos) {
        Round round = shell.workplace(job.jobBlock()).round();
        return round != null && round.phase == Round.Phase.CRAFT && round.charges.containsKey(pos);
    }

    @Override
    public JobStep holdsDecision(ServerLevel level, SettlerJob job) {
        Round round = shell.workplace(job.jobBlock()).round();
        if (round != null && round.phase == Round.Phase.CRAFT && fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom())) {
            return new JobStep.Pause(JobStatus.PAUSING);
        }
        return JobStep.skip();
    }

    // ---------------------------------------------------------------- per-tick work

    @Override
    public JobStep tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        Workplace workplace = shell.workplace(job.jobBlock());
        Round round = workplace.round();
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (round == null || site.isEmpty()) {
            return JobStep.DONE;
        }
        long dayTime = DayClock.dayTime(level);
        return switch (round.phase) {
            case WAIT -> JobStep.DONE;
            case FETCH -> {
                boolean counter = visit != null && visit.counter();
                BlockPos source = round.sources.getFirst();
                if (counter && !shell.waitAtCounter(level, source)) {
                    // Waiting for the Clerk.
                    yield JobStep.STAY;
                }
                fetch(level, job, round, source, ClerkStorage.stock(level, source, dayTime, counter), true);
                visit = null;
                yield JobStep.DONE;
            }
            case CRAFT -> craft(level, job, site.get(), round, target);
            case DELIVER -> {
                if (!deliver(level, job, round, visit != null && visit.counter(), dayTime)) {
                    workplace.setDropOffPending(true);
                }
                visit = null;
                yield JobStep.DONE;
            }
        };
    }

    /** Takes the round's inputs from one source into the buffer; after the last source the craft starts. */
    private void fetch(ServerLevel level, SettlerJob job, Round round, BlockPos source, Stock stock, boolean effects) {
        JobBuffer buffer = shell.workplace(job.jobBlock()).buffer();
        boolean taken = false;
        for (Map.Entry<Item, Integer> entry : List.copyOf(round.toTake.entrySet())) {
            List<ItemStack> got = stock.take(entry.getKey(), entry.getValue());
            int count = got.stream().mapToInt(ItemStack::getCount).sum();
            got.forEach(buffer::add);
            taken |= count > 0;
            if (count >= entry.getValue()) {
                round.toTake.remove(entry.getKey());
            } else {
                round.toTake.put(entry.getKey(), entry.getValue() - count);
            }
        }
        if (taken && stock.counter()) {
            ClerkCounter.handOut(level, source, effects);
        }
        round.sources.remove(source);
        if (!round.sources.isEmpty()) {
            return;
        }
        if (round.toTake.isEmpty()) {
            round.phase = Round.Phase.CRAFT;
        } else {
            // The stock changed since the round started.
            List<Request.Missing> lacking = round.toTake.entrySet().stream()
                    .map(entry -> new Request.Missing(entry.getKey(), entry.getValue())).toList();
            fail(level, job.jobBlock(), round, lacking, level.getGameTime());
        }
    }

    private JobStep craft(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, BlockPos station) {
        if (handTimer > 0 && --handTimer == 0) {
            // Back to the hammer after showing the result just made.
            shell.hold(job.jobType().displayItem().map(ItemStack::new).orElse(ItemStack.EMPTY));
        }
        if (!isStation(level, job, station)) {
            // Broken: the next decision picks a new free station at once; the inputs stay in the buffer.
            return JobStep.DONE;
        }
        if (fatigue.onBreak()) {
            return JobStep.STAY;
        }
        settler.getLookControl().setLookAt(Vec3.atCenterOf(station));
        if (++swingTimer >= SWING_INTERVAL) {
            swingTimer = 0;
            settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            BlockState state = level.getBlockState(station);
            level.playSound(null, station, state.getSoundType().getHitSound(), SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        // The stations chosen at the last decision, while the settler stands at the first of them.
        List<BlockPos> stations = !round.chargeStations.isEmpty() && round.chargeStations.getFirst().equals(station) ? round.chargeStations
                : List.of(station);
        Worked worked = work(level, job, site, round, stations, 1.0, true);
        return switch (worked.outcome()) {
            case CRAFTED -> {
                round.phase = Round.Phase.DELIVER;
                yield JobStep.WORKED_AND_DONE;
            }
            case NO_FUEL -> {
                failFuel(level, job, site, round, level.getGameTime());
                yield JobStep.DONE;
            }
            case FAILED -> JobStep.DONE;
            case WORKING -> JobStep.WORK;
        };
    }

    /** How working the charges for a while ended. */
    private enum Outcome {
        /** The charges go on. */
        WORKING,
        /** The round's last execution is done: it delivers next. */
        CRAFTED,
        /** Inputs were gone: the round failed. */
        FAILED,
        /** No charge runs and a station lacks fuel for its next one. */
        NO_FUEL
    }

    /**
     * @param executed the executions done
     * @param outcome  how it ended
     */
    private record Worked(int executed, Outcome outcome) {
    }

    /** Progress below the work time by less than this counts as done (catch-up spans end exactly on it). */
    private static final double DONE_EPSILON = 1.0E-9;

    /**
     * Works the round's current step for some ticks at its stations (vault {@code Systems/Producing Jobs.md}): a
     * station without a charge starts the next execution (paying its heat first, see {@link #startCharges}), every
     * charge progresses by the speed formula, a finished one is executed in the buffer (inputs out, result in, EXP) and
     * its station goes on with the next. A charge whose station broke is lost: its inputs never left the buffer, the
     * Remaining Heat went with the furnace.
     *
     * @param stations the stations of the step (the one the settler stands at first)
     * @param ticks    the ticks worked
     * @param live     whether the world shows it (furnaces lit, the result in the hand); false in catch-up
     */
    private Worked work(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, List<BlockPos> stations, double ticks, boolean live) {
        for (BlockPos pos : List.copyOf(round.charges.keySet())) {
            if (!stations.contains(pos) || !isStation(level, job, pos)) {
                round.charges.remove(pos);
                if (live) {
                    Furnaces.light(level, pos, false);
                }
            }
        }
        boolean noFuel = startCharges(level, job, site, round, stations, live);
        if (round.charges.isEmpty()) {
            return new Worked(0, noFuel ? Outcome.NO_FUEL : Outcome.WORKING);
        }
        double add = ticks * speed(level, job) / 20.0;
        double need = workTime(level, job, round);
        int executed = 0;
        List<BlockPos> done = new ArrayList<>();
        for (Map.Entry<BlockPos, Double> charge : List.copyOf(round.charges.entrySet())) {
            double progress = charge.getValue() + add;
            if (progress < need - DONE_EPSILON) {
                round.charges.put(charge.getKey(), progress);
                continue;
            }
            round.charges.remove(charge.getKey());
            done.add(charge.getKey());
            boolean ok = craftOne(level, job, site, round, live);
            if (!ok || round.crafted()) {
                if (live) {
                    done.forEach(pos -> Furnaces.light(level, pos, false));
                }
                return new Worked(ok ? executed + 1 : executed, ok ? Outcome.CRAFTED : Outcome.FAILED);
            }
            executed++;
        }
        if (!done.isEmpty()) {
            startCharges(level, job, site, round, stations, live);
            if (live) {
                done.stream().filter(pos -> !round.charges.containsKey(pos)).forEach(pos -> Furnaces.light(level, pos, false));
            }
        }
        return new Worked(executed, Outcome.WORKING);
    }

    /**
     * Starts a charge at every station of the list without one while executions of the step are left. A Job burning
     * fuel pays each execution's heat from the station's Remaining Heat and the buffer's fuel ({@link Furnaces#pay}); a
     * station that cannot pay stays idle.
     *
     * @return whether a station lacked fuel
     */
    private boolean startCharges(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, List<BlockPos> stations, boolean live) {
        if (round.crafted()) {
            return false;
        }
        CraftPlan.Step step = round.steps.get(round.step);
        Optional<List<Item>> fuels = profile(job).fuel().map(defaults -> Fuels.of(site.block()));
        int cost = recipe(level, step.pattern()).map(Furnaces::heatOf).orElse(Furnaces.STANDARD_HEAT);
        JobBuffer buffer = shell.workplace(job.jobBlock()).buffer();
        boolean noFuel = false;
        for (BlockPos pos : stations) {
            if (round.crafted + round.charges.size() >= step.crafts()) {
                break;
            }
            if (round.charges.containsKey(pos) || !isStation(level, job, pos)) {
                continue;
            }
            if (fuels.isPresent() && !Furnaces.pay(level, pos, cost, fuels.get(), buffer)) {
                noFuel = true;
                continue;
            }
            round.charges.put(pos, 0.0);
            if (live) {
                Furnaces.light(level, pos, true);
            }
        }
        return noFuel;
    }

    /** A round whose stations lack fuel: it fails with "Missing: Fuel" (the first listed fuel on the request). */
    private void failFuel(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, long now) {
        List<Item> fuels = Fuels.of(site.block());
        fail(level, job.jobBlock(), round, fuels.isEmpty() ? List.of() : List.of(new Request.Missing(fuels.getFirst(), 1)), now);
        shell.workplace(job.jobBlock()).setMissingFuel(true);
    }

    /**
     * One execution of the round's current step in the buffer: inputs out, the profile's "execute recipe" result in
     * (by default the output and the remainders), EXP.
     *
     * @return whether it was executed (false: inputs gone, the round failed)
     */
    private boolean craftOne(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, boolean effects) {
        CraftPlan.Step step = round.steps.get(round.step);
        JobBuffer buffer = shell.workplace(job.jobBlock()).buffer();
        List<ItemStack> consumed = new ArrayList<>();
        for (Ingredient input : step.inputs()) {
            ItemStack one = buffer.takeOne(input);
            if (one.isEmpty()) {
                consumed.forEach(buffer::add);
                fail(level, job.jobBlock(), round, List.of(new Request.Missing(input.items().findFirst().map(holder -> holder.value())
                        .orElse(net.minecraft.world.item.Items.AIR), 1)), level.getGameTime());
                return false;
            }
            consumed.add(one);
        }
        Pattern pattern = step.pattern();
        ItemStack result = new ItemStack(pattern.output(), pattern.count());
        Optional<RecipeHolder<?>> recipe = recipe(level, pattern);
        if (recipe.isPresent()) {
            profile(job).execute(level, recipe.get(), result, List.copyOf(consumed)).forEach(buffer::add);
        } else {
            // The recipe is gone (datapack change): the pattern's own output, as recorded.
            buffer.add(result);
            PatternRecipes.remainders(consumed).forEach(buffer::add);
        }
        shell.gainExp(level, job, job.jobType().baseExp() * site.level(), site.skillCap());
        if (++round.crafted >= step.crafts()) {
            round.step++;
            round.crafted = 0;
        }
        if (effects) {
            shell.hold(new ItemStack(pattern.output()));
            handTimer = HAND_TICKS;
        }
        return true;
    }

    /**
     * Delivers the round's item to the posting Clerk (under its access rule), ends the claim and lets the Clerk check
     * its rules. On "Deliveryman" the item goes into the own drop-off instead, with a result delivery for the Clerk
     * (in delivery for its rules until a Deliveryman brought it).
     *
     * @return whether everything fit (otherwise the rest waits in the buffer for the own drop-off)
     */
    private boolean deliver(ServerLevel level, SettlerJob job, Round round, boolean atCounter, long dayTime) {
        Workplace workplace = shell.workplace(job.jobBlock());
        JobBuffer buffer = workplace.buffer();
        List<ItemStack> product = buffer.takeAll(stack -> stack.is(round.item));
        List<ItemStack> rest = new ArrayList<>();
        SettlementData data = SettlementData.get(level);
        // Read at delivery: a removed Clerk leaves the request without a target (own drop-off).
        Optional<BlockPos> target = target(data, round.request);
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        Optional<BlockPos> dropOff = target.isPresent() ? site.filter(found -> found.block().byDeliveryman()).map(JobBlockSite::dropOff)
                : Optional.empty();
        Stock stock = dropOff.map(pos -> ClerkStorage.stock(level, pos, dayTime, false))
                .orElseGet(() -> target.map(clerk -> ClerkStorage.stock(level, clerk, dayTime, atCounter)).orElse(Stock.EMPTY));
        for (ItemStack stack : product) {
            ItemStack left = stock.insert(stack);
            if (!left.isEmpty()) {
                rest.add(left);
            }
        }
        rest.forEach(buffer::add);
        workplace.setRound(null);
        Optional<Settlement> settlement = settlementOf(data, round.request);
        settlement.ifPresent(found -> Requests.finish(data, found, round.request, settler.getUUID()));
        if (dropOff.isPresent()) {
            // The whole result: what did not fit follows into the same drop-off with the buffer (in delivery as well).
            int count = product.stream().mapToInt(ItemStack::getCount).sum();
            site.ifPresent(found -> Deliveries.postResult(data, found.settlement(), dropOff.get(), found.pos(), round.item, count, target.get(),
                    level.getGameTime()));
        } else {
            target.ifPresent(clerk -> StockRules.check(level, clerk, dayTime));
        }
        return rest.isEmpty();
    }

    /** A round that cannot go on: the claim is released, the request shows what is missing and is skipped a while. */
    private void fail(ServerLevel level, BlockPos jobBlock, Round round, List<Request.Missing> missing, long now) {
        SettlementData data = SettlementData.get(level);
        release(level, jobBlock);
        settlementOf(data, round.request).ifPresent(settlement -> Requests.setMissing(data, settlement, round.request, missing));
        shell.workplace(jobBlock).skips().put(round.request, now + SKIP_TICKS);
    }

    /**
     * Releases the claim of the JobBlock's round, if any.
     *
     * @return whether a round was released
     */
    private boolean release(ServerLevel level, BlockPos jobBlock) {
        Workplace workplace = shell.workplace(jobBlock);
        Round round = workplace.round();
        if (round == null) {
            return false;
        }
        workplace.setRound(null);
        // Running charges end: their inputs are still in the buffer, the furnaces go out.
        round.charges.keySet().forEach(pos -> Furnaces.light(level, pos, false));
        round.charges.clear();
        SettlementData data = SettlementData.get(level);
        settlementOf(data, round.request).ifPresent(settlement -> Requests.release(data, settlement, round.request, settler.getUUID()));
        if (!round.inputs.isEmpty()) {
            // Input deliveries not carried yet go; carried ones still arrive (inputs for a later round).
            JobBlockSite.at(level, jobBlock).ifPresent(site -> Deliveries.dropInputs(data, site.settlement(), jobBlock));
        }
        return true;
    }

    private static Optional<Settlement> settlementOf(SettlementData data, UUID request) {
        return data.settlements().stream().filter(settlement -> Requests.get(settlement, request).isPresent()).findFirst();
    }

    /** The Clerk a request delivers to now, empty if it has none (or is gone). */
    private static Optional<BlockPos> target(SettlementData data, UUID request) {
        return settlementOf(data, request).flatMap(settlement -> Requests.get(settlement, request)).flatMap(Request::target);
    }

    private void releaseIfRestored(ServerLevel level) {
        if (releasePending) {
            releasePending = false;
            SettlementData data = SettlementData.get(level);
            Requests.releaseAll(data, settler.getUUID());
            // The input deliveries of the lost rounds go too (carried ones still arrive).
            shell.jobs().forEach(job -> JobBlockSite.at(level, job.jobBlock())
                    .ifPresent(site -> Deliveries.dropInputs(data, site.settlement(), job.jobBlock())));
        }
    }

    /** Operations per second: the shared speed formula × the job factor × the biome bonus of the station of the day. */
    private double speed(ServerLevel level, SettlerJob job) {
        return LumberjackOutput.speed(shell.effectiveLevel(job)) * job.jobType().jobFactor()
                * shell.workplace(job.jobBlock()).biomeBonus(level, job.jobType()).speed().value();
    }

    private static ProducingProfile profile(SettlerJob job) {
        return Patterns.profile(job.jobType());
    }

    private static Optional<RecipeHolder<?>> recipe(ServerLevel level, Pattern pattern) {
        return pattern.recipe().flatMap(id -> PatternRecipes.holder(level, id));
    }

    /** Operations one execution of the round's current step takes (the profile's work time; 1 for a recipe that is gone). */
    private static double workTime(ServerLevel level, SettlerJob job, Round round) {
        return recipe(level, round.steps.get(round.step).pattern()).map(profile(job)::workTime).orElse(1.0);
    }

    // ---------------------------------------------------------------- station

    /**
     * The station of the day: a scroll work point (a station of any of the profile's recipe types), else the nearest
     * station of the main recipe type within {@value #STATION_RADIUS} blocks of the JobBlock inside the border. A furnace
     * holding items or burning on its own is no station ({@link Furnaces#isIdle}).
     */
    private @Nullable BlockPos station(ServerLevel level, SettlerJob job, JobBlockSite site, long day) {
        TagKey<Block> main = mainStation(job);
        return shell.workplace(job.jobBlock()).workPlaceOfDay(level, settler, pos -> isStation(level, job, pos) && Furnaces.isIdle(level, pos),
                free -> nearestStation(level, site, main, pos -> free.test(pos) && Furnaces.isIdle(level, pos)), site, day);
    }

    /** The station tag of the main recipe type. */
    private static TagKey<Block> mainStation(SettlerJob job) {
        ProducingProfile profile = profile(job);
        return profile.station(profile.recipeTypes().getFirst());
    }

    /**
     * How many stations the job works in parallel at its JobBlock's level: {@code parallel_stations} of the job file,
     * else the profile's value (1 by default), at most {@value SettlementBlock#MAX_WORK_POINTS}.
     */
    private static int parallel(SettlerJob job, JobBlockSite site) {
        int level = site.level();
        int count = JobBalancing.of(job.jobType()).parallelStations().map(list -> list.get(Math.min(level, list.size()) - 1))
                .orElseGet(() -> profile(job).parallelStations(level));
        return Math.clamp(count, 1, SettlementBlock.MAX_WORK_POINTS);
    }

    /**
     * The stations of the main recipe type the job works in parallel today: the station of the day first, then up to
     * {@link #parallel} − 1 further ones, each occupied for the day. They come from the JobBlock's scroll work points
     * if it has valid ones, else from the stations nearest to the JobBlock; only free (not occupied by another settler)
     * and idle ones count. While short of stations it searches again at most every {@value Workplace#BLOCKED_RETRY}
     * ticks.
     *
     * @param ofDay the station of the day
     */
    private List<BlockPos> stations(ServerLevel level, SettlerJob job, JobBlockSite site, long day, BlockPos ofDay) {
        Workplace workplace = shell.workplace(job.jobBlock());
        Settlement settlement = site.settlement();
        List<BlockPos> further = workplace.parallelStations(day);
        int wanted = parallel(job, site) - 1;
        TagKey<Block> main = mainStation(job);
        Predicate<BlockPos> usable = pos -> !pos.equals(ofDay) && level.isLoaded(pos) && level.getBlockState(pos).is(main)
                && OccupiedPlaces.isFreeFor(settlement, pos, settler.getUUID(), day) && Furnaces.isIdle(level, pos);
        for (BlockPos pos : List.copyOf(further)) {
            if (further.size() > wanted || !usable.test(pos)) {
                further.remove(pos);
                OccupiedPlaces.release(settlement, pos, settler.getUUID());
            }
        }
        long now = level.getGameTime();
        if (further.size() < wanted && workplace.parallelSearchDue(now)) {
            List<BlockPos> points = site.block().workPoints().stream().filter(pos -> isStation(level, job, pos)).toList();
            List<BlockPos> candidates = points.isEmpty() ? stationsAround(level, site, main) : points;
            for (BlockPos pos : candidates) {
                if (further.size() >= wanted) {
                    break;
                }
                if (!further.contains(pos) && usable.test(pos)) {
                    further.add(pos);
                }
            }
            workplace.setParallelSearchAt(further.size() < wanted ? now + Workplace.BLOCKED_RETRY : 0L);
        }
        List<BlockPos> stations = new ArrayList<>();
        stations.add(ofDay);
        for (BlockPos pos : further) {
            workplace.occupy(settlement, settler, pos, day);
            stations.add(pos);
        }
        return stations;
    }

    /**
     * The stations the round's current step is worked at: every parallel station for a step of the main recipe type
     * worked at the station of the day, else only the step's station.
     */
    private List<BlockPos> chargeStations(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, BlockPos station, long day) {
        BlockPos ofDay = station(level, job, site, day);
        return station.equals(ofDay) && mainStep(level, job, round) ? stations(level, job, site, day, ofDay) : List.of(station);
    }

    /** Whether the round's current step is a recipe of the main type (or its recipe is gone). */
    private static boolean mainStep(ServerLevel level, SettlerJob job, Round round) {
        return round.crafted() || recipe(level, round.steps.get(round.step).pattern())
                .map(holder -> holder.value().getType() == profile(job).recipeTypes().getFirst()).orElse(true);
    }

    /** Whether a position is a station of any of the job's recipe types. */
    private static boolean isStation(ServerLevel level, SettlerJob job, BlockPos pos) {
        ProducingProfile profile = profile(job);
        return level.isLoaded(pos) && profile.recipeTypes().stream().map(profile::station).anyMatch(level.getBlockState(pos)::is);
    }

    /** The status without a station of the day: every station occupied by another settler, or none at all. */
    private static JobStatus noStation(Workplace workplace) {
        return workplace.noFreeWorkPlace() ? JobStatus.NO_FREE_WORK_PLACE : JobStatus.NO_WORK_PLACE;
    }

    /**
     * The station of the round's current step: the station of the day if it is one of the step's recipe type, else the
     * nearest station of that type (looked up once per round and type).
     */
    private @Nullable BlockPos stepStation(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, long day) {
        BlockPos ofDay = station(level, job, site, day);
        Optional<RecipeType<?>> type = round.crafted() ? Optional.empty()
                : recipe(level, round.steps.get(round.step).pattern()).map(holder -> holder.value().getType());
        if (ofDay == null || type.isEmpty() || !profile(job).recipeTypes().contains(type.get())) {
            return ofDay;
        }
        TagKey<Block> tag = profile(job).station(type.get());
        if (level.getBlockState(ofDay).is(tag)) {
            return ofDay;
        }
        // A station of another type is occupied for the day too; one occupied by another settler meanwhile is not used.
        Settlement settlement = site.settlement();
        Predicate<BlockPos> free = pos -> OccupiedPlaces.isFreeFor(settlement, pos, settler.getUUID(), day);
        BlockPos found = round.stations.computeIfAbsent(tag, key -> Optional.ofNullable(nearestStation(level, site, key, free))).orElse(null);
        if (found == null || !level.isLoaded(found) || !level.getBlockState(found).is(tag) || !free.test(found)) {
            return null;
        }
        shell.workplace(job.jobBlock()).occupy(settlement, settler, found, day);
        return found;
    }

    private static @Nullable BlockPos nearestStation(ServerLevel level, JobBlockSite site, TagKey<Block> tag, Predicate<BlockPos> free) {
        BlockPos center = site.pos();
        BlockPos best = null;
        double bestDistance = STATION_RADIUS * STATION_RADIUS + 1.0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-STATION_RADIUS, -STATION_HEIGHT, -STATION_RADIUS),
                center.offset(STATION_RADIUS, STATION_HEIGHT, STATION_RADIUS))) {
            double distance = pos.distSqr(center);
            if (distance < bestDistance && level.isLoaded(pos) && level.getBlockState(pos).is(tag)
                    && JobAssignments.insideBorder(site.settlement(), pos) && free.test(pos)) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Every station of a tag within {@value #STATION_RADIUS} blocks of the JobBlock inside the border, nearest first. */
    private static List<BlockPos> stationsAround(ServerLevel level, JobBlockSite site, TagKey<Block> tag) {
        BlockPos center = site.pos();
        List<BlockPos> found = new ArrayList<>();
        double radius = STATION_RADIUS * STATION_RADIUS + 1.0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-STATION_RADIUS, -STATION_HEIGHT, -STATION_RADIUS),
                center.offset(STATION_RADIUS, STATION_HEIGHT, STATION_RADIUS))) {
            if (pos.distSqr(center) < radius && level.isLoaded(pos) && level.getBlockState(pos).is(tag)
                    && JobAssignments.insideBorder(site.settlement(), pos)) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found;
    }

    // ---------------------------------------------------------------- catch-up

    @Override
    public boolean catchUpReady(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        return level.isLoaded(site.pos()) && level.isLoaded(site.collect()) && level.isLoaded(site.dropOff());
    }

    /**
     * The same rounds without walking: requests are picked as the simulated time goes on, inputs are taken at once,
     * operations take their time and count against the catch-up budget; the result goes straight into the Clerk, the
     * rest into the own drop-off.
     */
    @Override
    public SimulatedWork simulateWork(ServerLevel level, SettlerJob job, long day, double workTicks) {
        BlockPos jobBlock = job.jobBlock();
        Optional<JobBlockSite> found = JobBlockSite.at(level, jobBlock);
        if (found.isEmpty()) {
            shell.endCatchUpJob(jobBlock);
            return new SimulatedWork(0, workTicks);
        }
        JobBlockSite site = found.get();
        releaseIfRestored(level);
        Patterns.uncheckFiltered(level, jobBlock, job.jobType());
        Patterns.enforceLimit(level, jobBlock, job.jobType(), settler.getUUID(), profile(job).checkLimit(shell.effectiveLevel(job)));
        if (station(level, job, site, day) == null) {
            shell.setStatus(jobBlock, noStation(shell.workplace(jobBlock)));
            return new SimulatedWork(0, workTicks);
        }
        // The time of day follows the simulated clock: a round crossing 18:30 no longer reaches the linked storage.
        double clock = day * (double) DayClock.DAY_TICKS + shell.catchUpDayTime();
        Workplace workplace = shell.workplace(jobBlock);
        double ticks = workTicks;
        int operations = 0;
        while (ticks > 0.0) {
            if (workplace.round() == null) {
                if (workplace.dropOffPending() || (workplace.buffer().shouldDeliver() && deliverable(job, workplace.buffer()))) {
                    workplace.setDropOffPending(false);
                    if (!deliverToDropOff(level, site)) {
                        shell.endCatchUpJob(jobBlock);
                        return new SimulatedWork(operations, ticks);
                    }
                }
                BlockPos ofDay = station(level, job, site, day);
                if (ofDay == null) {
                    shell.setStatus(jobBlock, noStation(workplace));
                    return new SimulatedWork(operations, ticks);
                }
                Pick pick = start(level, job, site, stations(level, job, site, day, ofDay), (long) clock, DayClock.dayTime((long) clock), true);
                pick.status().ifPresent(status -> shell.setStatus(jobBlock, status));
                if (pick.round().isEmpty()) {
                    if (!pick.more()) {
                        // Nothing doable: the next job takes the rest of the time.
                        return new SimulatedWork(operations, ticks);
                    }
                    // The next decision tries the next requests.
                    ticks -= SettlerWork.DECISION_INTERVAL;
                    clock += SettlerWork.DECISION_INTERVAL;
                    continue;
                }
                Round round = pick.round().get();
                workplace.setRound(round);
                if (round.phase != Round.Phase.WAIT) {
                    for (BlockPos source : List.copyOf(round.sources)) {
                        fetch(level, job, round, source, ClerkStorage.stock(level, source, DayClock.dayTime((long) clock), true), false);
                    }
                }
                continue;
            }
            Round round = workplace.round();
            if (round.phase == Round.Phase.WAIT) {
                long now = (long) clock;
                Optional<JobStatus> waiting = waitForInputs(level, site, round, DayClock.dayTime(now), now);
                waiting.ifPresent(status -> shell.setStatus(jobBlock, status));
                if (waiting.isEmpty()) {
                    fetch(level, job, round, site.collect(), ClerkStorage.stock(level, site.collect(), DayClock.dayTime(now), true), false);
                } else if (workplace.round() != null) {
                    // The Deliverymen bring the inputs later: the next job takes the rest of the time.
                    return new SimulatedWork(operations, ticks);
                }
                continue;
            }
            double perTick = speed(level, job) / 20.0;
            while (round.phase == Round.Phase.CRAFT && ticks > 0.0 && workplace.round() == round) {
                BlockPos ofDay = station(level, job, site, day);
                if (ofDay == null || perTick <= 0.0) {
                    release(level, jobBlock);
                    shell.setStatus(jobBlock, noStation(workplace));
                    return new SimulatedWork(operations, ticks);
                }
                List<BlockPos> stations = mainStep(level, job, round) ? stations(level, job, site, day, ofDay) : List.of(ofDay);
                // Up to the next finished charge (the one furthest on); none runs yet: they start first.
                double furthest = round.charges.values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
                double span = Double.isNaN(furthest) ? 0.0 : Math.min(ticks, Math.max(0.0, (workTime(level, job, round) - furthest) / perTick));
                Worked worked = work(level, job, site, round, stations, span, false);
                ticks -= span;
                clock += span;
                operations += worked.executed();
                if (worked.outcome() == Outcome.CRAFTED) {
                    round.phase = Round.Phase.DELIVER;
                } else if (worked.outcome() == Outcome.NO_FUEL) {
                    failFuel(level, job, site, round, (long) clock);
                } else if (worked.outcome() == Outcome.WORKING && round.charges.isEmpty()) {
                    // No station of the step can work (all broken): the next job takes the rest of the time.
                    release(level, jobBlock);
                    shell.setStatus(jobBlock, JobStatus.NO_WORK_PLACE);
                    return new SimulatedWork(operations, ticks);
                }
            }
            if (workplace.round() == round && round.phase == Round.Phase.DELIVER
                    && !deliver(level, job, round, true, DayClock.dayTime((long) clock))
                    && !deliverToDropOff(level, site)) {
                shell.endCatchUpJob(jobBlock);
                return new SimulatedWork(operations, ticks);
            }
        }
        return new SimulatedWork(operations, 0.0);
    }

    /**
     * Catch-up: the buffer goes straight into the own drop-off.
     *
     * @return whether everything fit
     */
    private boolean deliverToDropOff(ServerLevel level, JobBlockSite site) {
        JobBuffer buffer = shell.workplace(site.pos()).buffer();
        List<Item> fuels = Fuels.of(site.block());
        shell.deliverDirect(level, site, buffer.takeAll(stack -> !fuels.contains(stack.getItem())));
        return buffer.stacks().stream().allMatch(stack -> fuels.contains(stack.getItem()));
    }

    /** Fuel rests stay in the buffer for the next charges: they never go to storage (vault {@code Jobs/Smelter.md}). */
    @Override
    public boolean keeps(SettlerJob job, ItemStack stack) {
        return settler.level() instanceof ServerLevel level && Fuels.of(level, job.jobBlock()).contains(stack.getItem());
    }

    /** Whether the buffer holds anything that goes to the drop-off (not only fuel rests). */
    private boolean deliverable(SettlerJob job, JobBuffer buffer) {
        return buffer.stacks().stream().anyMatch(stack -> !keeps(job, stack));
    }

    // ---------------------------------------------------------------- state

    /**
     * Leaving the JobBlock releases the claim; the fetched inputs go straight into the own drop-off while it is loaded
     * (the Settler Shell drops what is left at the settler).
     */
    @Override
    public void forget(BlockPos jobBlock) {
        if (settler.level() instanceof ServerLevel level && release(level, jobBlock)) {
            JobBlockSite.at(level, jobBlock).filter(site -> level.isLoaded(site.dropOff())).ifPresent(site -> {
                Stock dropOff = ClerkStorage.stock(level, site.dropOff(), DayClock.dayTime(level), false);
                shell.workplace(jobBlock).buffer().deliver(stack -> stack.getCount() - dropOff.insert(stack).getCount());
            });
        }
        visit = null;
    }

    @Override
    public void restore(SettlerWork.BuffersSnapshot snapshot) {
        // The Workplaces dropped their rounds; the claims are released at the next decision.
        releasePending = true;
    }

    @Override
    public void startCatchUp() {
        shell.jobs().forEach(job -> shell.workplace(job.jobBlock()).skips().clear());
    }
}
