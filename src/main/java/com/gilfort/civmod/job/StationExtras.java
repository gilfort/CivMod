package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.List;

import com.gilfort.civmod.api.Job;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Station Extras (vault {@code Systems/Producing Jobs.md}): blocks near a Job's station of the day that replace its base
 * job factor, from {@code station_extras} in the Job's datapack job file ({@link JobBalancing}). With several matching
 * entries the highest {@code job_factor} counts; without one the Job's own {@link Job#jobFactor()}. Not a bonus
 * multiplier: the biome bonuses act on top of the result. Any Job may name station extras; they take effect where a Job
 * works at a station of the day and its job factor counts (the Producing Jobs).
 */
public final class StationExtras {

    private StationExtras() {
    }

    /**
     * Reads the blocks around a station; called once per day and station ({@link Workplace}), as it scans up to a
     * {@value StationExtra#MAX_RADIUS}-block cube.
     *
     * @param level   the level
     * @param job     the Job
     * @param station the station of the day
     * @return the base job factor at that station: the highest factor of the matching station extras, else the Job's
     */
    public static double jobFactor(Level level, Job job, BlockPos station) {
        List<StationExtra> extras = JobBalancing.of(job).stationExtras().orElse(List.of()).stream()
                .sorted(Comparator.comparingDouble(StationExtra::jobFactor).reversed()).toList();
        for (StationExtra extra : extras) {
            if (near(level, station, extra)) {
                return extra.jobFactor();
            }
        }
        return job.jobFactor();
    }

    /** Whether a block of the entry stands within its radius of the station (loaded blocks only). */
    private static boolean near(Level level, BlockPos station, StationExtra extra) {
        int radius = extra.radius();
        for (BlockPos pos : BlockPos.betweenClosed(station.offset(-radius, -radius, -radius), station.offset(radius, radius, radius))) {
            if (level.isLoaded(pos) && extra.matches(level.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }
}
