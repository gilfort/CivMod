package com.gilfort.civmod.inn;

import java.util.UUID;

import com.gilfort.civmod.job.NeedTally;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;

/**
 * The reputation of a settlement as a refuge (vault {@code Systems/NPC Population.md}): the share of met needs over all its
 * settlers, counting only the needs that exist in the game. Nobody sees it; it only changes how many visitors an inn expects. A
 * settler that is not loaded cannot be asked and does not count.
 */
final class Reputation {

    private Reputation() {
    }

    /**
     * @param level      the level
     * @param settlement a settlement
     * @return the share of met needs, 0 to 1; 0 for a settlement without a loaded settler
     */
    static double of(ServerLevel level, Settlement settlement) {
        NeedTally sum = NeedTally.NONE;
        for (UUID member : settlement.members()) {
            if (level.getEntity(member) instanceof Settler settler) {
                sum = sum.plus(settler.getWork().needTally(level));
            }
        }
        return sum.share();
    }
}
