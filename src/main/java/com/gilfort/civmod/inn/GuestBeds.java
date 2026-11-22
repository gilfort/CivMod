package com.gilfort.civmod.inn;

import java.util.Optional;

import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The guest beds of a Pantry (vault {@code Systems/NPC Population.md}): the player binds a bed to a Pantry with the Scroll of
 * Assignment, the same bed again unbinds it. A Pantry holds {@link InnRules#bedCapacity} beds, all within the Tavern radius of
 * the Pantry; the settlers' bed assignment never hands them out ({@link SettlerBeds#freeBeds}).
 */
public final class GuestBeds {

    /** What clicking a bed with the Scroll bound to a Pantry did. */
    public enum Result {
        /** The bed is a guest bed of the Pantry now. */
        BOUND,
        /** Bound, but the Pantry does not count as an inn: the settlement already has as many inns as the config allows. */
        BOUND_OVER_LIMIT,
        /** The bed was a guest bed of the Pantry and is none now. */
        UNBOUND,
        /** The block is no bed. */
        NOT_A_BED,
        /** The bed lies outside the settlement border. */
        OUTSIDE,
        /** The bed lies beyond the Tavern radius of the Pantry. */
        TOO_FAR,
        /** The Pantry holds as many guest beds as its level allows. */
        FULL,
        /** A settler owns the bed or a converting Villager has it reserved, or it is a guest bed of another Pantry. */
        TAKEN,
        /** The bed is the respawn point of a player. */
        SPAWN_BED,
        /** The Scroll is not bound to a Pantry of a settlement. */
        NO_PANTRY
    }

    private GuestBeds() {
    }

    /**
     * Binds the bed at a position to the Pantry, or unbinds it if it is bound to it already.
     *
     * @param level   the level
     * @param data    the dimension's settlement data
     * @param pantry  the Pantry the Scroll is bound to
     * @param clicked the clicked position (head or foot of a bed)
     * @return what happened
     */
    public static Result toggle(ServerLevel level, SettlementData data, BlockPos pantry, BlockPos clicked) {
        Optional<Settlement> settlement = data.settlementAt(pantry);
        Optional<SettlementBlock> block = settlement.flatMap(found -> found.block(pantry)).filter(ClerkStorage::isPantry);
        if (block.isEmpty()) {
            return Result.NO_PANTRY;
        }
        Optional<BlockPos> head = SettlerBeds.head(level, clicked);
        if (head.isEmpty()) {
            return Result.NOT_A_BED;
        }
        BlockPos bed = head.get();
        Settlement owner = settlement.get();
        if (owner.inn().bedsOf(pantry).contains(bed)) {
            data.updateInn(owner, inn -> inn.withoutBed(bed));
            return Result.UNBOUND;
        }
        Optional<Result> refusal = refusal(level, owner, block.get(), bed);
        if (refusal.isPresent()) {
            return refusal.get();
        }
        data.updateInn(owner, inn -> inn.withBed(pantry, bed));
        Inns.bedBound(level, data, owner);
        return Inns.isInn(owner, pantry) ? Result.BOUND : Result.BOUND_OVER_LIMIT;
    }

    private static Optional<Result> refusal(ServerLevel level, Settlement settlement, SettlementBlock pantry, BlockPos bed) {
        if (settlement.inn().isGuestBed(bed) || settlement.beds().containsKey(bed) || settlement.bedReservations().containsValue(bed)) {
            return Optional.of(Result.TAKEN);
        }
        if (!JobAssignments.insideBorder(settlement, bed)) {
            return Optional.of(Result.OUTSIDE);
        }
        if (SettlerBeds.spawnBeds(level).contains(bed)) {
            return Optional.of(Result.SPAWN_BED);
        }
        double radius = EntertainmentRules.get().tavernRadius();
        if (bed.distSqr(pantry.pos()) > radius * radius) {
            return Optional.of(Result.TOO_FAR);
        }
        if (settlement.inn().bedsOf(pantry.pos()).size() >= InnRules.get().bedCapacity(pantry.level())) {
            return Optional.of(Result.FULL);
        }
        return Optional.empty();
    }
}
