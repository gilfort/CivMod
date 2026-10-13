package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Catch-up simulation per settlement (vault {@code Systems/Settlement.md}): settlers that are ready to catch up are
 * collected for {@value #COLLECT_TICKS} ticks, then simulated day by day in hourly segments; the order of the
 * settlers rotates every day so shared drop-offs and pantries are used fairly. A global budget of
 * {@value #ROLLS_PER_TICK} production rolls per tick spreads large amounts over several ticks.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CatchUp {

    /** Ticks to wait for further settlers of the same settlement. */
    public static final int COLLECT_TICKS = 100;
    /** Production rolls simulated per server tick (all settlements). */
    public static final int ROLLS_PER_TICK = 1000;
    /** Awake hours per day (06:00–20:00). */
    private static final int HOURS = 14;

    private record Key(ResourceKey<Level> dimension, int settlement) {
    }

    private static final Map<Key, Batch> BATCHES = new HashMap<>();

    private CatchUp() {
    }

    /** Settlers of one settlement catching up together. */
    private static final class Batch {
        private final ServerLevel level;
        private final List<Settler> settlers = new ArrayList<>();
        private final long startTick;
        private long day = Long.MIN_VALUE;
        private long lastDay;
        private int hour;
        private int index;

        Batch(ServerLevel level) {
            this.level = level;
            this.startTick = level.getGameTime() + COLLECT_TICKS;
        }

        void begin() {
            day = Long.MAX_VALUE;
            lastDay = Long.MIN_VALUE;
            for (Settler settler : settlers) {
                settler.getWork().startCatchUp();
                day = Math.min(day, Math.floorDiv(settler.getWork().catchUpFrom(), 24000L));
                lastDay = Math.max(lastDay, Math.floorDiv(settler.getWork().catchUpTo() - 1, 24000L));
            }
        }

        /**
         * @return the rolls used; negative when the batch is complete
         */
        int step(int budget) {
            int used = 0;
            while (day <= lastDay) {
                List<Settler> order = order(settlers, day);
                while (hour < HOURS) {
                    while (index < order.size()) {
                        if (used >= budget) {
                            return used;
                        }
                        Settler settler = order.get(index++);
                        if (!settler.isRemoved()) {
                            used += settler.getWork().simulateHour(level, day, hour);
                        }
                    }
                    index = 0;
                    hour++;
                }
                hour = 0;
                day++;
            }
            settlers.stream().filter(settler -> !settler.isRemoved()).forEach(settler -> settler.getWork().finishCatchUp(level));
            return -1;
        }
    }

    /**
     * Registers a settler that is ready to catch up. A running batch is not extended: late settlers wait for the
     * next batch of their settlement.
     *
     * @param level   the level
     * @param settler the settler
     */
    public static void register(ServerLevel level, Settler settler) {
        Key key = new Key(level.dimension(), settler.getSettlementId());
        Batch batch = BATCHES.get(key);
        if (batch == null) {
            batch = new Batch(level);
            BATCHES.put(key, batch);
        } else if (batch.day != Long.MIN_VALUE) {
            WAITING.computeIfAbsent(key, k -> new ArrayList<>()).add(settler);
            return;
        }
        batch.settlers.add(settler);
    }

    private static final Map<Key, List<Settler>> WAITING = new HashMap<>();

    /**
     * @param settlers the settlers
     * @param day      the day
     * @return the settlers in the order of that day (rotated daily)
     */
    public static <T> List<T> order(List<T> settlers, long day) {
        List<T> order = new ArrayList<>(settlers);
        if (!order.isEmpty()) {
            Collections.rotate(order, (int) -Math.floorMod(day, (long) order.size()));
        }
        return order;
    }

    /**
     * Runs a complete catch-up at once (tests).
     *
     * @param level    the level
     * @param settlers settlers with missed time set
     */
    public static void runNow(ServerLevel level, List<Settler> settlers) {
        Batch batch = new Batch(level);
        batch.settlers.addAll(settlers);
        batch.begin();
        while (batch.step(Integer.MAX_VALUE) >= 0) {
            // runs to completion
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        long profile = com.gilfort.civmod.debug.CivProfiler.start();
        try {
            runBatches();
        } finally {
            com.gilfort.civmod.debug.CivProfiler.stop(com.gilfort.civmod.debug.CivProfiler.Section.CATCH_UP, profile);
        }
    }

    private static void runBatches() {
        int budget = ROLLS_PER_TICK;
        Iterator<Map.Entry<Key, Batch>> iterator = BATCHES.entrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            Batch batch = iterator.next().getValue();
            if (batch.level.getGameTime() < batch.startTick) {
                continue;
            }
            if (batch.day == Long.MIN_VALUE) {
                batch.begin();
            }
            int used = batch.step(budget);
            if (used < 0) {
                iterator.remove();
            } else {
                budget -= used;
            }
        }
        if (!WAITING.isEmpty()) {
            WAITING.entrySet().removeIf(entry -> {
                if (BATCHES.containsKey(entry.getKey()) || entry.getValue().isEmpty()) {
                    return entry.getValue().isEmpty();
                }
                Batch next = new Batch(entry.getValue().get(0).level() instanceof ServerLevel level ? level : null);
                next.settlers.addAll(entry.getValue());
                BATCHES.put(entry.getKey(), next);
                return true;
            });
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        BATCHES.clear();
        WAITING.clear();
    }
}
