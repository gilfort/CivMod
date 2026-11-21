package com.gilfort.civmod.origin;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * The past of a settler (vault {@code Systems/Settler Origin.md}): only ids, name parts and the rescuer are kept; every text is
 * built from language keys. Settlers from before the origins and settlers built by hand in tests have none.
 *
 * @param gender       the gender
 * @param name         the rolled name, empty for a Villager that kept its own name
 * @param place        where the settler comes from
 * @param upbringing   the upbringing entry, empty if the race has none
 * @param turningPoint the turning point entry, empty if none fits
 * @param reason       the reason entry of the arrival channel, empty if the channel has none
 * @param rescuer      who took the settler in, empty if nobody is recorded
 */
public record SettlerOrigin(Gender gender, Optional<SettlerName> name, Place place, Optional<Identifier> upbringing,
        Optional<Identifier> turningPoint, Optional<Identifier> reason, Optional<Rescuer> rescuer) {

    /** Save codec. */
    public static final Codec<SettlerOrigin> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Gender.CODEC.fieldOf("gender").forGetter(SettlerOrigin::gender),
            SettlerName.CODEC.optionalFieldOf("name").forGetter(SettlerOrigin::name),
            Place.CODEC.fieldOf("place").forGetter(SettlerOrigin::place),
            Identifier.CODEC.optionalFieldOf("upbringing").forGetter(SettlerOrigin::upbringing),
            Identifier.CODEC.optionalFieldOf("turning_point").forGetter(SettlerOrigin::turningPoint),
            Identifier.CODEC.optionalFieldOf("reason").forGetter(SettlerOrigin::reason),
            Rescuer.CODEC.optionalFieldOf("rescuer").forGetter(SettlerOrigin::rescuer)
    ).apply(instance, SettlerOrigin::new));

    /**
     * @param rescuer who took the settler in
     * @return this origin with that rescuer
     */
    public SettlerOrigin withRescuer(Optional<Rescuer> rescuer) {
        return new SettlerOrigin(gender, name, place, upbringing, turningPoint, reason, rescuer);
    }
}
