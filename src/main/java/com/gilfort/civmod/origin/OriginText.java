package com.gilfort.civmod.origin;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Builds the texts of an origin from language keys. The server sends the keys and their arguments; every client puts the words
 * together in its own language.
 */
public final class OriginText {

    private OriginText() {
    }

    /**
     * The language key of a table entry: {@code origin.<slot>.<namespace>.<path>}, with dots for slashes. A datapack that adds an
     * entry adds the text under its key.
     *
     * @param slot the slot: {@code upbringing}, {@code turning_point}, {@code reason}, {@code place}, {@code surname.trade},
     *             {@code surname.place}, {@code surname.trait}, {@code surname.clan}, {@code trade} (the trade as a noun) or
     *             {@code job} (what a Job is as the object of "drawn to")
     * @param id   the entry id
     * @return the language key
     */
    public static String key(String slot, Identifier id) {
        return "origin." + slot + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    /**
     * @param name the rolled name
     * @return the name as a translatable text, so it shows in the language of each player; plain first name without a surname
     */
    public static Component name(SettlerName name) {
        SettlerName.Surname surname = name.surname();
        if (surname.type() == SurnameType.NONE) {
            return Component.literal(name.first());
        }
        return Component.translatable("origin.name", name.first(), surname(surname));
    }

    private static Component surname(SettlerName.Surname surname) {
        return switch (surname.type()) {
            case PATRONYMIC -> Component.translatable("origin.surname.patronymic", surname.param());
            case MATRONYMIC -> Component.translatable("origin.surname.matronymic", surname.param());
            case TRADE -> idKey("surname.trade", surname.param());
            case PLACE -> idKey("surname.place", surname.param());
            case TRAIT -> idKey("surname.trait", surname.param());
            case CLAN -> idKey("surname.clan", surname.param());
            case NONE -> Component.empty();
        };
    }

    /** The translatable text of an entry id given as a string; the plain string if it is no id. */
    static Component idKey(String slot, String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? Component.literal(id) : Component.translatable(key(slot, parsed));
    }

    /**
     * The words of a place with its journey, such as "the steppes to the south, 2 days on foot".
     *
     * @param place the place
     * @return the text
     */
    public static Component place(Place place) {
        return Component.translatable(key("place", place.id()), journey(place));
    }

    private static Component journey(Place place) {
        if (place.kind() != Place.Kind.BIOME) {
            return Component.empty();
        }
        return place.direction().map(direction -> Component.translatable("origin.journey", Component.translatable(direction.langKey()),
                Component.translatable(place.days() == 1 ? "origin.days.one" : "origin.days.many", place.days())))
                .orElseGet(() -> Component.translatable("origin.journey.unknown"));
    }
}
