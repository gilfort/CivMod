package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.job.SettlerWork.RestPlace;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Hearths;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Carries out the rest intents of the {@link Schedule} for the Settler Shell (vault {@code Systems/NPC Daily
 * Schedule.md}): rest near the rest place, go to bed, sleep, wake, return to the rest place after a skipped night. It
 * finds the rest place of the night (its bed near a hearth, else the camp: a straw bed spot or the ground around a
 * hearth) and keeps it. The first settler going to bed in a settlement without a hearth places a campfire. It answers
 * the shell with a {@link BodyStep} or a spot to stand on; only the shell walks or teleports the settler and sets its
 * activity. The module itself only lies the settler down, wakes it and sets the area it strolls in during the evening.
 */
final class SettlerRest {

    /** Stroll radius around the bed in the evening. */
    static final int REST_RADIUS = 5;

    private final Settler settler;
    private @Nullable RestPlace restPlace;
    private long restPlaceDay = -1;
    /** The {@link Settlement#hearthRevision()} the rest place was chosen with. */
    private int restPlaceHearths;

    /**
     * @param settler the settler
     */
    SettlerRest(Settler settler) {
        this.settler = settler;
    }

    /**
     * @return where the settler spends (or spent) the night, if already decided
     */
    Optional<RestPlace> restPlace() {
        return Optional.ofNullable(restPlace);
    }

    /**
     * @return the area to stroll in during the evening (bed / straw bed spot), if any
     */
    Optional<BlockPos> restArea() {
        return Optional.ofNullable(restPlace).map(RestPlace::pos);
    }

    /**
     * Where the settler sleeps tonight, as the hearths and its bed stand now. Claims nothing and changes nothing, so it
     * holds in the morning and in catch-up too.
     *
     * @param bed    the own real bed it sleeps in, empty at the camp
     * @param hearth the hearth of the camp, empty in a bed or without a hearth
     */
    private record Sleeping(Optional<BlockPos> bed, Optional<BlockPos> hearth) {
    }

    private Sleeping sleeping(ServerLevel level, Settlement settlement) {
        Set<BlockPos> hearths = Hearths.of(level, settlement);
        int range = CivModConfig.HEARTH_RANGE.get();
        Optional<BlockPos> bed = settlement.bedOf(settler.getUUID()).filter(head -> SettlerBeds.head(level, head).isPresent())
                .filter(head -> hearths.isEmpty() || Hearths.nearest(hearths, head).filter(near -> Hearths.distance(head, near) <= range).isPresent());
        return bed.isPresent() ? new Sleeping(bed, Optional.empty()) : new Sleeping(bed, Hearths.nearest(hearths, jobBlock(settlement)));
    }

    private BlockPos jobBlock(Settlement settlement) {
        return settler.getJobs().stream().findFirst().map(SettlerJob::jobBlock)
                .or(() -> settlement.anchor().map(SettlementBlock::pos)).orElse(settler.blockPosition());
    }

    /**
     * @param level the level
     * @return whether the settler sleeps in its own real bed; false at the camp, which also takes a settler whose bed
     *         is too far from any hearth
     */
    boolean sleepsInOwnBed(ServerLevel level) {
        return settlement(level).flatMap(settlement -> sleeping(level, settlement).bed()).isPresent();
    }

    /**
     * @param level the level
     * @return the blocks between the place the settler sleeps at and the nearest hearth, empty without a hearth
     */
    OptionalInt hearthDistance(ServerLevel level) {
        Optional<Settlement> settlement = settlement(level);
        if (settlement.isEmpty()) {
            return OptionalInt.empty();
        }
        Sleeping sleeping = sleeping(level, settlement.get());
        Set<BlockPos> hearths = Hearths.of(level, settlement.get());
        Optional<BlockPos> place = restPlace().map(RestPlace::pos).or(sleeping::bed)
                .or(() -> sleeping.hearth().map(hearth -> hearth.east(SettlerBeds.CAMP_RING)));
        return place.flatMap(from -> Hearths.nearest(hearths, from).map(hearth -> Hearths.distance(from, hearth)))
                .stream().mapToInt(Integer::intValue).findFirst();
    }

    private Optional<Settlement> settlement(ServerLevel level) {
        return SettlementData.get(level).settlement(settler.getSettlementId());
    }

    /** The day is not over: the rest place of the night is chosen again in the evening. */
    void clear() {
        restPlace = null;
    }

    /**
     * Rests near the rest place in the evening ({@link Schedule.Intent#REST}) or goes to bed
     * ({@link Schedule.Intent#GO_TO_BED}); the settler strolls around its rest place.
     *
     * @param level    the level
     * @param day      the day
     * @param bed      whether it goes to bed
     * @param activity the current activity
     * @return walk to the rest place, stay on the way or in bed, or stand still (resting, or no rest place)
     */
    BodyStep evening(ServerLevel level, long day, boolean bed, Activity activity) {
        if (bed && activity != Activity.GO_TO_BED && activity != Activity.SLEEP) {
            placeCampfireIfNone(level);
        }
        RestPlace previous = restPlace;
        RestPlace rest = ensureRestPlace(level, day);
        if (rest != null) {
            settler.setHomeTo(rest.pos(), REST_RADIUS);
        }
        if (!bed || rest == null) {
            return BodyStep.STOP;
        }
        if (activity == Activity.SLEEP || activity == Activity.GO_TO_BED && rest.equals(previous)) {
            return BodyStep.STAY;
        }
        return new BodyStep.Walk(Activity.GO_TO_BED, rest.pos(), SettlerWork.REACH);
    }

