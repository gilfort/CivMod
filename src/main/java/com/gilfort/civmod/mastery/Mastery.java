package com.gilfort.civmod.mastery;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.mastery.MasterworkRules.Effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The Mastery of one settler (vault {@code Systems/Producing Jobs.md}): the Producing loop asks once per finished craft, after
 * it took the inputs out of the buffer. A Job with a parent in the Job API rolls ({@link MasteryRules#hits}); what a hit
 * gives depends on the recipe: gear (a result with durability) becomes a masterwork, a recipe with enough ingredient items
 * saves some of them, a smaller one makes extra output. A {@link ReversibleRecipes reversible} recipe gets neither saving
 * nor extra output. The loop calls {@link #roll} once before the remainders and {@link Boost#finish} or
 * {@link Boost#finishLive} once after the craft made its stacks; the order of the steps inside is kept here.
 */
public final class Mastery {

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
    public enum Benefit {
        /** No hit, or a recipe Mastery does not touch. */
        NONE,
        /** Some of the inputs were not used up. */
        SAVING,
        /** The recipe's result comes out once more per hit. */
        EXTRA_OUTPUT,
        /** The result has durability: the finished item becomes a masterwork. */
        GEAR
    }

    /**
     * The outcome of the roll of one craft. Finish it after the recipe made its stacks and before the buffer takes them.
     *
     * @param benefit what the hits turned into
     * @param hits    how many hits the craft had (0 to the maximum of {@link MasteryRules})
     * @param saved   how many ingredient items the craft saves; 0 unless the benefit is {@link Benefit#SAVING}
     * @param effects the effects of a masterwork, one per hit; empty unless the benefit is {@link Benefit#GEAR}
     */
    public record Boost(Benefit benefit, int hits, int saved, List<Effect> effects) {

        /** No hit. */
        public static final Boost NONE = new Boost(Benefit.NONE, 0, 0, List.of());

        /**
         * Finishes the craft with nobody watching (catch-up): gear becomes a masterwork (a masterwork upgraded by the craft
         * keeps its bonuses, with a hit it gets more), nothing is shown.
         *
         * @param settler  the settler who crafted
         * @param produced what the craft made, the result first; gear in it is changed in place
         * @param result   what one execution makes
         * @return what the buffer takes: the produced stacks, then the extra output if the craft makes some
         */
        public List<ItemStack> finish(LivingEntity settler, List<ItemStack> produced, ItemStack result) {
            makeMasterwork(settler, produced);
            return withExtraOutput(produced, result);
        }

        /**
         * Finishes the craft in live play, where a player can watch: like {@link #finish}, and a masterwork plays its sound,
         * particles and message while a saving or an extra output shows particles.
         *
         * @param level    the level
         * @param settler  the settler who crafted
         * @param produced what the craft made, the result first; gear in it is changed in place
         * @param result   what one execution makes
         * @return what the buffer takes: the produced stacks, then the extra output if the craft makes some
         */
        public List<ItemStack> finishLive(ServerLevel level, LivingEntity settler, List<ItemStack> produced, ItemStack result) {
            makeMasterwork(settler, produced).ifPresent(stack -> MasterworkNotice.show(level, level.players(), settler, stack));
            if (benefit == Benefit.SAVING || benefit == Benefit.EXTRA_OUTPUT) {
                MasterworkNotice.showBoost(level, settler);
            }
            return withExtraOutput(produced, result);
        }

        private Optional<ItemStack> makeMasterwork(LivingEntity settler, List<ItemStack> produced) {
            // A smithing upgrade keeps the masterwork of its base: work its bonuses out over the new item, with or without a hit.
            produced.forEach(Masterwork::refresh);
            if (benefit != Benefit.GEAR) {
                return Optional.empty();
            }
            Optional<ItemStack> gear = produced.stream().filter(ItemStack::isDamageableItem).findFirst();
            gear.ifPresent(stack -> Masterwork.turnInto(stack, effects, settler.getName().getString()));
            return gear;
        }

        private List<ItemStack> withExtraOutput(List<ItemStack> produced, ItemStack result) {
            if (benefit != Benefit.EXTRA_OUTPUT) {
                return produced;
            }
            return Stream.concat(produced.stream(), Stream.generate(result::copy).limit(hits)).toList();
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
     * Rolls for one craft and, on a saving, puts the saved items back into the buffer: they are not used up, so they also leave
     * no remainder (a saved milk bucket makes no empty bucket). Call it after the craft took its inputs and before their
     * remainders are worked out, so that what is left in {@code consumed} is what the craft used up.
     *
     * @param level    the level
     * @param craft    the craft
     * @param consumed the inputs the craft took out of the buffer, one stack per ingredient item; the saved ones leave it
     * @param buffer   the buffer the inputs came from
     * @return what the hits turned into, to finish once the craft made its stacks
     */
    public Boost roll(ServerLevel level, Craft craft, List<ItemStack> consumed, JobBuffer buffer) {
        RandomSource source = scripted != null ? scripted : random;
        Boost boost = decide(craft, consumed.size(), source, () -> ReversibleRecipes.contains(level, craft.pattern().recipe().orElseThrow()));
        for (int left = boost.saved(); left > 0; left--) {
            buffer.add(consumed.remove(source.nextInt(consumed.size())));
        }
        return boost;
    }

    /**
     * The decision of one craft: it touches neither the buffer nor the world.
     *
     * @param craft       the craft
     * @param ingredients the ingredient items the craft took (counts included)
     * @param random      the random source of the rolls and the effect draws
     * @param reversible  whether the craft's recipe is reversible; asked only when a hit could save or add material
     * @return what the hits turned into
     */
    static Boost decide(Craft craft, int ingredients, RandomSource random, BooleanSupplier reversible) {
        if (craft.job().parent().isEmpty() || craft.drawsOnly() || craft.pattern().recipe().isEmpty()) {
            return Boost.NONE;
        }
        MasteryRules rules = MasteryRules.get();
        int hits = rules.hits(craft.skill(), random);
        if (hits == 0) {
            return Boost.NONE;
        }
        ItemStack gear = craft.pattern().output().toStack();
        if (gear.isDamageableItem()) {
            return new Boost(Benefit.GEAR, hits, 0, MasterworkRules.get().draw(gear, hits, random));
        }
        if (reversible.getAsBoolean()) {
            return Boost.NONE;
        }
        if (!rules.savesMaterial(ingredients)) {
            return new Boost(Benefit.EXTRA_OUTPUT, hits, 0, List.of());
        }
        return new Boost(Benefit.SAVING, hits, rules.saved(ingredients, hits), List.of());
    }
}
