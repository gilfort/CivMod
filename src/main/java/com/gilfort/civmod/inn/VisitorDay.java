package com.gilfort.civmod.inn;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.job.StandingSpots;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.InnSlots;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The day of one visitor (vault {@code Systems/NPC Population.md}, section "The inn and its visitors"). A visitor ticks no needs and
 * no Job; this class is all it does on its own. Once per decision step ({@link SettlerWork#DECISION_INTERVAL} ticks) it looks at
 * the time of day and does what the time asks:
 * <ul>
 * <li>by day it strolls ({@link VisitorStroll}) through the settlement inside the border, with a light pull to the Pantry;</li>
 * <li>in the evening (from the end of work to its bedtime) it walks to the Pantry, eats one piece of food from the Pantry's stock,
 * and stays around the Pantry within the Tavern evening;</li>
 * <li>at night it walks to its guest bed and sleeps there until its waking time.</li>
 * </ul>
 * Its times are the daily times of a settler ({@link Schedule#dayTimes}), so visitors do not all go to bed at the same tick. Each
 * decision also runs the meeting check of the social life, with the rules of a settler. Nothing is saved: after a reload the
 * visitor decides again, and the visit itself ({@link Visit}) is kept by the settlement. In an unloaded settlement the visitor does
 * not tick, so it stands still there; the checks of the inn work on its visit as data.
 */
public final class VisitorDay {

    /** Blocks around the Pantry that a visitor strolls in once it has eaten in the evening. */
    private static final int EVENING_RADIUS = 6;
    /** How close a visitor has to come to its guest bed before it lies down (horizontal, in blocks). */
    private static final double BED_REACH = 2.5;
    /** How close a visitor has to come to the Pantry before it takes its meal. */
    private static final double PANTRY_REACH = ClerkStorage.COUNTER_REACH;
    /** The walking speed of a visitor on its way to the Pantry or its bed, like a settler's. */
    private static final double WALK_SPEED = 0.6;
    /** The vertical reach of a target: a bed or a Pantry a few blocks above or below counts as reached. */
    private static final double VERTICAL_REACH = 3.0;

    /** The part of the day a visitor is in. */
    enum Part {
        /** From its waking time to the end of work: strolling. */
        DAY,
        /** From the end of work to its bedtime: the Tavern evening, with its meal at the Pantry. */
        EVENING,
        /** From its bedtime to its waking time: in its guest bed. */
        NIGHT;

        /**
         * @param dayTime the day time (0 = 06:00)
         * @param times   the daily times of the visitor on that day
         * @return the part of the day
         */
        static Part of(long dayTime, Schedule.DayTimes times) {
            if (dayTime >= times.bed() || dayTime < times.wake()) {
                return NIGHT;
            }
            return dayTime >= SettlerWork.WORK_END ? EVENING : DAY;
        }
    }

    /** A walk to a target: where to, and since when (the game time it started). */
    private record Walk(BlockPos target, long since) {
    }

    private final Settler visitor;
    private Part part = Part.DAY;
    /** Whether the stroll goal may start a stroll: not while the visitor walks to the Pantry or its bed, or lies in it. */
    private boolean mayStroll = true;
    private @Nullable Walk walk;

    /**
     * @param visitor the settler whose visit to an inn this is the day of
     */
    public VisitorDay(Settler visitor) {
        this.visitor = visitor;
    }

    /**
     * @return whether the stroll goal may start a stroll
     */
    boolean mayStroll() {
        return mayStroll && !visitor.isSleeping();
    }

    /**
     * @return whether it is the evening of the visitor, when its strolls stay around the Pantry
     */
    boolean isEvening() {
        return part == Part.EVENING;
    }

    /**
     * Every tick of the AI step: the conversation and the head turn of the social life run on, and every decision step the visitor
     * decides what to do now.
     *
     * @param level the level
     */
    public void aiStep(ServerLevel level) {
        visitor.getWork().socialTick();
        if ((visitor.tickCount + visitor.getId()) % SettlerWork.DECISION_INTERVAL == 0) {
            decideNow(level);
        }
    }

    /**
     * Decides once what the visitor does now, as it does every decision step (tests call it directly). Nothing is decided while
     * the visitor is in a conversation, and a visitor whose visit is gone does nothing; its body notices that by itself.
     *
     * @param level the level
     */
    public void decideNow(ServerLevel level) {
        if (visitor.getWork().talkHoldsDecision()) {
            return;
        }
        SettlementData data = SettlementData.get(level);
        Optional<Settlement> settlement = data.settlement(visitor.getVisitedSettlementId());
        Optional<Visit> visit = settlement.flatMap(found -> found.inn().visit(visitor.getUUID()));
        if (visit.isEmpty()) {
            return;
        }
        long clock = level.getOverworldClockTime();
        long day = DayClock.day(clock);
        part = Part.of(DayClock.dayTime(clock), Schedule.dayTimes(visitor.getUUID(), day));
        switch (part) {
            case NIGHT -> night(level, visit.get());
            case EVENING -> evening(level, data, settlement.get(), visit.get(), day);
            case DAY -> strollByDay();
        }
        visitor.getWork().meetAsVisitor(level);
    }

    private void strollByDay() {
        wakeUp();
        walk = null;
        mayStroll = true;
        visitor.clearHome();
    }

    /**
     * The evening: to the Pantry for the meal, then around the Pantry. A Pantry that is gone serves no meal and sends nobody away:
     * the visitor stays the night and leaves at the next morning check, which the inn plays.
     */
    private void evening(ServerLevel level, SettlementData data, Settlement settlement, Visit visit, long day) {
        wakeUp();
        visitor.setHomeTo(visit.pantry(), EVENING_RADIUS);
        boolean hungry = visit.fedDay() != day && ClerkStorage.pantryAt(settlement, visit.pantry()).isPresent();
        mayStroll = !hungry;
        if (hungry && reached(level, visit.pantry(), PANTRY_REACH)) {
            eat(level, data, settlement, visit, day);
            mayStroll = true;
        }
    }

    /** The meal: one piece of food from the Pantry. With nothing to eat the visitor leaves at the next morning check. */
    private void eat(ServerLevel level, SettlementData data, Settlement settlement, Visit visit, long day) {
        Visit meal = visit.fedOn(day);
        if (!VisitorMeal.eat(level, visitor, visit.pantry())) {
            meal = meal.sentAway(InnSlots.nextMorning(InnSlots.latest(level.getOverworldClockTime())));
        }
        Visit updated = meal;
        data.updateInn(settlement, inn -> inn.withVisit(updated));
    }

    /** The night: to the guest bed and into it. A visitor without a bed stands where it is until morning. */
    private void night(ServerLevel level, Visit visit) {
        mayStroll = false;
        if (visitor.isSleeping()) {
            walk = null;
            return;
        }
        Optional<BlockPos> bed = visit.bed();
        if (bed.isEmpty()) {
            visitor.getNavigation().stop();
            walk = null;
            return;
        }
        if (reached(level, bed.get(), BED_REACH)) {
            BlockState state = level.getBlockState(bed.get());
            if (state.hasProperty(BedBlock.OCCUPIED) && !state.getValue(BedBlock.OCCUPIED)) {
                visitor.getNavigation().stop();
                visitor.startSleeping(bed.get());
            }
        }
    }

    private void wakeUp() {
        if (visitor.isSleeping()) {
            visitor.wakeUp();
        }
    }

    /**
     * Walks towards a target. A visitor that has not come there for as long as a settler is allowed to be stuck is put next to it.
     *
     * @param level  the level
     * @param target the block to walk to
     * @param reach  how close the visitor has to come, horizontally
     * @return whether the visitor stands within reach of the target now
     */
    private boolean reached(ServerLevel level, BlockPos target, double reach) {
        if (isNear(target, reach)) {
            walk = null;
            return true;
        }
        long now = level.getGameTime();
        Walk current = walk;
        if (current == null || !current.target().equals(target)) {
            walk = new Walk(target.immutable(), now);
        } else if (now - current.since() > CivModConfig.antiStuckTicks()) {
            Vec3 spot = StandingSpots.standingSpot(level, target, reach);
            visitor.teleportTo(spot.x, spot.y, spot.z);
            visitor.getNavigation().stop();
            walk = null;
            return true;
        }
        visitor.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, WALK_SPEED);
        return false;
    }

    @Override
    public String toString() {
        return "VisitorDay[" + part + ", mayStroll=" + mayStroll + ", walk=" + walk + ", navigationDone=" + visitor.getNavigation().isDone()
                + ", at " + visitor.blockPosition() + "]";
    }

    private boolean isNear(BlockPos target, double reach) {
        Vec3 center = Vec3.atCenterOf(target);
        double dx = visitor.getX() - center.x;
        double dz = visitor.getZ() - center.z;
        return dx * dx + dz * dz <= reach * reach && Math.abs(visitor.getY() - target.getY()) <= VERTICAL_REACH;
    }
}
