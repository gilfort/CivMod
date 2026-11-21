package com.gilfort.civmod.origin;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The parts of a rolled settler name: a first name and a surname. The name shown is built from these as a translatable text
 * ({@link OriginText#name}), so only the parts are saved.
 *
 * @param first   the first name from the race's list
 * @param surname how the surname came to be
 */
public record SettlerName(String first, Surname surname) {

    /** Save codec. */
    public static final Codec<SettlerName> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("first").forGetter(SettlerName::first),
            Surname.CODEC.fieldOf("surname").forGetter(SettlerName::surname)
    ).apply(instance, SettlerName::new));

    /**
     * The surname and what the upbringing line needs to tell where it comes from.
     *
     * @param type     the rule type that produced it
     * @param param    the father's or mother's first name (patronymic, matronymic), otherwise the id of the trade, place, trait or
     *                 clan; empty for {@link SurnameType#NONE}
     * @param ancestor the relative a trade comes from, empty for every other type
     */
    public record Surname(SurnameType type, String param, Optional<Ancestor> ancestor) {

        /** The settler has no surname. */
        public static final Surname NONE = new Surname(SurnameType.NONE, "", Optional.empty());

        /** Save codec. */
        public static final Codec<Surname> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                SurnameType.CODEC.fieldOf("type").forGetter(Surname::type),
                Codec.STRING.optionalFieldOf("param", "").forGetter(Surname::param),
                Ancestor.CODEC.optionalFieldOf("ancestor").forGetter(Surname::ancestor)
        ).apply(instance, Surname::new));
    }
}
