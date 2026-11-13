package com.gilfort.civmod.settlement;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.logistics.CraftPlan;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.logistics.Request;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * Sub-requests of a producer (vault {@code Systems/Sub-Requests.md}): a producer that lacks an input it cannot make
 * itself asks the settlement for it. The child request goes to the parent's Clerk, inherits its priority and is claimed by
 * any producer with a checked pattern for the item; the result is held for the parent ({@link Holds}). Delivery mode makes
 * no difference here, it only changes how things are carried.
 */
public final class SubRequests {

    private SubRequests() {
    }

    /**
     * Posts a child request for every missing input that a producer of the settlement can make, unless the chain is too
     * long, the item is already asked for along the parent chain (a cycle), or the parent already has an open child for it.
     * What stays missing shows on the parent as "Missing: …".
     *
     * @param level      the level
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param parent     the request the producer lacks inputs for
     * @param missing    what it lacks
     * @return how many child requests were posted
     */
    public static int post(ServerLevel level, SettlementData data, Settlement settlement, Request parent, List<Request.Missing> missing) {
        if (parent.target().isEmpty() || depth(settlement, parent) >= CraftPlan.MAX_DEPTH) {
            return 0;
        }
        Set<Item> chain = chainItems(settlement, parent);
        int posted = 0;
        for (Request.Missing lacking : missing) {
            Item item = lacking.item();
            if (lacking.anyFuel() || lacking.count() <= 0 || chain.contains(item) || hasChildFor(settlement, parent, item)
                    || !Producers.canMake(level, settlement, item)) {
                continue;
            }
            Requests.post(data, settlement, item, lacking.count(), parent.target(), Optional.of(parent.id()));
            posted++;
        }
        return posted;
    }

    /** How many parents a request has: 0 for a main request. */
    private static int depth(Settlement settlement, Request request) {
        int depth = 0;
        Optional<Request> up = request.parent().flatMap(id -> Requests.get(settlement, id));
        // The limit also stops a parent loop in broken data.
        while (up.isPresent() && depth <= CraftPlan.MAX_DEPTH) {
            depth++;
            up = up.get().parent().flatMap(id -> Requests.get(settlement, id));
        }
        return depth;
    }

    /** The items of a request and of every request above it. */
    private static Set<Item> chainItems(Settlement settlement, Request request) {
        Set<Item> items = new HashSet<>();
        Set<UUID> seen = new HashSet<>();
        Optional<Request> current = Optional.of(request);
        while (current.isPresent() && seen.add(current.get().id())) {
            items.add(current.get().item());
            current = current.get().parent().flatMap(id -> Requests.get(settlement, id));
        }
        return items;
    }

    private static boolean hasChildFor(Settlement settlement, Request parent, Item item) {
        return Requests.children(settlement, parent.id()).stream().anyMatch(child -> child.item() == item);
    }
}
