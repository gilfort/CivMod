package com.gilfort.civmod.origin;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * The surname rule types the code knows (vault {@code Systems/Settler Origin.md}). A race picks its types with weights in its
 * data; the types themselves are not extensible by datapacks.
 */
public enum SurnameType implements StringRepresentable {
    /** The father's first name plus a suffix; men only. */
    PATRONYMIC("patronymic", Gender.MALE),
    /** The mother's first name plus a suffix; women only. */
    MATRONYMIC("matronymic", Gender.FEMALE),
    /** The trade of an ancestor. */
    TRADE("trade", null),
    /** The kind of place the settler comes from. */
    PLACE("place", null),
    /** A trait of the settler or an ancestor. */
    TRAIT("trait", null),
    /** A clan name. */
    CLAN("clan", null),
    /** No surname. */
    NONE("none", null);

    /** Codec for data and saves. */
    public static final Codec<SurnameType> CODEC = StringRepresentable.fromEnum(SurnameType::values);

    private final String serializedName;
    private final @Nullable Gender onlyFor;

    SurnameType(String serializedName, @Nullable Gender onlyFor) {
        this.serializedName = serializedName;
        this.onlyFor = onlyFor;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /**
     * @param gender a gender
     * @return whether a settler of that gender may carry this type
     */
    public boolean allows(Gender gender) {
        return onlyFor == null || onlyFor == gender;
    }
}
