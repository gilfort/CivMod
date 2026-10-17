package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The Clerk's store (vault {@code Jobs/Clerk.md}): the Clerk JobBlock's own 27 slots plus the containers linked with the
 * Scroll of Assignment, and the access rule between them. Without a working Clerk (no settler assigned, or outside
 * its shift from its waking time to 18:30) only the 27 block slots are reachable – for customers and for deliveries; the linked storage
 * is reachable only through a working Clerk at its counter. Hoppers and pipes use the block's item capability and see
 * the 27 slots only.
 * <p>
 * Every Clerk JobBlock is a food point: settlers take food from the nearest Clerk whose reachable storage has food
 * ({@link #nearestFood}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkStorage {

    /** End of the Clerk's shift in day ticks (18:30; day time 0 = 06:00). */
    public static final long SHIFT_END = 12500L;
    /** Customers are served within this distance of the Clerk's counter. */
    public static final double COUNTER_REACH = 3.0;
    /** Reach when walking to a closed Clerk JobBlock (its own slots), as for any container. */
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
     * @param clerk   the Clerk settler
     * @param day     the day
     * @param dayTime the day time (0 = 06:00; negative before 06:00 on the Clerk's own day, {@link Schedule#moment})
     * @return whether it lies within the Clerk's shift: from its own waking time (05:30–06:00, Clerks wake up early) to
     *         18:30. The shift of the clock's next day may already be open after 05:30.
     */
    public static boolean inShift(UUID clerk, long day, long dayTime) {
        boolean today = dayTime >= shiftStart(clerk, day) && dayTime < SHIFT_END;
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
     * @return whether a Clerk settler works it now (assigned and on shift); only then is the linked storage reachable
     */
    public static boolean working(SettlementBlock clerk, long day, long dayTime) {
        return isClerk(clerk) && clerk.assignees().stream().anyMatch(assignee -> inShift(assignee.id(), day, dayTime));
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
        Optional<SettlementBlock> clerk = JobBlockSite.blockAt(level, pos).filter(ClerkStorage::isClerk);
        return stock(level, pos, clerk.isPresent() && atCounter && working(level, clerk.get(), dayTime));
    }

    /**
     * @param level  the level
     * @param pos    a Clerk JobBlock or any container
     * @param linked whether the linked storage of a Clerk is reachable (its working Clerk serves the visitor)
     * @return the reachable stock
     */
    public static Stock stock(ServerLevel level, BlockPos pos, boolean linked) {
        List<ResourceHandler<ItemResource>> handlers = new ArrayList<>();
        if (!level.isLoaded(pos)) {
            return Stock.EMPTY;
        }
        ResourceHandler<ItemResource> own = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        if (own != null) {
            handlers.add(own);
        }
        Optional<SettlementBlock> clerk = linked ? JobBlockSite.blockAt(level, pos).filter(ClerkStorage::isClerk) : Optional.empty();
        clerk.ifPresent(block -> {
            for (BlockPos container : activeStorage(block)) {
                if (level.isLoaded(container)) {
                    ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, container, null);
                    if (handler != null) {
                        handlers.add(handler);
                    }
                }
            }
        });
        return new Stock(handlers, clerk.isPresent());
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
        Optional<SettlementBlock> clerk = JobBlockSite.blockAt(level, pos).filter(ClerkStorage::isClerk);
        if (clerk.isEmpty() || !working(level, clerk.get(), dayTime)) {
            return new Visit(pos, BLOCK_REACH, false);
        }
        BlockPos counter = spotOfDay(clerk.get()).map(spot -> spot.pos().above()).orElse(pos);
        return new Visit(counter, COUNTER_REACH, true);
    }

    /**
     * @param pos a Clerk JobBlock or any container
     * @return the visit of a visitor that does not reach the counter: at the block itself, its own slots only
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
     * The nearest food point for a settler: its own Clerk first (a Clerk eats from its own storage), then every
     * Clerk JobBlock by distance whose reachable storage has edible food. Checked server-side, so no walk is wasted.
     *
     * @param level            the level
     * @param settlement       the settler's settlement
     * @param from             the settler position
     * @param dayTime          the day time
     * @param ownClerk         the Clerk JobBlock the settler works as Clerk, if any
     * @param counterReachable whether the settler reaches the counter of a Clerk (false: only its block slots)
     * @return the food point, empty if no Clerk has food for the settler
     */
    public static Optional<FoodPoint> nearestFood(ServerLevel level, Settlement settlement, BlockPos from, long dayTime,
            Optional<BlockPos> ownClerk, Predicate<BlockPos> counterReachable) {
        List<SettlementBlock> clerks = new ArrayList<>(settlement.blocks().stream().filter(ClerkStorage::isClerk).toList());
        clerks.sort(Comparator.<SettlementBlock>comparingInt(block -> ownClerk.filter(block.pos()::equals).isPresent() ? 0 : 1)
                .thenComparingDouble(block -> block.pos().distSqr(from)));
        for (SettlementBlock clerk : clerks) {
            boolean own = ownClerk.filter(clerk.pos()::equals).isPresent();
            Visit visit = visit(level, clerk.pos(), dayTime);
            boolean counter = visit.counter() && counterReachable.test(clerk.pos());
            if (!own && visit.counter() && !counter) {
                visit = new Visit(clerk.pos(), BLOCK_REACH, false);
            }
            if (stock(level, clerk.pos(), own || counter).contains(SettlerFood::isEdible)) {
                return Optional.of(new FoodPoint(clerk.pos(), visit));
            }
        }
        return Optional.empty();
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
