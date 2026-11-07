package com.gilfort.civmod.job;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.api.ServiceAct;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The numbers a datapack changes of one {@link ServiceAct} ({@code service_acts} in a Job's
 * {@code civmod_jobs/<job>.json}, keyed by the act's id): its window, its radius and the numbers of its effects. Anything
 * not set keeps the profile's value. The effects themselves are never listed in a datapack: {@code effects} holds one
 * entry per effect of the act, in the order of the profile, each with the numbers to change by name
 * ({@link com.gilfort.civmod.api.ServiceEffect#numbers()}).
 *
 * @param start   the first tick of the window
 * @param end     the tick at which the window ends
 * @param radius  the listener radius in blocks
 * @param effects the numbers of each effect, in order (as many entries as the act has effects, an empty one for an effect
 *                that stays as it is)
 */
public record ServiceActNumbers(Optional<Integer> start, Optional<Integer> end, Optional<Double> radius, Optional<List<Map<String, Double>>> effects) {

    /** Nothing changed. */
    public static final ServiceActNumbers NONE = new ServiceActNumbers(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /** Datapack codec: {@code { "start": ticks, "end": ticks, "radius": blocks, "effects": [ { "<number>": value } ] }}. */
    public static final Codec<ServiceActNumbers> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, ServiceAct.DAY_TICKS).optionalFieldOf("start").forGetter(ServiceActNumbers::start),
            Codec.intRange(0, ServiceAct.DAY_TICKS).optionalFieldOf("end").forGetter(ServiceActNumbers::end),
            Codec.doubleRange(0.0, ServiceAct.MAX_RADIUS).optionalFieldOf("radius").forGetter(ServiceActNumbers::radius),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).listOf().optionalFieldOf("effects").forGetter(ServiceActNumbers::effects)
    ).apply(instance, ServiceActNumbers::new));

    /**
     * @param earlier the numbers of an earlier file
     * @return these numbers with every field they do not set taken from the earlier ones (later files override)
     */
    ServiceActNumbers over(ServiceActNumbers earlier) {
        return new ServiceActNumbers(start.or(earlier::start), end.or(earlier::end), radius.or(earlier::radius), effects.or(earlier::effects));
    }
}
