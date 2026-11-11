package com.gilfort.civmod.job;

/** A Fisherman JobBlock's treasure option, handed to the catch roll (vault {@code Jobs/Fisherman.md}). */
public enum TreasureOption {
    /** The roll may give treasure. */
    ALLOWED,
    /** The treasure chance is not rolled and every catch is a fish. */
    FORBIDDEN;

    /**
     * @param enabled the JobBlock's treasure setting
     * @return the option it stands for
     */
    public static TreasureOption of(boolean enabled) {
        return enabled ? ALLOWED : FORBIDDEN;
    }
}
