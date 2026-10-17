package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The counter of a working Clerk (vault {@code Jobs/Clerk.md}): one customer at a time, the others wait nearby in a
 * loose queue; serving takes {@link ClerkStorage#serviceTicks} by the Clerk's skill. A hand-out gives the Clerk EXP
 * (base EXP × JobBlock level) with an arm swing and a sound. Catch-up has no queue: hand-outs only give EXP.
 * <p>
 * The queue is transient (lost on a restart, which only restarts a service); a customer that stops asking for a while
 * drops out of it.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
final class ClerkCounter {

    /** A customer that has not asked for this many ticks is no longer served. */
    private static final long STALE_TICKS = 40L;

    private static final Map<GlobalPos, Service> SERVING = new HashMap<>();

    /**
     * The customer being served.
     *
     * @param customer the customer
     * @param readyAt  game time at which the hand-out is ready
     * @param lastSeen game time the customer last asked
     */
    private record Service(UUID customer, long readyAt, long lastSeen) {
    }

    private ClerkCounter() {
    }

    /**
     * Asks to be served at a Clerk's counter; called every tick while the customer waits there.
     *
     * @param level    the level
     * @param clerk    the Clerk JobBlock
     * @param customer the customer
     * @return whether the customer is served now (the hand-out may happen); false while it waits its turn or is served
     */
    static boolean serve(ServerLevel level, BlockPos clerk, UUID customer) {
        GlobalPos key = GlobalPos.of(level.dimension(), clerk.immutable());
        long now = level.getGameTime();
        Service service = SERVING.get(key);
        if (service != null && now - service.lastSeen() > STALE_TICKS) {
            service = null;
        }
        if (service == null) {
            if (clerkOnBreak(level, clerk)) {
                return false;
            }
            SERVING.put(key, new Service(customer, now + ClerkStorage.serviceTicks(clerkSkill(level, clerk)), now));
            return false;
        }
        if (!service.customer().equals(customer)) {
            return false;
        }
        if (now >= service.readyAt()) {
            SERVING.remove(key);
            return true;
        }
        SERVING.put(key, new Service(customer, service.readyAt(), now));
        return false;
    }

    /**
     * @param level the level
     * @param clerk the Clerk JobBlock
     * @return whether a customer is being served or waits for its hand-out (the Clerk takes no break then)
     */
    static boolean busy(ServerLevel level, BlockPos clerk) {
        Service service = SERVING.get(GlobalPos.of(level.dimension(), clerk));
        return service != null && level.getGameTime() - service.lastSeen() <= STALE_TICKS;
    }

    /**
     * A hand-out from the Clerk's storage: the Clerk settler gains EXP (base EXP × JobBlock level) and, in live play,
     * swings its arm with a sound.
     *
     * @param level   the level
     * @param clerk   the Clerk JobBlock
     * @param effects whether to show the arm swing and sound (not in catch-up)
     */
    static void handOut(ServerLevel level, BlockPos clerk, boolean effects) {
        clerkSettler(level, clerk).ifPresent(settler -> settler.getWork().handedOut(level, clerk, effects));
    }

    /**
     * Forgets the counter of a Clerk JobBlock (removed, or its Clerk left).
     *
     * @param level the level
     * @param clerk the Clerk JobBlock
     */
    static void forget(ServerLevel level, BlockPos clerk) {
        SERVING.remove(GlobalPos.of(level.dimension(), clerk));
    }

    private static Optional<Settler> clerkSettler(ServerLevel level, BlockPos clerk) {
        return JobBlockSite.blockAt(level, clerk).flatMap(block -> block.assignees().stream().findFirst())
                .map(SettlementBlock.Assignee::id).map(level::getEntity).filter(Settler.class::isInstance).map(Settler.class::cast);
    }

    private static int clerkSkill(ServerLevel level, BlockPos clerk) {
        return clerkSettler(level, clerk).flatMap(settler -> settler.getJobs().stream().filter(job -> job.jobBlock().equals(clerk)).findFirst()
                .map(job -> settler.getWork().effectiveLevel(job))).orElse(1);
    }

    private static boolean clerkOnBreak(ServerLevel level, BlockPos clerk) {
        return clerkSettler(level, clerk).map(settler -> settler.getWork().clerkOnBreak()).orElse(false);
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        SERVING.clear();
    }
}
