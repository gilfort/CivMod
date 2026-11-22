package com.gilfort.civmod.settlement;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * One visit to an inn (vault {@code Systems/NPC Population.md}): everything the settlement keeps about a visitor. The entity is the
 * visitor's body and is found by {@link #id()}; when the visit ends, the record goes and the entity with it, loaded or not.
 *
 * @param id        the UUID of the visitor entity
 * @param pantry    the Pantry of the inn the visitor came to
 * @param bed       the guest bed (head position) it sleeps in, empty once the bed was unbound or broken
 * @param leaveSlot the arrival check ({@link InnSlots}) at which the visitor leaves
 * @param asked     whether the player has asked it to stay longer (only once, and then it says goodbye with a note)
 * @param fedDay    the day of the evening it last went to the Pantry for its meal, {@link #NO_DAY} if it never did
 * @param noFood    whether its meal found the Pantry without food, so it leaves at the next morning check and says why
 */
public record Visit(UUID id, BlockPos pantry, Optional<BlockPos> bed, long leaveSlot, boolean asked, long fedDay, boolean noFood) {

    /** {@link #fedDay()} of a visitor that has not been to the Pantry for a meal yet. */
    public static final long NO_DAY = Long.MIN_VALUE;

    /** Save codec. */
    public static final Codec<Visit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Visit::id),
            BlockPos.CODEC.fieldOf("pantry").forGetter(Visit::pantry),
            BlockPos.CODEC.optionalFieldOf("bed").forGetter(Visit::bed),
            Codec.LONG.fieldOf("leave_slot").forGetter(Visit::leaveSlot),
            Codec.BOOL.optionalFieldOf("asked", false).forGetter(Visit::asked),
            Codec.LONG.optionalFieldOf("fed_day", NO_DAY).forGetter(Visit::fedDay),
            Codec.BOOL.optionalFieldOf("no_food", false).forGetter(Visit::noFood)
    ).apply(instance, Visit::new));

    /**
     * A visit that has just begun: nobody asked the visitor to stay longer yet, and it has not eaten.
     *
     * @param id        the UUID of the visitor entity
     * @param pantry    the Pantry of the inn
     * @param bed       the guest bed it sleeps in
     * @param leaveSlot the check at which it leaves
     */
    public Visit(UUID id, BlockPos pantry, Optional<BlockPos> bed, long leaveSlot) {
        this(id, pantry, bed, leaveSlot, false, NO_DAY, false);
    }

    /**
     * @param newLeaveSlot the check at which the visitor leaves now
     * @return this visit with that departure
     */
    public Visit leavingAt(long newLeaveSlot) {
        return new Visit(id, pantry, bed, newLeaveSlot, asked, fedDay, noFood);
    }

    /**
     * @return this visit after the player asked the visitor to stay longer
     */
    public Visit askedToStay() {
        return new Visit(id, pantry, bed, leaveSlot, true, fedDay, noFood);
    }

    /**
     * @param newBed its guest bed now, empty for none
     * @return this visit with that bed
     */
    public Visit sleepingIn(Optional<BlockPos> newBed) {
        return new Visit(id, pantry, newBed, leaveSlot, asked, fedDay, noFood);
    }

    /**
     * @param day the day of the evening it went to the Pantry for its meal
     * @return this visit with the meal marked
     */
    public Visit fedOn(long day) {
        return new Visit(id, pantry, bed, leaveSlot, asked, day, noFood);
    }

    /**
     * The meal found the Pantry without food: the visitor goes on at the next morning check at the latest.
     *
     * @param morning the next morning check
     * @return this visit with the early departure and the reason
     */
    public Visit sentAway(long morning) {
        return new Visit(id, pantry, bed, Math.min(leaveSlot, morning), asked, fedDay, true);
    }
}
