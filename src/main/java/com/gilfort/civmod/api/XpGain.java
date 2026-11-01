package com.gilfort.civmod.api;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.RandomSource;

/**
 * The XP one action earns (vault {@code Systems/Settler XP.md}): {@code min}–{@code max} points with a chance, plus a
 * chance for one more point. XP is vanilla-style experience and independent of a Job's EXP. Live play rolls it, catch-up
 * adds the {@link #expected} value.
 *
 * @param min      the lowest amount
 * @param max      the highest amount
 * @param chance   the chance of the amount (0–1)
 * @param fraction the chance of one more point (0–1), independent of {@code chance}
 */
@ApiStatus.Experimental
public record XpGain(int min, int max, double chance, double fraction) {

    /** No XP. */
    public static final XpGain NONE = of(0);

    /** {@code n} or {@code [min, max]} as a list of both. */
    private static final Codec<List<Integer>> AMOUNT = Codec.either(Codec.intRange(0, Integer.MAX_VALUE), Codec.intRange(0, Integer.MAX_VALUE).listOf(2, 2))
            .xmap(either -> either.map(amount -> List.of(amount, amount), range -> range),
                    range -> range.get(0).equals(range.get(1)) ? Either.left(range.get(0)) : Either.right(range));

    /** The fields {@code amount} ({@code n} or {@code [min, max]}) and {@code chance} (default 1). */
    public static final MapCodec<XpGain> MAP_CODEC = RecordCodecBuilder.<XpGain>mapCodec(instance -> instance.group(
            AMOUNT.fieldOf("amount").forGetter(gain -> List.of(gain.min(), gain.max())),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("chance", 1.0).forGetter(XpGain::chance)
    ).apply(instance, (amount, chance) -> new XpGain(amount.get(0), amount.get(1), chance, 0.0)))
            .validate(gain -> gain.min() <= gain.max() ? DataResult.success(gain) : DataResult.error(() -> "XP amount: min must not be above max"));

    /** Data codec. */
    public static final Codec<XpGain> CODEC = MAP_CODEC.codec();

    /**
     * @param amount the amount
     * @return exactly that amount
     */
    public static XpGain of(int amount) {
        return of(amount, amount);
    }

    /**
     * @param min the lowest amount
     * @param max the highest amount
     * @return an amount between them
     */
    public static XpGain of(int min, int max) {
        return new XpGain(min, max, 1.0, 0.0);
    }

    /**
     * @param amount an amount with a fraction, such as a recipe's {@code experience}
     * @return its whole points; the fraction is a chance for one more, as at the vanilla furnace
     */
    public static XpGain fractional(double amount) {
        int whole = (int) Math.floor(amount);
        return new XpGain(whole, whole, 1.0, amount - whole);
    }

    /**
     * @param random the random source
     * @return the points of one action
     */
    public int roll(RandomSource random) {
        int amount = random.nextDouble() < chance ? random.nextIntBetweenInclusive(min, max) : 0;
        return amount + (random.nextDouble() < fraction ? 1 : 0);
    }

    /**
     * @return the average points of one action
     */
    public double expected() {
        return chance * (min + max) / 2.0 + fraction;
    }
}
