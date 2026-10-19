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
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
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
import net.minecraft.world.entity.EquipmentSlot;
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

    /** Result of picking a request. */
    private record Pick(Optional<Round> round, boolean more) {
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
    public boolean plan(ServerLevel level, SettlerJob job, JobBlockSite site, long day, long dayTime) {
        BlockPos jobBlock = job.jobBlock();
        releaseIfRestored(level);
        Patterns.uncheckFiltered(level, jobBlock, job.jobType());
        Patterns.enforceLimit(level, jobBlock, job.jobType(), profile(job).checkLimit(shell.effectiveLevel(job)));
        Workplace workplace = shell.workplace(jobBlock);
        if (workplace.round() == null) {
            if (workplace.dropOffPending() || workplace.buffer().shouldDeliver()) {
                workplace.setDropOffPending(false);
                return shell.startDelivery(level, job);
            }
            if (station(level, job, site, day) == null) {
                shell.block(level, jobBlock, JobStatus.NO_WORK_PLACE);
                return false;
            }
            Pick pick = start(level, job, site, level.getGameTime(), dayTime, false);
            if (pick.round().isEmpty()) {
                return false;
            }
            workplace.setRound(pick.round().get());
        }
        Round round = Objects.requireNonNull(workplace.round());
        if (round.phase == Round.Phase.WAIT && !inputsArrived(level, site, round, dayTime, level.getGameTime())) {
            // Waiting for input deliveries: the next priority works meanwhile.
            return false;
        }
        shell.setActiveJob(job);
        switch (round.phase) {
            case WAIT -> {
                // Left above: the inputs arrived and the round fetches them.
            }
            case FETCH -> {
                BlockPos source = round.sources.getFirst();
                shell.setStatus(jobBlock, JobStatus.FETCHING_INPUTS);
                walk(level, shell.reachableVisit(level, source, ClerkStorage.visit(level, source, dayTime)));
            }
            case CRAFT -> {
                BlockPos station = stepStation(level, job, site, round, day);
                if (station == null) {
                    release(level, jobBlock);
                    shell.block(level, jobBlock, JobStatus.NO_WORK_PLACE);
                    return false;
                }
                shell.setStatus(jobBlock, fatigue.onBreak() ? JobStatus.PAUSING : JobStatus.CRAFTING);
                walk(level, new ClerkStorage.Visit(station, SettlerWork.REACH, false));
            }
            case DELIVER -> {
                BlockPos target = site.block().byDeliveryman() ? site.dropOff() : target(SettlementData.get(level), round.request).orElse(site.dropOff());
                shell.setStatus(jobBlock, JobStatus.DELIVERING);
                walk(level, shell.reachableVisit(level, target, ClerkStorage.visit(level, target, dayTime)));
            }
        }
        return true;
    }

    private void walk(ServerLevel level, ClerkStorage.Visit next) {
        if (!shell.walkingTo(next.target())) {
            visit = next;
            shell.moveToJob(level, next.target());
        }
    }

    /**
     * Picks the highest-priority request with a checked pattern that is not skipped, up to {@value #MAX_TRIES} tries,
     * and claims what can be made of it in one round.
     *
     * @param now     the time for skipping (live: game time, catch-up: the simulated clock)
     * @param catchUp whether the producer reaches every Clerk's counter (catch-up)
     */
    private Pick start(ServerLevel level, SettlerJob job, JobBlockSite site, long now, long dayTime, boolean catchUp) {
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
                continue;
            }
            int claimed = Requests.claim(data, settlement, request.id(), settler.getUUID(), settler.getName().getString(), amount);
            if (claimed <= 0) {
                continue;
            }
            CraftPlan plan = CraftPlan.plan(level, profile, checked, request.item(), claimed, held, available);
            List<BlockPos> sources = new ArrayList<>();
            Map<Item, Integer> rest = new HashMap<>(plan.takes());
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
            return new Pick(Optional.of(new Round(request.id(), request.item(), plan, sources, inputs)), true);
        }
        shell.setStatus(jobBlock, tries > 0 || skipped ? JobStatus.MISSING_INPUTS : JobStatus.NO_REQUEST);
        return new Pick(Optional.empty(), more);
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
     * @return whether the inputs arrived (the round fetches now)
     */
    private boolean inputsArrived(ServerLevel level, JobBlockSite site, Round round, long dayTime, long now) {
        Settlement settlement = site.settlement();
        List<DeliveryTask> open = round.inputs.stream().map(id -> Deliveries.get(settlement, id)).flatMap(Optional::stream).toList();
        if (open.isEmpty()) {
            round.phase = Round.Phase.FETCH;
            return true;
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
            shell.setStatus(site.pos(), JobStatus.MISSING_INPUTS);
            return false;
        }
        shell.setStatus(site.pos(), JobStatus.WAITING_FOR_INPUTS);
        return false;
    }

    private Stock stock(ServerLevel level, BlockPos pos, long dayTime, boolean catchUp) {
        return ClerkStorage.stock(level, pos, dayTime, catchUp || !shell.counterUnreachable(level, pos));
    }

    @Override
    public void outranked(ServerLevel level, SettlerJob job, long day, long dayTime) {
        releaseToDropOff(level, job.jobBlock());
    }

    @Override
    public void dayEnded(ServerLevel level, SettlerJob job) {
        releaseToDropOff(level, job.jobBlock());
    }

    /** Releases the claim; the fetched inputs go to the own drop-off before the next round. */
    private void releaseToDropOff(ServerLevel level, BlockPos jobBlock) {
        Workplace workplace = shell.workplace(jobBlock);
        if (release(level, jobBlock) && !workplace.buffer().isEmpty()) {
            workplace.setDropOffPending(true);
        }
    }

    @Override
    public boolean holdsDecision(ServerLevel level, SettlerJob job) {
        Round round = shell.workplace(job.jobBlock()).round();
        if (round != null && round.phase == Round.Phase.CRAFT && fatigue.advance(SettlerWork.DECISION_INTERVAL, level.getRandom())) {
            shell.setStatus(job.jobBlock(), JobStatus.PAUSING);
            settler.getNavigation().stop();
            return true;
        }
        return false;
    }

    @Override
    public double reach(SettlerJob job) {
        return visit == null ? SettlerWork.REACH : visit.reach();
    }

    // ---------------------------------------------------------------- per-tick work

    @Override
    public void tickAtTarget(ServerLevel level, SettlerJob job, BlockPos target) {
        Workplace workplace = shell.workplace(job.jobBlock());
        Round round = workplace.round();
        Optional<JobBlockSite> site = JobBlockSite.at(level, job.jobBlock());
        if (round == null || site.isEmpty()) {
            shell.stepDone(level);
            return;
        }
        long dayTime = DayClock.dayTime(level);
        switch (round.phase) {
            case WAIT -> shell.stepDone(level);
            case FETCH -> {
                boolean counter = visit != null && visit.counter();
                BlockPos source = round.sources.getFirst();
                if (counter && !shell.waitAtCounter(level, source)) {
                    // Waiting for the Clerk.
                    return;
                }
                fetch(level, job, round, source, ClerkStorage.stock(level, source, dayTime, counter), true);
                visit = null;
                shell.stepDone(level);
            }
            case CRAFT -> craft(level, job, site.get(), round, target);
            case DELIVER -> {
                if (!deliver(level, job, round, visit != null && visit.counter(), dayTime)) {
                    workplace.setDropOffPending(true);
                }
                visit = null;
                shell.stepDone(level);
            }
        }
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

    private void craft(ServerLevel level, SettlerJob job, JobBlockSite site, Round round, BlockPos station) {
        if (handTimer > 0 && --handTimer == 0) {
            // Back to the hammer after showing the result just made.
            settler.setItemSlot(EquipmentSlot.MAINHAND, job.jobType().displayItem().map(ItemStack::new).orElse(ItemStack.EMPTY));
        }
        if (fatigue.onBreak()) {
            return;
        }
        shell.countWorkTick(job);
        settler.getNavigation().stop();
        settler.getLookControl().setLookAt(Vec3.atCenterOf(station));
        if (++swingTimer >= SWING_INTERVAL) {
            swingTimer = 0;
            settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            BlockState state = level.getBlockState(station);
            level.playSound(null, station, state.getSoundType().getHitSound(), SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        Workplace workplace = shell.workplace(job.jobBlock());
        double progress = workplace.production() + speed(job) / 20.0;
        if (progress < workTime(level, job, round)) {
            workplace.setProduction(progress);
            return;
        }
        workplace.setProduction(0.0);
        if (!craftOne(level, job, site, round, true)) {
            return;
        }
        if (round.crafted()) {
            round.phase = Round.Phase.DELIVER;
            shell.stepDone(level);
        }
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
            settler.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(pattern.output()));
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
            settler.getJobs().forEach(job -> JobBlockSite.at(level, job.jobBlock())
                    .ifPresent(site -> Deliveries.dropInputs(data, site.settlement(), job.jobBlock())));
        }
    }

    /** Operations per second: the shared speed formula × the job factor. */
    private double speed(SettlerJob job) {
        return LumberjackOutput.speed(shell.effectiveLevel(job)) * job.jobType().jobFactor();
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
     * station of the main recipe type within {@value #STATION_RADIUS} blocks of the JobBlock inside the border.
     */
    private @Nullable BlockPos station(ServerLevel level, SettlerJob job, JobBlockSite site, long day) {
        ProducingProfile profile = profile(job);
        List<TagKey<Block>> tags = profile.recipeTypes().stream().<TagKey<Block>>map(profile::station).distinct().toList();
        TagKey<Block> main = tags.getFirst();
        return shell.workplace(job.jobBlock()).workPlaceOfDay(level,
                pos -> level.isLoaded(pos) && tags.stream().anyMatch(level.getBlockState(pos)::is), () -> nearestStation(level, site, main), site, day);
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
        BlockPos found = round.stations.computeIfAbsent(tag, key -> Optional.ofNullable(nearestStation(level, site, key))).orElse(null);
        return found != null && level.isLoaded(found) && level.getBlockState(found).is(tag) ? found : null;
    }

    private static @Nullable BlockPos nearestStation(ServerLevel level, JobBlockSite site, TagKey<Block> tag) {
        BlockPos center = site.pos();
        BlockPos best = null;
        double bestDistance = STATION_RADIUS * STATION_RADIUS + 1.0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-STATION_RADIUS, -STATION_HEIGHT, -STATION_RADIUS),
                center.offset(STATION_RADIUS, STATION_HEIGHT, STATION_RADIUS))) {
            double distance = pos.distSqr(center);
            if (distance < bestDistance && level.isLoaded(pos) && level.getBlockState(pos).is(tag)
                    && JobAssignments.insideBorder(site.settlement(), pos)) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return best;
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
        Patterns.enforceLimit(level, jobBlock, job.jobType(), profile(job).checkLimit(shell.effectiveLevel(job)));
        if (station(level, job, site, day) == null) {
            shell.setStatus(jobBlock, JobStatus.NO_WORK_PLACE);
            return new SimulatedWork(0, workTicks);
        }
        // The time of day follows the simulated clock: a round crossing 18:30 no longer reaches the linked storage.
        double clock = day * (double) DayClock.DAY_TICKS + shell.catchUpDayTime();
        Workplace workplace = shell.workplace(jobBlock);
        double ticks = workTicks;
        int operations = 0;
        while (ticks > 0.0) {
            if (workplace.round() == null) {
                if (workplace.dropOffPending() || workplace.buffer().shouldDeliver()) {
                    workplace.setDropOffPending(false);
                    if (!deliverToDropOff(level, site)) {
                        shell.endCatchUpJob(jobBlock);
                        return new SimulatedWork(operations, ticks);
                    }
                }
                Pick pick = start(level, job, site, (long) clock, DayClock.dayTime((long) clock), true);
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
                if (inputsArrived(level, site, round, DayClock.dayTime(now), now)) {
                    fetch(level, job, round, site.collect(), ClerkStorage.stock(level, site.collect(), DayClock.dayTime(now), true), false);
                } else if (workplace.round() != null) {
                    // The Deliverymen bring the inputs later: the next job takes the rest of the time.
                    return new SimulatedWork(operations, ticks);
                }
                continue;
            }
            double perTick = speed(job) / 20.0;
            while (round.phase == Round.Phase.CRAFT && ticks > 0.0 && workplace.round() == round) {
                double need = (workTime(level, job, round) - workplace.production()) / perTick;
                if (need > ticks) {
                    workplace.setProduction(workplace.production() + ticks * perTick);
                    clock += ticks;
                    ticks = 0.0;
                    break;
                }
                ticks -= need;
                clock += need;
                workplace.setProduction(0.0);
                if (!craftOne(level, job, site, round, false)) {
                    break;
                }
                operations++;
                if (round.crafted()) {
                    round.phase = Round.Phase.DELIVER;
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
        shell.deliverDirect(level, site, buffer.takeAll(stack -> true));
        return buffer.isEmpty();
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
        settler.getJobs().forEach(job -> shell.workplace(job.jobBlock()).skips().clear());
    }
}
