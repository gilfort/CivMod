package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.api.Entertain;
import com.gilfort.civmod.api.ServiceAct;
import com.gilfort.civmod.api.ServiceProfile;

/**
 * The acts of the Bard (vault {@code Systems/Entertainment.md}, Bard): the day round from 11:00 to 17:30 and the stage act
 * from 17:30 to 20:00, each with the radius of 8 blocks and +1 Entertainment per listener, growing 1 % per Bard level. A
 * settler gets the points of a round stop once a day and those of a stage act once an evening, however many Bards reach
 * it ({@link Entertain.Cap}). The numbers are datapack values ({@code service_acts} in {@code civmod_jobs/bard.json}).
 * <p>
 * The round walks to settlers at work and plays as many stops as the Stage level; the Bard looks and sounds like a performer
 * while it plays ({@link BardShow}).
 */
public final class BardProfile implements ServiceProfile {

    /** The radius of a round stop (around the Bard) and of a stage act (around the Sound center), in blocks. */
    private static final double LISTEN_RADIUS = 8.0;
    /** The percent the Bard's points grow per skill level. */
    private static final double PERCENT_PER_LEVEL = 1.0;

    private static final List<ServiceAct> ACTS = List.of(
            ServiceAct.round("round", ServiceAct.clock(11, 0), ServiceAct.clock(17, 30), LISTEN_RADIUS,
                    new Entertain(1.0, PERCENT_PER_LEVEL, Entertain.Cap.ROUND_STOP)),
            ServiceAct.stationary("stage", ServiceAct.clock(17, 30), ServiceAct.clock(20, 0), LISTEN_RADIUS,
                    new Entertain(1.0, PERCENT_PER_LEVEL, Entertain.Cap.STAGE_ACT)));

    @Override
    public List<ServiceAct> acts() {
        return ACTS;
    }
}
