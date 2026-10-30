package com.gilfort.civmod.job;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.job.SettlerWork.RestPlace;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
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
 * finds the rest place of the night (a bed, a straw bed spot or the ground) and keeps it. It answers the shell with a
 * {@link BodyStep} or a spot to stand on; only the shell walks or teleports the settler and sets its activity. The
 * module itself only lies the settler down, wakes it and sets the area it strolls in during the evening.
 */
final class SettlerRest {

    /** Stroll radius around the bed in the evening. */
    static final int REST_RADIUS = 5;

    private final Settler settler;
    private @Nullable RestPlace restPlace;
    private long restPlaceDay = -1;

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
        RestPlace rest = ensureRestPlace(level, day);
        if (rest != null) {
            settler.setHomeTo(rest.pos(), REST_RADIUS);
        }
        if (!bed || rest == null) {
            return BodyStep.STOP;
        }
        if (activity == Activity.GO_TO_BED || activity == Activity.SLEEP) {
            return BodyStep.STAY;
        }
        return new BodyStep.Walk(Activity.GO_TO_BED, rest.pos(), SettlerWork.REACH);
    }

    private @Nullable RestPlace ensureRestPlace(ServerLevel level, long day) {
        if (restPlace != null && restPlaceDay == day && (!restPlace.bed() || SettlerBeds.head(level, restPlace.pos()).isPresent())) {
            return restPlace;
        }
        SettlementData data = SettlementData.get(level);
        Optional<Settlement> settlement = data.settlement(settler.getSettlementId());
        if (settlement.isEmpty()) {
            return null;
        }
        Optional<BlockPos> bed = SettlerBeds.ensureBed(level, data, settlement.get(), settler.getUUID(), settler.blockPosition());
        if (bed.isPresent()) {
            restPlace = new RestPlace(bed.get(), true, null);
        } else {
            BlockPos jobBlock = settler.getJobs().stream().findFirst().map(SettlerJob::jobBlock)
                    .or(() -> settlement.get().anchor().map(SettlementBlock::pos)).orElse(settler.blockPosition());
            restPlace = SettlerBeds.reserveStrawSpot(level, jobBlock, settler.getUUID())
                    .map(spot -> new RestPlace(spot.pos(), false, spot))
                    .orElse(new RestPlace(jobBlock.above(), false, null));
        }
        restPlaceDay = day;
        return restPlace;
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
