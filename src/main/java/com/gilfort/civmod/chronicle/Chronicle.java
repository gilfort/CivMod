package com.gilfort.civmod.chronicle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.gilfort.civmod.origin.OriginLine;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * What a settler remembers of its story (vault {@code Systems/Chronicle.md}): the diary, the origin lines it has told and whether
 * its talent is confirmed. The flags stay outside the diary because a full diary drops old entries and the flags must not drop
 * with them. The record is immutable, so a settler's chronicle moves to another settler as one value.
 *
 * @param entries         the diary, oldest first, at most {@link #CAP}
 * @param told            the origin lines the settler has told in a talk, in the order told
 * @param talentConfirmed whether the first level-up in a favoured Job has confirmed the talent
 * @param storySpentDay   the last day on which the settler's chance to share a story aspect is used up (it told one that day, or it was
 *                        hired as a visitor that day), {@link #NO_DAY} if none
 */
public record Chronicle(List<DiaryEntry> entries, List<OriginLine.Slot> told, boolean talentConfirmed, long storySpentDay) {

    /** The most entries a diary holds. */
    public static final int CAP = 20;

    /** The day mark of a settler whose story chance was never used up. */
    public static final long NO_DAY = Long.MIN_VALUE;

    /** A settler that has no story yet. */
    public static final Chronicle EMPTY = new Chronicle(List.of(), List.of(), false, NO_DAY);

    /** Save codec. */
    public static final Codec<Chronicle> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DiaryEntry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(Chronicle::entries),
            OriginLine.Slot.CODEC.listOf().optionalFieldOf("told", List.of()).forGetter(Chronicle::told),
            Codec.BOOL.optionalFieldOf("talent_confirmed", false).forGetter(Chronicle::talentConfirmed),
            Codec.LONG.optionalFieldOf("story_spent_day", NO_DAY).forGetter(Chronicle::storySpentDay)
    ).apply(instance, Chronicle::new));

    /**
     * @param entries         the diary, oldest first
     * @param told            the origin lines told
     * @param talentConfirmed whether the talent is confirmed
     * @param storySpentDay   the last day the story chance is used up
     */
    public Chronicle {
        entries = List.copyOf(entries);
        told = List.copyOf(told);
    }

    /**
     * Writes an entry at the end of the diary. A full diary first drops its oldest entry that is not permanent; when every entry is
     * permanent the new one is not written.
     *
     * @param entry     the entry
     * @param permanent tells whether an entry type is never dropped
     * @return the chronicle with the entry, or this one if there was no room
     */
    public Chronicle with(DiaryEntry entry, Predicate<Identifier> permanent) {
        List<DiaryEntry> next = new ArrayList<>(entries);
        if (next.size() >= CAP) {
            int droppable = firstDroppable(next, permanent);
            if (droppable < 0) {
                return this;
            }
            next.remove(droppable);
        }
        next.add(entry);
        return new Chronicle(next, told, talentConfirmed, storySpentDay);
    }

    private static int firstDroppable(List<DiaryEntry> diary, Predicate<Identifier> permanent) {
        for (int i = 0; i < diary.size(); i++) {
            if (!permanent.test(diary.get(i).type())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return the chronicle with the talent confirmed
     */
    public Chronicle withTalentConfirmed() {
        return new Chronicle(entries, told, true, storySpentDay);
    }

    /**
     * @param slot an origin line
     * @return whether the settler has told it
     */
    public boolean hasTold(OriginLine.Slot slot) {
        return told.contains(slot);
    }

    /**
     * @param slot an origin line the settler has just told
     * @return the chronicle with the line marked as told
     */
    public Chronicle withTold(OriginLine.Slot slot) {
        List<OriginLine.Slot> next = new ArrayList<>(told);
        if (!next.contains(slot)) {
            next.add(slot);
        }
        return new Chronicle(entries, next, talentConfirmed, storySpentDay);
    }

    /**
     * @param day the day on which the settler's chance to share a story aspect is used up
     * @return the chronicle with that day marked
     */
    public Chronicle withStorySpent(long day) {
        return new Chronicle(entries, told, talentConfirmed, day);
    }
}
