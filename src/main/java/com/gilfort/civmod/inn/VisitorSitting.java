package com.gilfort.civmod.inn;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.entertainment.TavernSeats;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * How a visitor sits around in the Tavern by day (vault {@code Systems/NPC Population.md}, "The visitor's day"). By day it goes
 * round in cycles of strolling and sitting: it strolls for 30 to 90 s, then looks once for a free seat within the Tavern radius of its
 * Pantry, walks there and sits for 60 to 180 s. With no free seat, or without its Pantry, it starts the next stroll at once. A
 * visitor books nothing: it needs no Tavern Host on shift, a seat only has to be free of players and of the other visitors of the
 * Pantry, and the settlers book seats from 17:30, when {@link VisitorDay} has stood the visitor up.
 * <p>
 * The cycle runs on the decision steps of the visitor ({@link SettlerWork#DECISION_INTERVAL} ticks, 5 seconds), so a conversation
 * that holds its decisions also holds the cycle. The seat search is the only work beyond a step, once per cycle. Nothing is saved:
 * after a reload the visitor starts with a stroll.
 */
final class VisitorSitting {

    private static final int TICKS_PER_SECOND = 20;
    private static final int STROLL_MIN_SECONDS = 30;
    private static final int STROLL_MAX_SECONDS = 90;
    private static final int SIT_MIN_SECONDS = 60;
    private static final int SIT_MAX_SECONDS = 180;
    /** How close a visitor has to come to its seat before it sits down (horizontal, in blocks), like a settler. */
    static final double SEAT_REACH = 2.0;
    /** The stroll has not begun: the visitor has not decided by day yet. */
    private static final int NOT_BEGUN = -1;

    /** Where a visitor is in its cycle. */
    private enum Phase {
        STROLLING,
        /** On its way to the seat it found. */
        WALKING,
        SITTING
    }

    private final Settler visitor;
    private Phase phase = Phase.STROLLING;
    /** The decision steps until the stroll ends (strolling) or the sitting ends (sitting). */
    private int stepsLeft = NOT_BEGUN;
    /** The key of the seat the visitor walks to or sits on. */
    private @Nullable String seat;

    VisitorSitting(Settler visitor) {
        this.visitor = visitor;
    }

    /**
     * @return whether the stroll goal may start a stroll: the visitor is neither on its way to a seat nor sitting
     */
    boolean strolls() {
        return phase == Phase.STROLLING;
    }

    /**
     * @return the key of the seat the visitor walks to or sits on, empty while it strolls
     */
    Optional<String> heldSeat() {
        return phase == Phase.STROLLING ? Optional.empty() : Optional.ofNullable(seat);
    }

    /**
     * One decision step by day: moves the cycle on.
     *
     * @param level      the level
     * @param settlement the settlement of the inn the visitor visits
     * @param visit      the visit of the visitor
     * @return the block to walk to if the visitor is on its way to a seat, empty while it strolls or sits
     */
    Optional<BlockPos> dayStep(ServerLevel level, Settlement settlement, Visit visit) {
        boolean pantryStands = ClerkStorage.pantryAt(settlement, visit.pantry()).isPresent();
        return switch (phase) {
            case STROLLING -> strollStep(level, settlement, visit, pantryStands);
            case WALKING -> walkStep(level, visit, pantryStands);
            case SITTING -> {
                sitStep(level, visit, pantryStands);
                yield Optional.empty();
            }
        };
    }

    /**
     * The visitor stands within reach of its seat: it sits down there, looking the way of the seat.
     *
     * @param level the level
     * @param visit the visit of the visitor
     */
    void sitDown(ServerLevel level, Visit visit) {
        findHeld(level, visit).ifPresent(found -> {
            visitor.sitDown(found.position(), found.facing());
            phase = Phase.SITTING;
            stepsLeft = rolledSteps(level, SIT_MIN_SECONDS, SIT_MAX_SECONDS);
        });
    }

    /**
     * The visitor is no longer in the part of the day for sitting (evening, night): it stands up, and the next day starts with a stroll.
     */
    void standDown() {
        visitor.standUp();
        phase = Phase.STROLLING;
        stepsLeft = NOT_BEGUN;
        seat = null;
    }

    private Optional<BlockPos> strollStep(ServerLevel level, Settlement settlement, Visit visit, boolean pantryStands) {
        if (stepsLeft == NOT_BEGUN) {
            beginStroll(level);
            return Optional.empty();
        }
        if (--stepsLeft > 0) {
            return Optional.empty();
        }
        Optional<TavernSeats.Seat> found = pantryStands ? freeSeat(level, settlement, visit) : Optional.empty();
        if (found.isEmpty()) {
            beginStroll(level);
            return Optional.empty();
        }
        seat = found.get().key();
        phase = Phase.WALKING;
        return Optional.of(found.get().walkTo());
    }

    private Optional<BlockPos> walkStep(ServerLevel level, Visit visit, boolean pantryStands) {
        Optional<TavernSeats.Seat> held = pantryStands ? findHeld(level, visit) : Optional.empty();
        if (held.isEmpty()) {
            beginStroll(level);
            return Optional.empty();
        }
        return Optional.of(held.get().walkTo());
    }

    /** Stands the visitor up when its time is over, its seat or Pantry is gone, or something stood it up (a talk, a hit). */
    private void sitStep(ServerLevel level, Visit visit, boolean pantryStands) {
        boolean keepsSitting = visitor.isSitting() && pantryStands && findHeld(level, visit).isPresent() && --stepsLeft > 0;
        if (!keepsSitting) {
            visitor.standUp();
            beginStroll(level);
        }
    }

    private void beginStroll(ServerLevel level) {
        phase = Phase.STROLLING;
        seat = null;
        stepsLeft = rolledSteps(level, STROLL_MIN_SECONDS, STROLL_MAX_SECONDS);
    }

    /** The seat the visitor holds as it is now, empty if there is none, it is gone or a player rides it. */
    private Optional<TavernSeats.Seat> findHeld(ServerLevel level, Visit visit) {
        String key = seat;
        if (key == null) {
            return Optional.empty();
        }
        return TavernSeats.find(level, visit.pantry(), EntertainmentRules.get().tavernRadius(), key)
                .filter(found -> !TavernSeats.occupied(level, found));
    }

    /** The one seat search of a cycle: the free seat nearest to the visitor within the Tavern radius of its Pantry. */
    private Optional<TavernSeats.Seat> freeSeat(ServerLevel level, Settlement settlement, Visit visit) {
        Set<String> taken = seatsOfOtherVisitors(level, settlement, visit);
        return TavernSeats.around(level, visit.pantry(), EntertainmentRules.get().tavernRadius()).stream()
                .filter(found -> !taken.contains(found.key()) && !TavernSeats.occupied(level, found))
                .min(Comparator.comparingDouble(found -> found.position().distanceToSqr(visitor.position())));
    }

    private Set<String> seatsOfOtherVisitors(ServerLevel level, Settlement settlement, Visit visit) {
        Set<String> taken = new HashSet<>();
        for (Visit other : settlement.inn().visitsOf(visit.pantry())) {
            if (!other.id().equals(visitor.getUUID()) && level.getEntity(other.id()) instanceof Settler guest) {
                guest.visitorDay().heldSeat().ifPresent(taken::add);
            }
        }
        return taken;
    }

    /** A random number of decision steps between the bounds, which come in seconds. */
    private static int rolledSteps(ServerLevel level, int minSeconds, int maxSeconds) {
        int min = steps(minSeconds);
        return min + VisitorStroll.random(level).nextInt(steps(maxSeconds) - min + 1);
    }

    private static int steps(int seconds) {
        return seconds * TICKS_PER_SECOND / SettlerWork.DECISION_INTERVAL;
    }

    @Override
    public String toString() {
        return "VisitorSitting[" + phase + ", stepsLeft=" + stepsLeft + ", seat=" + seat + "]";
    }
}
