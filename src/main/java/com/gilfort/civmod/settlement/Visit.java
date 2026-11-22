package com.gilfort.civmod.settlement;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * One stay at an inn (vault {@code Systems/NPC Population.md}): everything the settlement keeps about a visitor. The entity is the
 * visitor's body and is found by {@link #id()}; when the stay ends, the record goes and the entity with it, loaded or not.
 *
 * @param id        the UUID of the visitor entity
 * @param pantry    the Pantry of the inn the visitor came to
 * @param bed       the guest bed (head position) it sleeps in, empty once the bed was unbound or broken
 * @param leaveSlot the arrival check ({@link InnSlots}) at which the visitor leaves
 * @param asked     whether the player has asked it to stay longer (only once, and then it says goodbye with a note)
 */
public record Visit(UUID id, BlockPos pantry, Optional<BlockPos> bed, long leaveSlot, boolean asked) {

    /** Save codec. */
    public static final Codec<Visit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Visit::id),
            BlockPos.CODEC.fieldOf("pantry").forGetter(Visit::pantry),
            BlockPos.CODEC.optionalFieldOf("bed").forGetter(Visit::bed),
            Codec.LONG.fieldOf("leave_slot").forGetter(Visit::leaveSlot),
            Codec.BOOL.optionalFieldOf("asked", false).forGetter(Visit::asked)
    ).apply(instance, Visit::new));

    /**
     * @param newLeaveSlot the check at which the visitor leaves now
     * @return this visit with that departure
     */
    public Visit leavingAt(long newLeaveSlot) {
        return new Visit(id, pantry, bed, newLeaveSlot, asked);
    }

    /**
     * @return this visit after the player asked the visitor to stay longer
     */
    public Visit askedToStay() {
        return new Visit(id, pantry, bed, leaveSlot, true);
    }

    /**
     * @param newBed its guest bed now, empty for none
     * @return this visit with that bed
     */
    public Visit sleepingIn(Optional<BlockPos> newBed) {
        return new Visit(id, pantry, newBed, leaveSlot, asked);
    }
}
