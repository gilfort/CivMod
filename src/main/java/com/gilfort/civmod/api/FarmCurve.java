package com.gilfort.civmod.api;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.Mth;

/**
 * The farm curve of a farming Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md}): a bigger farm
 * works faster, up to twice the shared speed formula. The productivity is {@code 0.2 × (1 + k × count)}, at most 2.0,
 * where the count (plants, animals, hives; {@link ResourceCollectionProfile#farmCount}) is capped per JobBlock level, so
 * one plant is slow and a farm pays off only with a JobBlock upgrade. A Job declares its curve with
 * {@link Job.Builder#farmCurve}; datapacks override it in {@code civmod_jobs/<job>.json} under {@code farm_curve}:
 *
 * <pre>{@code
 * "farm_curve": { "k": 0.005, "caps": [100, 350, 750, 1350, 2000] }
 * }</pre>
 *
 * @param k    the speed gained per counted plant, animal or hive
 * @param caps the largest count that still counts, per JobBlock level from 1 (levels beyond the list use its last value)
 */
@ApiStatus.Experimental
public record FarmCurve(double k, List<Integer> caps) {

    /** The productivity of an empty farm. */
    public static final double BASE = 0.2;
    /** The highest productivity: twice the shared speed formula. */
    public static final double MAX = 2.0;

    /** Data codec ({@code farm_curve}). */
    public static final Codec<FarmCurve> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.doubleRange(0.0, 100.0).fieldOf("k").forGetter(FarmCurve::k),
            Codec.intRange(0, 1_000_000).listOf(1, 5).fieldOf("caps").forGetter(FarmCurve::caps)
    ).apply(instance, FarmCurve::new));

    /**
     * @param k    the speed gained per counted plant, animal or hive
     * @param caps the cap per JobBlock level (1–5 entries)
     */
    public FarmCurve {
        caps = List.copyOf(caps);
        if (caps.isEmpty()) {
            throw new IllegalArgumentException("A farm curve needs a cap per JobBlock level");
        }
    }

    /**
     * @param jobBlockLevel the JobBlock level (1–5)
     * @return the largest count that counts at that level
     */
    public int cap(int jobBlockLevel) {
        return caps.get(Mth.clamp(jobBlockLevel, 1, caps.size()) - 1);
    }

    /**
     * @param count         the counted plants, animals or hives
     * @param jobBlockLevel the JobBlock level (1–5)
     * @return the productivity factor on the shared speed formula
     */
    public double productivity(int count, int jobBlockLevel) {
        return Math.min(MAX, BASE * (1.0 + k * Math.min(Math.max(0, count), cap(jobBlockLevel))));
    }
}
