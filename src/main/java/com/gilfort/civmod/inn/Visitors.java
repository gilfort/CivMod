package com.gilfort.civmod.inn;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSpawner;
import com.gilfort.civmod.settlement.InnSlots;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The visitor entities of the inns (vault {@code Systems/NPC Population.md}): they come into the world, are asked to stay longer
 * and leave. The settlement's {@link Visit} is the truth about a visitor's stay; the entity is its body, and a body without a visit
 * removes itself ({@link Inns#checkVisit}).
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
        /** The settlement has no visit of this visitor. */
        NO_VISIT
    }

    private Visitors() {
    }

    /**
     * Brings the visitor of a visit into the world, rolling its past and its levels.
     *
     * @param level      the level
     * @param settlement the settlement of the inn
     * @param visit      the visit
     * @param levelCap   the highest level it can roll in a favoured Job
     * @return whether the visitor stands in the world now
     */
    static boolean arrive(ServerLevel level, Settlement settlement, Visit visit, int levelCap) {
        return SettlerSpawner.visitor(level, settlement, visit.pantry(), visit.id(), levelCap).isPresent();
    }

    /**
     * @param level      the level
     * @param settlement the settlement, with every settler loaded ({@link Inns#run} waits for that)
     * @return the highest level a visitor can roll: the best skill level of the settlement's settlers, at least the minimum of the
     *         rules
     */
    static int levelCap(ServerLevel level, Settlement settlement) {
        int best = InnRules.get().minLevelCap();
        for (UUID member : settlement.members()) {
            if (level.getEntity(member) instanceof Settler settler) {
                best = Math.max(best, settler.getSkills().bestLevel());
            }
        }
        return best;
    }

    /**
     * The visitor of a visit that is over goes, if it is loaded; one that is not loaded notices the missing visit when it loads. A
     * visitor the player asked to stay longer says goodbye to the players nearby, and one that found no food at the Pantry says so.
     *
     * @param level the level
     * @param visit the visit that ended
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
        SettlerSpawner.puff(level, visitor.position());
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
     * @param level   the level
     * @param visitor a visitor
     * @return whether the player can still ask it to stay longer: it has a visit that nobody has asked about yet and that no empty
     *         Pantry has cut short
     */
    public static boolean canAskToStay(ServerLevel level, Settler visitor) {
        return SettlementData.get(level).settlement(visitor.getVisitedSettlementId())
                .flatMap(settlement -> settlement.inn().visit(visitor.getUUID())).filter(visit -> !visit.asked() && !visit.noFood()).isPresent();
    }

    /**
     * A player asks a visitor to stay longer from its window and gets the answer on the action bar.
     *
     * @param player  the player
     * @param visitor the visitor
     * @return what happened
     */
    public static Ask askToStayAndTell(ServerPlayer player, Settler visitor) {
        Ask result = askToStay(player.level(), visitor);
        String key = switch (result) {
            case EXTENDED -> "civmod.visitor.stay.extended";
            case DECLINED, NO_VISIT -> "civmod.visitor.stay.declined";
            case ALREADY_ASKED -> "civmod.visitor.stay.already_asked";
        };
        player.sendOverlayMessage(Component.translatable(key, visitor.getName()));
        return result;
    }

    /**
     * The player asks a visitor to stay a little longer: once per visitor, and it works with the chance of {@link InnRules}. A
     * visitor that the meal sent away for an empty Pantry has made up its mind: the ask is declined and nothing changes.
     *
     * @param level   the level
     * @param visitor the visitor
     * @return what happened
     */
    public static Ask askToStay(ServerLevel level, Settler visitor) {
        SettlementData data = SettlementData.get(level);
        Optional<Settlement> settlement = data.settlement(visitor.getVisitedSettlementId());
        Optional<Visit> found = settlement.flatMap(owner -> owner.inn().visit(visitor.getUUID()));
        if (found.isEmpty()) {
            return Ask.NO_VISIT;
        }
        Visit visit = found.get();
        if (visit.asked()) {
            return Ask.ALREADY_ASKED;
        }
        if (visit.noFood()) {
            return Ask.DECLINED;
        }
        InnRules rules = InnRules.get();
        boolean works = rules.extensionWorks(Inns.random(level).nextDouble());
        Visit asked = visit.askedToStay();
        Visit updated = works ? asked.leavingAt(InnSlots.after(visit.leaveSlot(), rules.extendDays())) : asked;
        data.updateInn(settlement.get(), inn -> inn.withVisit(updated));
        return works ? Ask.EXTENDED : Ask.DECLINED;
    }
}
