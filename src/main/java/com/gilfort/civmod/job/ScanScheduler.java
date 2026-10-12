package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Runs job scans spread over ticks with a global budget of {@value #SECTIONS_PER_TICK} chunk sections per tick
 * (vault {@code Systems/Resource Collection Jobs.md}, performance decision).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ScanScheduler {

    /** Global budget of scanned chunk sections per server tick. */
    public static final int SECTIONS_PER_TICK = 4;

    private static final List<Pending> QUEUE = new ArrayList<>();

    private record Pending(ServerLevel level, WoodScan scan, long startTick, Consumer<WoodScan.Result> onDone) {
    }

    private ScanScheduler() {
    }

    /**
     * Queues a scan.
     *
     * @param level      the level
     * @param scan       the scan
     * @param delayTicks ticks to wait before starting (spreads scans at the start of the workday)
     * @param onDone     receives the result
     */
    public static void submit(ServerLevel level, WoodScan scan, long delayTicks, Consumer<WoodScan.Result> onDone) {
        QUEUE.add(new Pending(level, scan, level.getGameTime() + delayTicks, onDone));
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        int budget = SECTIONS_PER_TICK;
        Iterator<Pending> iterator = QUEUE.iterator();
        while (budget > 0 && iterator.hasNext()) {
            Pending pending = iterator.next();
            if (pending.level().getGameTime() < pending.startTick()) {
                continue;
            }
            while (budget > 0 && !pending.scan().isDone()) {
                pending.scan().step(pending.level());
                budget--;
            }
            if (pending.scan().isDone()) {
                iterator.remove();
                pending.onDone().accept(pending.scan().result());
            }
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        QUEUE.clear();
    }
}
