package com.gilfort.civmod.job;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.gilfort.civmod.api.ScanResult;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A scan result saved with a settler (needed to catch up after loading) and on the JobBlock (its Found tab), with the
 * key it was made for. Only the built-in result types are saved (wood, mine, water, block counts, animals); an addon's own
 * {@link ScanResult} stays in memory (documented on {@link ScanResult}).
 *
 * @param jobBlock the JobBlock of the job
 * @param center   the scan centre
 * @param radius   the scan radius
 * @param day      the day of a daily scan, -1 otherwise
 * @param result   the result, of one of the built-in types
 */
public record SavedScan(BlockPos jobBlock, BlockPos center, int radius, long day, ScanResult result) {

    private static final Codec<WoodScan.Result> WOOD = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).fieldOf("logs").forGetter(WoodScan.Result::logs),
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).fieldOf("leaves").forGetter(WoodScan.Result::leaves)
    ).apply(instance, (logs, leaves) -> new WoodScan.Result(Map.copyOf(logs), Map.copyOf(leaves))));

    private static final Codec<MineScan.Result> MINE = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.listOf().fieldOf("stones").forGetter(result -> List.copyOf(result.stones())),
            Identifier.CODEC.listOf().fieldOf("ores").forGetter(result -> List.copyOf(result.ores()))
    ).apply(instance, (stones, ores) -> new MineScan.Result(Set.copyOf(stones), Set.copyOf(ores))));

    /**
     * A Beekeeper scan saved before it counted per honey level (now saved as block counts): loads as hives with honey,
     * without the full ones until the next scan. Never written.
     */
    private static final Codec<BlockCountScan.Result> LEGACY_HIVES = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).fieldOf("counts").forGetter(BlockCountScan.Result::counts),
            Codec.INT.optionalFieldOf("full", 0).forGetter(result -> 0)
    ).apply(instance, (counts, full) -> new BlockCountScan.Result(Map.copyOf(counts))));

    /** One counted block state and its count. */
    private static final Codec<Map.Entry<BlockState, Integer>> STATE_COUNT = RecordCodecBuilder.create(instance -> instance.group(
            BlockState.CODEC.fieldOf("state").forGetter(Map.Entry::getKey),
            Codec.INT.fieldOf("count").forGetter(Map.Entry::getValue)
    ).apply(instance, Map::entry));

    /** Block counts per counted state; a save from before the states (counts per block id) loads with the rolled states. */
    private static final Codec<BlockCountScan.Result> COUNTS = Codec.withAlternative(
            STATE_COUNT.listOf().xmap(entries -> BlockCountScan.Result.ofStates(entries.stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum))),
                    result -> List.copyOf(result.states().entrySet())),
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).xmap(counts -> new BlockCountScan.Result(Map.copyOf(counts)), BlockCountScan.Result::counts));

    /**
     * The saved result types, each under its own key next to the scan key (Lumberjack, Miner, Fisherman, Simple
     * Collection profiles and the Beekeeper, Animal Farmer). A new built-in type is one more entry; a result is written
     * under the first entry of its type, so a legacy entry after it only loads.
     */
    private static final List<Kind<?>> KINDS = List.of(
            new Kind<>("wood", WoodScan.Result.class, WOOD),
            new Kind<>("mine", MineScan.Result.class, MINE),
            new Kind<>("water", WaterScan.Result.class, Codec.INT.xmap(WaterScan.Result::new, WaterScan.Result::water)),
            new Kind<>("counts", BlockCountScan.Result.class, COUNTS),
            new Kind<>("animals", AnimalScan.Result.class, Codec.unboundedMap(Identifier.CODEC, Codec.INT)
                    .xmap(counts -> new AnimalScan.Result(Map.copyOf(counts)), AnimalScan.Result::counts)),
            new Kind<>("hives", BlockCountScan.Result.class, LEGACY_HIVES));

    /** The result under the key of its type. */
    private static final MapCodec<ScanResult> RESULT = new MapCodec<>() {

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return KINDS.stream().map(kind -> ops.createString(kind.key()));
        }

        @Override
        public <T> DataResult<ScanResult> decode(DynamicOps<T> ops, MapLike<T> input) {
            for (Kind<?> kind : KINDS) {
                T value = input.get(kind.key());
                if (value != null) {
                    return kind.codec().parse(ops, value).map(ScanResult.class::cast);
                }
            }
            return DataResult.error(() -> "No saved scan result");
        }

        @Override
        public <T> RecordBuilder<T> encode(ScanResult input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            return kind(input).map(kind -> kind.encode(input, ops, prefix))
                    .orElseGet(() -> prefix.withErrorsFrom(DataResult.error(() -> "Unsaved scan result type " + input.getClass().getName())));
        }
    };

    /** Save codec. */
    public static final Codec<SavedScan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("job_block").forGetter(SavedScan::jobBlock),
            BlockPos.CODEC.fieldOf("center").forGetter(SavedScan::center),
            Codec.INT.fieldOf("radius").forGetter(SavedScan::radius),
            Codec.LONG.fieldOf("day").forGetter(SavedScan::day),
            RESULT.forGetter(SavedScan::result)
    ).apply(instance, SavedScan::new));

    /**
     * @param jobBlock the JobBlock
     * @param center   the scan centre
     * @param radius   the radius
     * @param day      the day (-1 if not daily)
     * @param result   the result
     * @return the saved form, empty for result types that are not saved (an addon's own)
     */
    public static Optional<SavedScan> of(BlockPos jobBlock, BlockPos center, int radius, long day, ScanResult result) {
        return kind(result).map(kind -> new SavedScan(jobBlock, center, radius, day, result));
    }

    private static Optional<Kind<?>> kind(ScanResult result) {
        return KINDS.stream().filter(kind -> kind.type().isInstance(result)).findFirst();
    }

    /**
     * A saved result type.
     *
     * @param key   its key in the save
     * @param type  its class
     * @param codec its codec
     */
    private record Kind<R extends ScanResult>(String key, Class<R> type, Codec<R> codec) {

        <T> RecordBuilder<T> encode(ScanResult result, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            return prefix.add(key, codec.encodeStart(ops, type.cast(result)));
        }
    }
}
