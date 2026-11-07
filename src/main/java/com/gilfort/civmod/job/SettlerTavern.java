package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.entertainment.TavernSeats;
import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * The Tavern evening of one settler (vault {@code Systems/Entertainment.md}, {@code Systems/Sitting.md}): books a seat at
 * the nearest Pantry whose Tavern Host is on shift, walks to it and sits down. Like {@link SettlerRest} it only says what
 * it wants as a {@link BodyStep}; the Settler Shell walks the settler and changes its activity.
 * <p>
 * The booking is the seat's reservation: it lasts for one evening, belongs to this settler alone and is checked against
 * the other settlers' bookings, so a seat is never shared. Nothing is saved: after a reload the settler books again at its
 * next decision. Nobody rides the seat; the settler sits in the pose of {@link Settler#sitDown}.
 */
final class SettlerTavern {

    private static final double REACH = 2.0;

    /** A seat booked for an evening. */
    private record Booking(String seat, BlockPos pantry, long day) {
    }

    private final Settler settler;
    private @Nullable Booking booking;
    /** The evening on which the settler lost its seat (taken, removed, host gone) and goes home instead. */
    private long lostDay = Long.MIN_VALUE;

    SettlerTavern(Settler settler) {
        this.settler = settler;
    }

    /**
     * @param day the day of an evening
     * @return the key of the seat this settler has booked that evening
     */
    Optional<String> bookedSeat(long day) {
        Booking current = booking;
        return current != null && current.day() == day ? Optional.of(current.seat()) : Optional.empty();
    }

    /**
     * The Schedule's question: does this settler spend the evening in the Tavern? Yes if it holds a seat that is still
     * there and free of players; it keeps the seat until bedtime (the Schedule stops asking at night), also after the
     * Tavern Host's shift ended. Otherwise, in the Tavern evening and if its Entertainment is not full, it books the free
     * seat nearest to it at the nearest Pantry with a Tavern Host on shift. A settler that lost its seat goes home for the
     * rest of the evening.
     *
     * @param level   the level
     * @param day     the day
     * @param dayTime the day time
     * @return whether the settler goes to the Tavern
     */
    boolean attends(ServerLevel level, long day, long dayTime) {
        if (lostDay == day) {
            return false;
        }
        Booking current = booking;
        if (current != null && current.day() == day) {
            if (heldSeat(level).isPresent()) {
                return true;
            }
            booking = null;
            lostDay = day;
            return false;
        }
        booking = null;
        if (!Tavern.isEvening(dayTime) || settler.getWork().entertainment().value() >= EntertainmentRules.MAX) {
            return false;
        }
        return SettlementData.get(level).settlement(settler.getSettlementId())
                .map(settlement -> book(level, settlement, EntertainmentRules.get(), day, dayTime)).orElse(false);
    }

    /** Books the free seat nearest to the settler at the nearest Pantry with a Tavern Host on shift and a free seat. */
    private boolean book(ServerLevel level, Settlement settlement, EntertainmentRules rules, long day, long dayTime) {
        Set<String> booked = null;
        for (SettlementBlock pantry : settlement.blocks().stream().filter(ClerkStorage::isPantry)
                .sorted(Comparator.comparingDouble((SettlementBlock block) -> block.pos().distSqr(settler.blockPosition()))).toList()) {
            if (!level.isLoaded(pantry.pos()) || !ClerkStorage.working(pantry, day, dayTime)) {
                continue;
            }
            if (booked == null) {
                booked = bookedByOthers(level, settlement, day);
            }
            Set<String> taken = booked;
            Optional<TavernSeats.Seat> seat = TavernSeats.around(level, pantry.pos(), rules.tavernRadius()).stream()
                    .filter(found -> !taken.contains(found.key()) && !TavernSeats.occupied(level, found))
                    .min(Comparator.comparingDouble((TavernSeats.Seat found) -> found.position().distanceToSqr(settler.position())));
            if (seat.isPresent()) {
                booking = new Booking(seat.get().key(), pantry.pos(), day);
                return true;
            }
        }
        return false;
    }

    /** The seats the other loaded settlers of the settlement have booked for the evening. */
    private Set<String> bookedByOthers(ServerLevel level, Settlement settlement, long day) {
        Set<String> seats = new HashSet<>();
        for (UUID member : settlement.members()) {
            Entity entity = level.getEntity(member);
            if (entity instanceof Settler other && other != settler) {
                other.getWork().tavernSeat(day).ifPresent(seats::add);
            }
        }
        return seats;
    }

    /** The booked seat as it is now, empty if there is no booking, the seat is gone or somebody rides it. */
    private Optional<TavernSeats.Seat> heldSeat(ServerLevel level) {
        Booking current = booking;
        if (current == null) {
            return Optional.empty();
        }
        return TavernSeats.find(level, current.pantry(), EntertainmentRules.get().tavernRadius(), current.seat())
                .filter(found -> !TavernSeats.occupied(level, found));
    }

    /**
     * Walks to the booked seat; once the settler sits there it stays.
     *
     * @param level the level
     * @return walk to the seat, stay when seated, or stand still if the seat is gone
     */
    BodyStep evening(ServerLevel level) {
        Optional<TavernSeats.Seat> seat = heldSeat(level);
        if (seat.isEmpty()) {
            return BodyStep.STOP;
        }
        return settler.isSitting() ? BodyStep.STAY : new BodyStep.Walk(Activity.TAVERN, seat.get().walkTo(), REACH);
    }

    /**
     * The settler reached its seat: it sits down there, facing the way of the seat.
     *
     * @param level the level
     * @return stay (sitting), or stand still if the seat is gone
     */
    BodyStep arrived(ServerLevel level) {
        Optional<TavernSeats.Seat> seat = heldSeat(level);
        if (seat.isEmpty()) {
            return BodyStep.STOP;
        }
        if (!settler.isSitting()) {
            settler.sitDown(seat.get().position(), seat.get().facing());
        }
        return BodyStep.STAY;
    }

    /**
     * @param activity the settler's activity
     * @return whether the settler sits at its seat: the pose is kept, the work break rule does not stand it up
     */
    boolean sitsAtSeat(Activity activity) {
        return activity == Activity.TAVERN && settler.isSitting();
    }
}
