package com.gilfort.civmod.inn;

import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.Talent;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.network.chat.Component;

/**
 * What a visitor of an inn offers (vault {@code Systems/NPC Population.md}, section 6): the trade it names and the price it asks to
 * join the settlement. The trade is the Job its upbringing favours in which it is best; with a tie the first one in the Job registry
 * wins, so the offer stays the same from the first talk to the hire. The level only sets the price and is never told. A visitor whose
 * upbringing favours nothing (a race without data) names the Job it is best in. Computed on a talk and on hiring only.
 *
 * @param trade the Job the visitor names as its trade
 * @param level the visitor's level in it
 * @param price what it asks
 */
public record VisitorOffer(Job trade, int level, VisitorPrices.Price price) {

    /**
     * @param visitor a visitor
     * @return its offer now
     */
    public static VisitorOffer of(Settler visitor) {
        Job trade = best(visitor, job -> Talent.favours(visitor, job)).or(() -> best(visitor, job -> true))
                .orElseThrow(() -> new IllegalStateException("No Job is registered, so a visitor has no trade"));
        int level = visitor.getSkills().get(trade).level();
        return new VisitorOffer(trade, level, VisitorPrices.price(trade, level, visitor.getUUID()));
    }

    private static Optional<Job> best(Settler visitor, Predicate<Job> candidates) {
        Optional<Job> best = Optional.empty();
        int bestLevel = 0;
        for (Job job : ModJobs.REGISTRY) {
            int level = visitor.getSkills().get(job).level();
            if (candidates.test(job) && (best.isEmpty() || level > bestLevel)) {
                best = Optional.of(job);
                bestLevel = level;
            }
        }
        return best;
    }

    /**
     * @return the greeting that names the price and the trade, which a talk starts with
     */
    public Component greeting() {
        return Component.translatable("civmod.visitor.talk.price", price.amount(), price.stack().getItemName(), trade.indefiniteName());
    }
}
