package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Reservations;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The Clerk's store (vault {@code Jobs/Clerk.md}): the containers linked with the Scroll of Assignment (the Clerk JobBlock has no
 * slots of its own), and the access rule. Taking from it needs a working Clerk at its counter (a settler assigned, in its
 * shift from its waking time to 18:00, a Tavern Host to 20:00); the Deliverymen of its Warehouse reach all of it as staff, and settlers put items in
 * at any time ({@link #dropOff}). Hoppers and pipes on the block see no slot.
 * <p>
 * Every Pantry is a food point: settlers take food from the nearest Pantry that has food
 * ({@link #nearestFood}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkStorage {

    /** End of the Clerk's shift in day ticks (18:00; day time 0 = 06:00). */
    public static final long SHIFT_END = 12000L;
    /** End of the Tavern Host's shift in day ticks (20:00): the Tavern evening runs until bedtime. */
    public static final long TAVERN_SHIFT_END = 14000L;
    /** Customers are served within this distance of the Clerk's counter. */
    public static final double COUNTER_REACH = 3.0;
    /** Reach when walking to a Clerk JobBlock without its counter, as for any container. */
    private static final double BLOCK_REACH = 2.5;
    /** Service time at skill level 1 (2 s). */
    private static final double SERVICE_TICKS = 40.0;

    private ClerkStorage() {
    }

    /**
     * Where a visitor walks to reach a Clerk JobBlock or a container.
     *
     * @param target  the position to walk to
     * @param reach   how close the visitor has to come
     * @param counter whether the visitor is served at the counter of a working Clerk (the whole storage)
     */
    public record Visit(BlockPos target, double reach, boolean counter) {
    }

    /**
     * A Clerk JobBlock with food for a settler.
     *
     * @param clerk the Clerk JobBlock
     * @param visit where to walk to
     */
    public record FoodPoint(BlockPos clerk, Visit visit) {
    }

    /**
     * @param block a JobBlock
     * @return whether it is a Clerk JobBlock
     */
    public static boolean isClerk(SettlementBlock block) {
        return block.jobType() == ModJobs.CLERK.get();
    }

    /**
     * @param block a JobBlock
     * @return whether it is a Pantry (the JobBlock of the Tavern Host)
     */
    public static boolean isPantry(SettlementBlock block) {
        return block.jobType() == ModJobs.TAVERN_HOST.get();
    }

    /**
     * @param block a JobBlock
     * @return whether a settler serves a counter at it: a Clerk JobBlock or a Pantry
     */
    private static boolean hasCounter(SettlementBlock block) {
        return shiftEnd(block.jobType()).isPresent();
    }

    /**
     * @param clerk a Clerk JobBlock
     * @return its name for players: its Clerk settler, else "Clerk at x, y, z"
     */
    public static Component name(SettlementBlock clerk) {
        return clerk.assignee(ModJobs.CLERK.get()).<Component>map(assignee -> Component.literal(assignee.name()))
                .orElseGet(() -> Component.translatable("civmod.scroll.point.clerk_at", clerk.pos().toShortString()));
    }

    /**
     * The one place that says which jobs serve a counter and when their shift ends.
     *
     * @param job a job
     * @return the day time its shift ends: 20:00 for the Tavern Host, 18:00 for the Clerk; empty for a job without a counter
     */
    public static OptionalLong shiftEnd(Job job) {
        if (job == ModJobs.CLERK.get()) {
            return OptionalLong.of(SHIFT_END);
        }
        return job == ModJobs.TAVERN_HOST.get() ? OptionalLong.of(TAVERN_SHIFT_END) : OptionalLong.empty();
    }

    /**
     * @param clerk   the Clerk or Tavern Host settler
     * @param day     the day
     * @param dayTime the day time (0 = 06:00; negative before 06:00 on the Clerk's own day, {@link Schedule#moment})
     * @param job     the counter job ({@link #shiftEnd})
     * @return whether it lies within the shift: from its own waking time (05:30–06:00, counter jobs wake up early) to
     *         the end of the job's shift. The shift of the clock's next day may already be open after 05:30. A job without
     *         a counter has no shift.
     */
    public static boolean inShift(UUID clerk, long day, long dayTime, Job job) {
        OptionalLong end = shiftEnd(job);
        if (end.isEmpty()) {
            return false;
        }
        boolean today = dayTime >= shiftStart(clerk, day) && dayTime < end.getAsLong();
        return today || dayTime - DayClock.DAY_TICKS >= shiftStart(clerk, day + 1);
    }

    /**
     * @param clerk the Clerk settler
     * @param day   the day
     * @return the day time its shift opens: its waking time in the early waking window (05:30–06:00, negative)
     */
    public static long shiftStart(UUID clerk, long day) {
        return Schedule.dayTimes(clerk, day, true).wake();
    }

    /**
     * Service time at the counter: 2 s at skill level 1 down to 1 s at level 100, on the exponential curve of the work
     * speed with half its gain.
     *
     * @param skillLevel the Clerk's skill level
     * @return ticks per customer
     */
    public static int serviceTicks(int skillLevel) {
        return (int) Math.round(SERVICE_TICKS / Math.pow(2.0, (Math.clamp(skillLevel, 1, 100) - 1) / 99.0));
    }

    /**
     * @param clerk   a Clerk JobBlock
     * @param day     the day
     * @param dayTime the day time
     * @return whether its settler works it now (assigned and on shift); only then is the linked storage reachable
     */
    public static boolean working(SettlementBlock clerk, long day, long dayTime) {
        return hasCounter(clerk) && clerk.assignee(clerk.jobType()).filter(assignee -> inShift(assignee.id(), day, dayTime, clerk.jobType())).isPresent();
    }

    /**
     * {@link #working(SettlementBlock, long, long)} for the JobBlock at a position, on the level's day.
     *
     * @param level   the level
     * @param pos     a JobBlock position
     * @param dayTime the day time
     * @return whether a Clerk or Tavern Host works the JobBlock there now (a Pantry then serves at its counter)
     */
    public static boolean working(ServerLevel level, BlockPos pos, long dayTime) {
        return JobBlockSite.blockAt(level, pos).filter(block -> working(level, block, dayTime)).isPresent();
    }

    /**
     * {@link #working(SettlementBlock, long, long)} on the level's day. Catch-up passes a simulated day time from 06:00
     * on, where the day does not matter: every Clerk's shift is open by then.
     */
    private static boolean working(ServerLevel level, SettlementBlock clerk, long dayTime) {
        return working(clerk, DayClock.day(level), dayTime);
    }

    /**
     * @param clerk a Clerk JobBlock
     * @return the linked containers in use: as many as its level allows, the first linked ones (the rest is kept but
     *         inactive after a downgrade)
     */
    public static List<BlockPos> activeStorage(SettlementBlock clerk) {
        List<BlockPos> storage = clerk.storage();
        return storage.subList(0, Math.min(storage.size(), SettlementRules.clerkStorage(clerk.level())));
    }

    /**
     * What a visitor reaches at a position under the access rule.
     *
     * @param level     the level
     * @param pos       a Clerk JobBlock or any container
     * @param dayTime   the day time
     * @param atCounter whether the visitor stands at the Clerk's counter (catch-up: always)
     * @return the reachable stock
     */
    public static Stock stock(ServerLevel level, BlockPos pos, long dayTime, boolean atCounter) {
        return stock(level, pos, dayTime, atCounter, null);
    }

    /**
     * What a visitor reaches at a position under the access rule, for a Delivery task (a pickup at a foreign Clerk's
     * counter).
     *
     * @param level     the level
     * @param pos       a Clerk JobBlock or any container
     * @param dayTime   the day time
     * @param atCounter whether the visitor stands at the Clerk's counter (catch-up: always)
     * @param task      the Delivery task asking, which sees what is reserved for it; null for everybody else
     * @return the reachable stock, without what the Clerk reserved for other tasks
     */
    public static Stock stock(ServerLevel level, BlockPos pos, long dayTime, boolean atCounter, @Nullable UUID task) {
        Optional<SettlementBlock> clerk = JobBlockSite.blockAt(level, pos).filter(ClerkStorage::isClerk);
        return stock(level, pos, clerk.isPresent() && atCounter && working(level, clerk.get(), dayTime), task);
    }

    /**
     * Where a settler puts something into a Clerk or a container: a Clerk's linked containers at any time, on shift or
     * not, because putting items in gives nothing away. A Clerk JobBlock without a linked container takes nothing
     * ("Storage full").
     *
     * @param level the level
     * @param pos   a Clerk JobBlock or any container
     * @return the stock to insert into
     */
    public static Stock dropOff(ServerLevel level, BlockPos pos) {
        return stock(level, pos, true);
    }

    /**
     * @param level  the level
     * @param pos    a Clerk JobBlock or any container
     * @param linked whether the linked storage of a Clerk is reachable (its working Clerk serves the visitor, or staff)
     * @return the reachable stock: a Clerk JobBlock has no slots of its own, so nothing without the linked storage
     */
    public static Stock stock(ServerLevel level, BlockPos pos, boolean linked) {
        return stock(level, pos, linked, null);
    }

    /**
     * @param level  the level
     * @param pos    a Clerk JobBlock or any container
     * @param linked whether the linked storage of a Clerk is reachable (its working Clerk serves the visitor)
     * @param task   the Delivery task asking, which sees what is reserved for it; null for everybody else
     * @return the reachable stock, without what the Clerk reserved for other tasks
     */
    public static Stock stock(ServerLevel level, BlockPos pos, boolean linked, @Nullable UUID task) {
        if (!level.isLoaded(pos)) {
            return Stock.EMPTY;
        }
        Optional<JobBlockSite> clerk = JobBlockSite.at(level, pos).filter(site -> isClerk(site.block()));
        ClerkStock view = clerk.isPresent() ? ClerkStock.of(level, pos) : null;
        if (view != null) {
            return view.stock(activeStorage(clerk.get().block()), linked)
                    .without(Reservations.reserved(clerk.get().settlement(), pos, DayClock.day(level), task));
        }
        ResourceHandler<ItemResource> own = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        return own == null ? Stock.EMPTY : Stock.live(List.of(own), false);
    }

    /**
     * Drops the stock view of a JobBlock block entity that is removed or unloaded.
     *
     * @param level the level
     * @param block the block entity
     */
    public static void forgetStock(ServerLevel level, JobBlockEntity block) {
        ClerkStock.forget(level, block);
    }

    /**
     * The write-through seam of the surplus check: the Clerks whose stock CivMod's own transactions (drop-offs,
     * results, deliveries, unloads) increased since the last call.
     *
     * @param level the level
     * @return those Clerk JobBlocks with the items that went in; the record is cleared
     */
    public static Map<BlockPos, Set<Item>> takeGrown(ServerLevel level) {
        return ClerkStock.takeGrown(level);
    }

    /**
     * Where a visitor walks to reach a position: a working Clerk serves at today's stand spot (or next to its JobBlock
     * without spots), everything else is reached at the block itself.
     *
     * @param level   the level
     * @param pos     a Clerk JobBlock or any container
     * @param dayTime the day time
     * @return the visit
     */
    public static Visit visit(ServerLevel level, BlockPos pos, long dayTime) {
        Optional<SettlementBlock> clerk = JobBlockSite.blockAt(level, pos).filter(ClerkStorage::hasCounter);
        if (clerk.isEmpty() || !working(level, clerk.get(), dayTime)) {
            return new Visit(pos, BLOCK_REACH, false);
        }
        BlockPos counter = spotOfDay(clerk.get()).map(spot -> spot.pos().above()).orElse(pos);
        return new Visit(counter, COUNTER_REACH, true);
    }

    /**
     * @param pos a Clerk JobBlock or any container
     * @return the visit of a visitor that does not reach the counter: at the block itself (a Clerk JobBlock gives nothing out there)
     */
    public static Visit blockVisit(BlockPos pos) {
        return new Visit(pos, BLOCK_REACH, false);
    }

    /**
     * @param clerk a Clerk JobBlock
     * @return the stand spot the Clerk uses now, as last picked (see {@code ClerkSpots})
     */
    public static Optional<SettlementBlock.StandSpot> spotOfDay(SettlementBlock clerk) {
        int index = clerk.spotOfDay().index();
        return index >= 0 && index < clerk.standSpots().size() ? Optional.of(clerk.standSpots().get(index)) : Optional.empty();
    }

    /**
     * The nearest food point for a settler: its own Pantry first (a Tavern Host eats from its Pantry), then every
     * Pantry by distance that has edible food. Checked server-side, so no walk is wasted. At a Pantry whose Tavern Host
     * works, the settler is served at the counter; otherwise it helps itself.
     *
     * @param level            the level
     * @param settlement       the settler's settlement
     * @param from             the settler position
     * @param dayTime          the day time
     * @param ownPantry        the Pantry the settler works as Tavern Host, if any
     * @param counterReachable whether the settler reaches the counter of a Pantry (false: it takes from the block)
     * @return the food point, empty if no Pantry has food for the settler
     */
    public static Optional<FoodPoint> nearestFood(ServerLevel level, Settlement settlement, BlockPos from, long dayTime,
            Optional<BlockPos> ownPantry, Predicate<BlockPos> counterReachable) {
        List<SettlementBlock> pantries = new ArrayList<>(settlement.blocks().stream().filter(ClerkStorage::isPantry).toList());
        pantries.sort(Comparator.<SettlementBlock>comparingInt(block -> ownPantry.filter(block.pos()::equals).isPresent() ? 0 : 1)
                .thenComparingDouble(block -> block.pos().distSqr(from)));
        for (SettlementBlock pantry : pantries) {
            boolean own = ownPantry.filter(pantry.pos()::equals).isPresent();
            Visit visit = visit(level, pantry.pos(), dayTime);
            if (!own && visit.counter() && !counterReachable.test(pantry.pos())) {
                visit = new Visit(pantry.pos(), BLOCK_REACH, false);
            }
            if (stock(level, pantry.pos(), false).contains(SettlerFood::isEdible)) {
                return Optional.of(new FoodPoint(pantry.pos(), visit));
            }
        }
        return Optional.empty();
    }

    /**
     * The Pantry a hungry settler without food waits at (with food or not): the nearest one.
     *
     * @param settlement the settler's settlement
     * @param from       the settler position
     * @return the Pantry, empty if the settlement has none
     */
    public static Optional<BlockPos> nearestPantry(Settlement settlement, BlockPos from) {
        return settlement.blocks().stream().filter(ClerkStorage::isPantry).map(SettlementBlock::pos)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(from)));
    }

    /**
     * Drops the stock views of an unloaded level.
     */
    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ClerkStock.forget(level);
        }
    }

    /**
     * Removes a broken block as storage or stand spot right away (other missing containers are skipped on access).
     */
    @SubscribeEvent
    static void onBreak(BreakBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            JobAssignments.removePointsAt(SettlementData.get(level), event.getPos());
        }
    }
}
