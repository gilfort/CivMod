package com.gilfort.civmod.origin;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * The relative a trade surname comes from.
 */
public enum Ancestor implements StringRepresentable {
    /** The father; only when the trade fits the upbringing. */
    FATHER("father"),
    /** The grandfather. */
    GRANDFATHER("grandfather"),
    /** The great-grandfather. */
    GREAT_GRANDFATHER("great_grandfather");

    /** Save codec. */
    public static final Codec<Ancestor> CODEC = StringRepresentable.fromEnum(Ancestor::values);

    private final String serializedName;

    Ancestor(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /**
     * @return the language key of the relative's name
     */
    public String langKey() {
        return "origin.ancestor." + serializedName;
    }
}
