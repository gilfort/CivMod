package com.gilfort.civmod.job;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * A scan result saved with a settler (needed to catch up after loading), with the key it was made for.
 *
 * @param jobBlock the JobBlock of the job
 * @param center   the scan centre
 * @param radius   the scan radius
 * @param day      the day of a daily scan, -1 otherwise
 * @param wood     a Lumberjack result
 * @param mine     a Miner result
 * @param water    a Fisherman result (water count)
 */
public record SavedScan(BlockPos jobBlock, BlockPos center, int radius, long day, Optional<WoodScan.Result> wood,
        Optional<MineScan.Result> mine, Optional<Integer> water) {

    private static final Codec<WoodScan.Result> WOOD = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).fieldOf("logs").forGetter(WoodScan.Result::logs),
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).fieldOf("leaves").forGetter(WoodScan.Result::leaves)
    ).apply(instance, (logs, leaves) -> new WoodScan.Result(Map.copyOf(logs), Map.copyOf(leaves))));

    private static final Codec<MineScan.Result> MINE = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.listOf().fieldOf("stones").forGetter(result -> List.copyOf(result.stones())),
            Identifier.CODEC.listOf().fieldOf("ores").forGetter(result -> List.copyOf(result.ores()))
    ).apply(instance, (stones, ores) -> new MineScan.Result(Set.copyOf(stones), Set.copyOf(ores))));

    /** Save codec. */
    public static final Codec<SavedScan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("job_block").forGetter(SavedScan::jobBlock),
            BlockPos.CODEC.fieldOf("center").forGetter(SavedScan::center),
            Codec.INT.fieldOf("radius").forGetter(SavedScan::radius),
            Codec.LONG.fieldOf("day").forGetter(SavedScan::day),
            WOOD.optionalFieldOf("wood").forGetter(SavedScan::wood),
            MINE.optionalFieldOf("mine").forGetter(SavedScan::mine),
            Codec.INT.optionalFieldOf("water").forGetter(SavedScan::water)
    ).apply(instance, SavedScan::new));

    /**
     * @param jobBlock the JobBlock
     * @param center   the scan centre
     * @param radius   the radius
     * @param day      the day (-1 if not daily)
     * @param result   the result
     * @return the saved form, empty for unknown result types
     */
    public static Optional<SavedScan> of(BlockPos jobBlock, BlockPos center, int radius, long day, ScanResult result) {
        return switch (result) {
            case WoodScan.Result wood -> Optional.of(new SavedScan(jobBlock, center, radius, day, Optional.of(wood), Optional.empty(), Optional.empty()));
            case MineScan.Result mine -> Optional.of(new SavedScan(jobBlock, center, radius, day, Optional.empty(), Optional.of(mine), Optional.empty()));
            case WaterScan.Result water -> Optional.of(new SavedScan(jobBlock, center, radius, day, Optional.empty(), Optional.empty(),
                    Optional.of(water.water())));
            default -> Optional.empty();
        };
    }

    /**
     * @return the result, null if none is stored
     */
    public @Nullable ScanResult result() {
        if (wood.isPresent()) {
            return wood.get();
        }
        if (mine.isPresent()) {
            return mine.get();
        }
        return water.map(WaterScan.Result::new).orElse(null);
    }
}
