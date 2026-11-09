package com.gilfort.civmod.entertainment;

import com.gilfort.civmod.api.Entertain;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The Stage, the JobBlock of the Bard (vault {@code Systems/Entertainment.md}, Bard): which blocks and settlers belong to
 * it, where its Sound center is and what a missed day gives. The Bard's acts are in {@code BardProfile}; the loop that plays
 * them is the Service loop.
 */
public final class Stage {

    /** The points every settler gets for a day it missed with a Bard assigned to a connected Stage. */
    public static final double CATCH_UP_POINTS = 1.0;

    private Stage() {
    }

    /**
     * @param job a Job
     * @return whether it is the Bard, whose JobBlock is the Stage
     */
    public static boolean isBard(Job job) {
        return job == ModJobs.BARD.get();
    }

    /**
     * @param block a JobBlock
     * @return whether it is a Stage
     */
    public static boolean isStage(SettlementBlock block) {
        return isBard(block.jobType());
    }

    /**
     * @param stage a Stage
     * @return the center of its stage acts: the Sound center the player set with the Scroll of Assignment, else the Stage block
     *         (the bottom center of the block, as the center of every Service act)
     */
    public static Vec3 soundCenter(SettlementBlock stage) {
        return Vec3.atBottomCenterOf(stage.soundCenter().orElse(stage.pos()));
    }

    /**
     * @param settler a settler
     * @return whether it plays the Bard: it holds the Bard Job and is not also a Tavern Host (who counts for the Tavern crowd)
     */
    public static boolean playsBard(Settler settler) {
        boolean bard = false;
        for (SettlerJob job : settler.getJobs()) {
            if (job.jobType() == ModJobs.TAVERN_HOST.get()) {
                return false;
            }
            bard |= isBard(job.jobType());
        }
        return bard;
    }

    /**
     * @param level   the level
     * @param settler a settler
     * @return whether it plays the Bard ({@link #playsBard}) and heads for its Stage or plays there now; until then it is an
     *         ordinary guest of the Tavern evening
     */
    public static boolean performs(ServerLevel level, Settler settler) {
        return playsBard(settler) && settler.getWork().atAppointment(level);
    }

    /**
     * The flat catch-up of a missed day (vault {@code Systems/Entertainment.md}, Catch-up): a settler of a settlement that has a
     * Bard assigned to a connected Stage gets {@link #CATCH_UP_POINTS}. Nothing is walked or played. The day counts as the
     * evening of a stage act, so a settler who heard the Bard that evening is not paid twice. Away settlers get nothing.
     *
     * @param level   the level
     * @param settler a settler whose catch-up covered the whole evening of that day
     * @param day     the missed day
     */
    public static void catchUp(ServerLevel level, Settler settler, long day) {
        Entertainment entertainment = settler.getWork().entertainment();
        if (entertainment.heard(Entertain.Cap.STAGE_ACT, day) || settler.getWork().isAway()) {
            return;
        }
        boolean played = SettlementData.get(level).settlement(settler.getSettlementId()).filter(settlement -> settlement.blocks().stream()
                .anyMatch(block -> isStage(block) && block.connected() && block.assignee(block.jobType()).isPresent())).isPresent();
        if (played) {
            settler.getWork().setEntertainment(entertainment.addCapped(CATCH_UP_POINTS, Entertain.Cap.STAGE_ACT, day));
        }
    }
}
