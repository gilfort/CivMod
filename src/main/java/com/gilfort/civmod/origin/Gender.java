package com.gilfort.civmod.origin;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * The gender of a settler, rolled at spawn (vault {@code Systems/Settler Origin.md}). Only the names use it for now; skins are
 * independent of it.
 */
public enum Gender implements StringRepresentable {
    /** Male: may carry a patronymic surname. */
    MALE("male"),
    /** Female: may carry a matronymic surname. */
    FEMALE("female");

    /** Save codec. */
    public static final Codec<Gender> CODEC = StringRepresentable.fromEnum(Gender::values);

    private final String serializedName;

    Gender(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
