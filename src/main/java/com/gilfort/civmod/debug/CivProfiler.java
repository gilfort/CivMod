package com.gilfort.civmod.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.gilfort.civmod.CivMod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Lightweight profiler for the PoC benchmark: sums the time CivMod code spends per server tick in a few sections
 * and keeps the last {@value #WINDOW} ticks. Disabled (no measuring) until the benchmark enables it.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CivProfiler {

    /** Measured sections. */
    public enum Section {
        /** Settler decisions (every 5 s, staggered), hunger, membership checks. */
        DECISIONS("decisions"),
        /** Per-tick work (production, eating, sleeping). */
        WORK("work ticks"),
        /** Job scans (spread over ticks). */
        SCANS("scans"),
        /** Catch-up simulation. */
        CATCH_UP("catch-up"),
        /** Settler entities in total, including vanilla AI, pathfinding and physics (contains decisions and work). */
        SETTLERS("settler entities total");

        private final String label;

        Section(String label) {
            this.label = label;
        }
    }

    private static final int WINDOW = 1200;
    private static final int COLUMNS = Section.values().length + 1;
    private static final int SERVER = Section.values().length;

    private static boolean enabled;
    private static final long[] CURRENT = new long[Section.values().length];
    private static final long[][] HISTORY = new long[WINDOW][COLUMNS];
    private static int index;
    private static int filled;
    private static long tickStart;

    private CivProfiler() {
    }

    /**
     * Starts measuring (clears earlier values).
     */
    public static void enable() {
        enabled = true;
        index = 0;
        filled = 0;
    }

    /**
     * @return a start timestamp, 0 while disabled
     */
    public static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    /**
     * Adds the time since {@code start} to a section.
     *
     * @param section the section
     * @param start   the value returned by {@link #start()}
     */
    public static void stop(Section section, long start) {
        if (start != 0L) {
            CURRENT[section.ordinal()] += System.nanoTime() - start;
        }
    }

    @SubscribeEvent
    static void onTickStart(ServerTickEvent.Pre event) {
        tickStart = enabled ? System.nanoTime() : 0L;
    }

    @SubscribeEvent
    static void onTickEnd(ServerTickEvent.Post event) {
        if (!enabled || tickStart == 0L) {
            return;
        }
        long[] row = HISTORY[index];
        System.arraycopy(CURRENT, 0, row, 0, CURRENT.length);
        row[SERVER] = System.nanoTime() - tickStart;
        java.util.Arrays.fill(CURRENT, 0L);
        index = (index + 1) % WINDOW;
        filled = Math.min(filled + 1, WINDOW);
    }

    /**
     * @return report lines: average and maximum per section over the recorded ticks
     */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        if (filled == 0) {
            lines.add("No data – run /civmod debug benchmark first.");
            return lines;
        }
        lines.add(String.format(Locale.ROOT, "Last %d ticks (%.0f s):", filled, filled / 20.0));
        long ownSum = 0L;
        long ownMax = 0L;
        for (int tick = 0; tick < filled; tick++) {
            long own = 0L;
            for (Section section : List.of(Section.DECISIONS, Section.WORK, Section.SCANS, Section.CATCH_UP)) {
                own += HISTORY[tick][section.ordinal()];
            }
            ownSum += own;
            ownMax = Math.max(ownMax, own);
        }
        lines.add(line("CivMod code (total)", ownSum / (double) filled, ownMax));
        for (Section section : Section.values()) {
            long sum = 0L;
            long max = 0L;
            for (int tick = 0; tick < filled; tick++) {
                sum += HISTORY[tick][section.ordinal()];
                max = Math.max(max, HISTORY[tick][section.ordinal()]);
            }
            lines.add(line("  " + section.label, sum / (double) filled, max));
        }
        long sum = 0L;
        long max = 0L;
        for (int tick = 0; tick < filled; tick++) {
            sum += HISTORY[tick][SERVER];
            max = Math.max(max, HISTORY[tick][SERVER]);
        }
        lines.add(line("Server tick (whole server)", sum / (double) filled, max));
        return lines;
    }

    private static String line(String label, double averageNanos, long maxNanos) {
        return String.format(Locale.ROOT, "%s: avg %.3f ms, max %.3f ms", label, averageNanos / 1.0E6, maxNanos / 1.0E6);
    }
}
