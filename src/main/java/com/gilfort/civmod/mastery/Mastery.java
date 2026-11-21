package com.gilfort.civmod.mastery;

import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.logistics.Pattern;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The Mastery of one settler (vault {@code Systems/Producing Jobs.md}): the Producing loop asks once per finished craft, after
 * it took the inputs out of the buffer. A Job with a parent in the Job API rolls ({@link MasteryRules#hits}); what a hit
 * gives depends on the recipe: gear (a result with durability) becomes a masterwork, a recipe with enough ingredient items
 * saves some of them, a smaller one makes extra output. A {@link ReversibleRecipes reversible} recipe gets neither saving
 * nor extra output. The roll lives in this class so the loop only has to call it.
 */
public final class Mastery {

    /** Particles of one saving or extra output. */
    private static final int PARTICLE_COUNT = 5;
    /** How far the particles spread from the settler's chest, in blocks. */
    private static final double PARTICLE_SPREAD = 0.3;

    private final RandomSource random;
    private @Nullable RandomSource scripted;

    /**
     * @param random the settler's random source, used unless a test scripted another one
     */
    public Mastery(RandomSource random) {
        this.random = random;
    }

    /**
     * What a craft is, as far as Mastery needs it.
     *
     * @param job        the Job the settler crafts as
     * @param skill      the settler's effective skill level in the Job
     * @param pattern    the pattern the craft follows
     * @param drawsOnly  whether the recipe only draws (no result of its own to improve, like the Sifter's)
     */
    public record Craft(Job job, int skill, Pattern pattern, boolean drawsOnly) {
    }

    /** What a hit turned into. */
    public enum Kind {
        /** No hit, or a recipe Mastery does not touch. */
        NONE,
        /** Some of the inputs were not used up. */
        SAVING,
        /** The recipe's result comes out once more per hit. */
        EXTRA_OUTPUT,
        /** The result has durability: a hit is a masterwork (not made yet, see #303). */
        GEAR
    }

    /**
     * The outcome of the roll of one craft.
     *
     * @param kind what the hits turned into
     * @param hits how many hits the craft had (0 to the maximum of {@link MasteryRules})
     */
    public record Boost(Kind kind, int hits) {

        /** No hit. */
        public static final Boost NONE = new Boost(Kind.NONE, 0);

        /**
         * @param result what one execution makes
         * @return the extra output: the result once more per hit if the craft makes extra output, else nothing
         */
        public List<ItemStack> extraOutput(ItemStack result) {
            return kind == Kind.EXTRA_OUTPUT ? Stream.generate(result::copy).limit(hits).toList() : List.of();
        }

        /**
         * Shows a saving or extra output as a few particles at the settler, no message. Nothing for a craft without one.
         *
         * @param level   the level
         * @param settler the settler
         */
        public void show(ServerLevel level, LivingEntity settler) {
            if (kind == Kind.SAVING || kind == Kind.EXTRA_OUTPUT) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, settler.getX(), settler.getY() + settler.getBbHeight() * 0.75, settler.getZ(),
                        PARTICLE_COUNT, PARTICLE_SPREAD, PARTICLE_SPREAD, PARTICLE_SPREAD, 0.0);
            }
        }
    }

    /**
     * Replaces the random source of the roll (tests).
     *
     * @param scripted the source to roll with, null for the settler's own
     */
    @ApiStatus.Internal
    public void script(@Nullable RandomSource scripted) {
        this.scripted = scripted;
    }

    /**
     * Rolls for one craft. On a saving the saved items leave {@code consumed} and go back into the buffer: they are not
     * used up, so they also leave no remainder (a saved milk bucket makes no empty bucket).
     *
     * @param level    the level
     * @param craft    the craft
     * @param consumed the inputs the craft took out of the buffer, one stack per ingredient item
     * @param buffer   the buffer the inputs came from
     * @return what the hits turned into
     */
    public Boost apply(ServerLevel level, Craft craft, List<ItemStack> consumed, JobBuffer buffer) {
        if (craft.job().parent().isEmpty() || craft.drawsOnly() || craft.pattern().recipe().isEmpty()) {
            return Boost.NONE;
        }
        MasteryRules rules = MasteryRules.get();
        RandomSource source = scripted != null ? scripted : random;
        int hits = rules.hits(craft.skill(), source);
        if (hits == 0) {
            return Boost.NONE;
        }
        if (craft.pattern().output().toStack().isDamageableItem()) {
            return new Boost(Kind.GEAR, hits);
        }
        if (ReversibleRecipes.contains(level, craft.pattern().recipe().get())) {
            return Boost.NONE;
        }
        if (!rules.savesMaterial(consumed.size())) {
            return new Boost(Kind.EXTRA_OUTPUT, hits);
        }
        for (int left = rules.saved(consumed.size(), hits); left > 0; left--) {
            buffer.add(consumed.remove(source.nextInt(consumed.size())));
        }
        return new Boost(Kind.SAVING, hits);
    }
}