    /** Only the live evening comes here: catch-up places nothing, the first live evening follows. */
    private void placeCampfireIfNone(ServerLevel level) {
        settlement(level).filter(settlement -> Hearths.of(level, settlement).isEmpty())
                .ifPresent(settlement -> Hearths.placeCampfire(level, settlement, jobBlock(settlement)));
    }

    private @Nullable RestPlace ensureRestPlace(ServerLevel level, long day) {
        Optional<Settlement> settlement = settlement(level);
        if (settlement.isEmpty()) {
            return null;
        }
        if (restPlace != null && restPlaceDay == day && restPlaceHearths == settlement.get().hearthRevision()
                && (!restPlace.bed() || SettlerBeds.head(level, restPlace.pos()).isPresent())) {
            return restPlace;
        }
        SettlerBeds.ensureBed(level, SettlementData.get(level), settlement.get(), settler.getUUID(), settler.blockPosition());
        Sleeping sleeping = sleeping(level, settlement.get());
        restPlace = sleeping.bed().map(bed -> new RestPlace(bed, true, null)).orElseGet(() -> camp(level, settlement.get(), sleeping.hearth()));
        restPlaceDay = day;
        restPlaceHearths = settlement.get().hearthRevision();
        return restPlace;
    }

    /** The ring around the hearth, its bare ground once the ring is full; without a hearth the place at the JobBlock. */
    private RestPlace camp(ServerLevel level, Settlement settlement, Optional<BlockPos> hearth) {
        BlockPos jobBlock = jobBlock(settlement);
        RestPlace ground = new RestPlace(jobBlock.above(), false, null);
        if (hearth.isPresent()) {
            return SettlerBeds.reserveCampSpot(level, hearth.get(), settler.getUUID())
                    .map(spot -> new RestPlace(spot.pos(), false, SettlerBeds.inCampRing(spot.pos(), hearth.get()) ? spot : null))
                    .orElse(ground);
        }
        return SettlerBeds.reserveStrawSpot(level, jobBlock, settler.getUUID()).map(spot -> new RestPlace(spot.pos(), false, spot)).orElse(ground);
    }

    /**
     * The night was skipped while the settler was not in bed: it appears at its rest place of last night as if it slept
     * there ({@link Schedule.Intent#RETURN_TO_REST_PLACE}).
     *
     * @param level the level
     * @param day   the day after the night
     * @return where the shell puts the settler, empty without a rest place
     */
    Optional<Vec3> returnSpot(ServerLevel level, long day) {
        RestPlace rest = ensureRestPlace(level, day - 1);
        restPlace = null;
        if (rest == null) {
            return Optional.empty();
        }
        return Optional.of(rest.straw() != null ? Vec3.atBottomCenterOf(rest.pos()) : SettlerWork.standingSpot(level, rest.pos()));
    }

    /**
     * Lies down at the rest place (the shell has set the activity to sleeping): in a free bed, else on the straw bed or
     * the bare ground.
     *
     * @param level the level
     */
    void lieDown(ServerLevel level) {
        RestPlace rest = restPlace;
        if (rest != null && rest.bed() && !level.getBlockState(rest.pos()).getValue(BedBlock.OCCUPIED)
                && settler.startSleeping(rest.pos())) {
            return;
        }
        if (rest != null && rest.straw() != null) {
            settler.sleepOnStraw(rest.straw(), true);
        } else {
            settler.sleepOnStraw(new SettlerBeds.StrawSpot(settler.blockPosition(), settler.getDirection()), false);
        }
    }

    /** Gets up after the night (the shell sets the settler idle): the straw bed spot and the rest place are free again. */
    void wake() {
        settler.wakeUp();
        SettlerBeds.releaseStrawSpot(settler.getUUID());
        restPlace = null;
    }

    /**
     * A settler that stands on a bed (beds are blocked for the pathfinder) gets down before it walks, to the vanilla
     * stand-up position, like waking up.
     *
     * @param level the level
     * @return where the shell puts the settler, empty if it does not stand on a bed (or sleeps)
     */
    Optional<Vec3> offBedSpot(ServerLevel level) {
        if (settler.isSleeping()) {
            return Optional.empty();
        }
        BlockPos feet = settler.blockPosition();
        BlockPos bed = level.getBlockState(feet).getBlock() instanceof AbstractBedBlock ? feet
                : level.getBlockState(feet.below()).getBlock() instanceof AbstractBedBlock ? feet.below() : null;
        return bed == null ? Optional.empty() : Optional.of(SettlerWork.standingSpot(level, bed));
    }
}
