package com.gilfort.civmod.inn;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSpawner;
import com.gilfort.civmod.settlement.InnSlots;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The visitor entities of the inns (vault {@code Systems/NPC Population.md}): they come into the world, are asked to stay longer
 * and leave. The settlement's {@link Visit} is the truth about a stay; the entity is its body, and a body without a stay removes
 * itself ({@link Inns#checkStay}).
 */
public final class Visitors {

    /** Players within this distance get the note of a visitor that leaves. */
    private static final double NOTE_DISTANCE = 64.0;

    /** What asking a visitor to stay longer did. */
    public enum Ask {
        /** The visitor stays {@code extend_days} more. */
        EXTENDED,
        /** The visitor has to go on at the time it planned. */
        DECLINED,
        /** The player asked this visitor before. */
        ALREADY_ASKED,
        /** The settlement has no stay of this visitor. */
        NO_STAY
    }

    private Visitors() {
    }

    /**
     * Brings the visitor of a stay into the world, rolling its past and its levels.
     *
     * @param level      the level
     * @param settlement the settlement of the inn
     * @param visit      the stay
     * @param levelCap   the highest level it can roll in a favoured Job
     * @return whether the visitor stands in the world now
     */
    static boolean arrive(ServerLevel level, Settlement settlement, Visit visit, int levelCap) {
        return SettlerSpawner.visitor(level, settlement, visit.pantry(), visit.id(), levelCap).isPresent();
    }

    /**
     * @param level      the level
     * @param settlement the settlement
     * @return the highest level a visitor can roll: the best skill level of the settlement's loaded settlers, at least the minimum
     *         of the rules
     */
    static int levelCap(ServerLevel level, Settlement settlement) {
        int best = InnRules.get().minLevelCap();
        for (UUID member : settlement.members()) {
            if (level.getEntity(member) instanceof Settler settler) {
                best = Math.max(best, settler.getSkills().snapshot().values().stream().mapToInt(SettlerSkills.Skill::level).max().orElse(0));
            }
        }
        return best;
    }

    /**
     * The visitor of a stay that is over goes, if it is loaded; one that is not loaded notices the missing stay when it loads. A
     * visitor the player asked to stay longer says goodbye to the players nearby, and one that found no food at the Pantry says so.
     *
     * @param level the level
     * @param visit the stay that ended
     */
    static void leave(ServerLevel level, Visit visit) {
        if (level.getEntity(visit.id()) instanceof Settler visitor) {
            if (visit.noFood()) {
                note(level, visitor, Component.translatable("civmod.visitor.no_food", visitor.getName()));
            } else if (visit.asked()) {
                note(level, visitor, Component.translatable("civmod.visitor.moved_on", visitor.getName()));
            }
            visitor.discard();
        }
    }

    /**
     * The visitor of a settlement that is gone vanishes with a puff and a note.
     *
     * @param level   the level
     * @param visitor the visitor
     */
    static void vanish(ServerLevel level, Settler visitor) {
        Vec3 pos = visitor.position();
        level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 1.0, pos.z, 12, 0.3, 0.5, 0.3, 0.02);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4F, 0.8F);
        note(level, visitor, Component.translatable("civmod.visitor.dissolved", visitor.getName()));
        visitor.discard();
    }

    private static void note(ServerLevel level, Settler visitor, Component message) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(visitor) <= NOTE_DISTANCE * NOTE_DISTANCE) {
                player.sendSystemMessage(message);
            }
        }
    }

    /**
     * The player asks a visitor to stay a little longer: once per visitor, and it works with the chance of {@link InnRules}.
     *
     * @param level   the level
     * @param visitor the visitor
     * @return what happened
     */
    public static Ask askToStay(ServerLevel level, Settler visitor) {
        SettlementData data = SettlementData.get(level);
        Optional<Settlement> settlement = data.settlement(visitor.getVisitedSettlementId());
        Optional<Visit> stay = settlement.map(found -> found.inn().visits().get(visitor.getUUID()));
        if (stay.isEmpty()) {
            return Ask.NO_STAY;
        }
        Visit visit = stay.get();
        if (visit.asked()) {
            return Ask.ALREADY_ASKED;
        }
        InnRules rules = InnRules.get();
        boolean works = rules.extensionWorks(Inns.random(level).nextDouble());
        Visit asked = visit.askedToStay();
        Visit updated = works ? asked.leavingAt(InnSlots.after(visit.leaveSlot(), rules.extendDays())) : asked;
        data.updateInn(settlement.get(), inn -> inn.withVisit(updated));
        return works ? Ask.EXTENDED : Ask.DECLINED;
    }
}
