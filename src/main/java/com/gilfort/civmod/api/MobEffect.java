package com.gilfort.civmod.api;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Gives a vanilla mob effect to the listeners: the players within the radius by default. The datapack numbers are
 * {@code duration} (ticks) and {@code amplifier}.
 *
 * @param effect    the vanilla effect
 * @param duration  how long it lasts, in ticks
 * @param amplifier the level of the effect, 0 for level I
 * @param targets   who gets it
 */
@ApiStatus.Experimental
public record MobEffect(Holder<net.minecraft.world.effect.MobEffect> effect, int duration, int amplifier, Targets targets) implements ServiceEffect {

    private static final String DURATION = "duration";
    private static final String AMPLIFIER = "amplifier";

    /**
     * @param effect    the vanilla effect
     * @param duration  how long it lasts, in ticks
     * @param amplifier the level of the effect, 0 for level I
     */
    public MobEffect(Holder<net.minecraft.world.effect.MobEffect> effect, int duration, int amplifier) {
        this(effect, duration, amplifier, Targets.PLAYERS);
    }

    /**
     * @throws IllegalArgumentException if the duration is not positive or the amplifier is negative
     */
    public MobEffect {
        if (duration < 1 || amplifier < 0) {
            throw new IllegalArgumentException("A mob effect needs a positive duration and a non-negative amplifier: " + duration + ", " + amplifier);
        }
    }

    @Override
    public void apply(ServiceContext context) {
        for (ServerPlayer player : context.players()) {
            give(player);
        }
        for (Mob settler : context.settlers()) {
            give(settler);
        }
    }

    private void give(LivingEntity listener) {
        listener.addEffect(new MobEffectInstance(effect, duration, amplifier));
    }

    @Override
    public Map<String, Double> numbers() {
        return Map.of(DURATION, (double) duration, AMPLIFIER, (double) amplifier);
    }

    @Override
    public ServiceEffect withNumbers(Map<String, Double> values) {
        return new MobEffect(effect, (int) Math.round(values.getOrDefault(DURATION, (double) duration)),
                (int) Math.round(values.getOrDefault(AMPLIFIER, (double) amplifier)), targets);
    }
}
