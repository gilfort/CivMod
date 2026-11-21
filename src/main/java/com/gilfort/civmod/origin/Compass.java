package com.gilfort.civmod.origin;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * One of the eight compass directions a place lies in. The text of a place names it, never coordinates.
 */
public enum Compass implements StringRepresentable {
    /** Towards negative Z. */
    NORTH("north"),
    /** North and east. */
    NORTH_EAST("north_east"),
    /** Towards positive X. */
    EAST("east"),
    /** South and east. */
    SOUTH_EAST("south_east"),
    /** Towards positive Z. */
    SOUTH("south"),
    /** South and west. */
    SOUTH_WEST("south_west"),
    /** Towards negative X. */
    WEST("west"),
    /** North and west. */
    NORTH_WEST("north_west");

    /** Save codec. */
    public static final Codec<Compass> CODEC = StringRepresentable.fromEnum(Compass::values);

    private static final double SECTOR_DEGREES = 45.0;
    private static final double FULL_CIRCLE_DEGREES = 360.0;

    private final String serializedName;

    Compass(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /**
     * @return the language key of the direction's name
     */
    public String langKey() {
        return "origin.direction." + serializedName;
    }

    /**
     * @param dx blocks to the east (negative: west)
     * @param dz blocks to the south (negative: north)
     * @return the direction the offset points in
     */
    public static Compass of(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        double clockwise = (degrees + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES;
        int sector = (int) Math.round(clockwise / SECTOR_DEGREES) % values().length;
        return values()[sector];
    }
}
