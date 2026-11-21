package com.gilfort.civmod.api;

import java.util.List;
import java.util.function.Function;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.chat.Component;

/**
 * A kind of diary entry a settler can write (vault {@code Systems/Chronicle.md}). An entry saves only its type id, a list of
 * text parameters and the day; the words are built from the type when somebody reads it, in the language of the reader. Register
 * a type in the {@code civmod:diary_entry_type} registry ({@link CivModApi#diaryEntryTypes}) and add entries with
 * {@link CivModApi#addDiaryEntry}.
 *
 * @param text          builds the first-person text of an entry from its parameters; it runs on the client, so it must not touch the
 *                      world
 * @param beforeArrival whether the entry is about the time before the settler arrived (the Chronicle tab lists it under "Before
 *                      day X")
 * @param permanent     whether the entry stays when the diary is full; the oldest entry that is not permanent is dropped instead
 */
@ApiStatus.Experimental
public record DiaryEntryType(Function<List<String>, Component> text, boolean beforeArrival, boolean permanent) {

    /**
     * @param langKey the language key of the entry text; the parameters are its arguments, in order
     * @return a type that dates from the settler's time here and drops out of a full diary when it is old
     */
    public static DiaryEntryType of(String langKey) {
        return new DiaryEntryType(params -> Component.translatable(langKey, params.toArray()), false, false);
    }

    /**
     * @return this type, listed under "Before day X"
     */
    public DiaryEntryType asBeforeArrival() {
        return new DiaryEntryType(text, true, permanent);
    }

    /**
     * @return this type, never dropped from a full diary
     */
    public DiaryEntryType asPermanent() {
        return new DiaryEntryType(text, beforeArrival, true);
    }
}
