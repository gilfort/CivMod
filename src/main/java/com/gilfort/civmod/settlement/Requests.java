package com.gilfort.civmod.settlement;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.gilfort.civmod.logistics.Request;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * The requests of a settlement (vault {@code Jobs/Clerk.md}, {@code Systems/Producing Jobs.md}), kept in its
 * {@link Settlement}. Clerk stock rules post them ({@link StockRules}); every producer of the settlement sees them by
 * priority and claims the part it can do in one round, so several producers split a request without double work.
 * <p>
 * Claim lifecycle for producers: {@link #claimable} → {@link #claim} → craft → deliver to {@link Request#target()} →
 * {@link #finish}. A producer that stops (switches job, the day ends, leaves the JobBlock, dies) calls
 * {@link #release} / {@link #releaseAll}; the amount is open again. {@link #setMissing} shows why it cannot work on one.
 */
public final class Requests {

    /** Priority of requests from sources other than a stock rule. */
    public static final int DEFAULT_PRIORITY = 0;

    /** Highest priority first; equal priorities in posting order (the list order). */
    private static final Comparator<Request> BY_PRIORITY = Comparator.comparingInt(Request::priority).reversed();

    private Requests() {
    }

    /**
     * @param settlement a settlement
     * @return all its requests, highest priority first (also those fully claimed)
     */
    public static List<Request> byPriority(Settlement settlement) {
        return settlement.requests().stream().sorted(BY_PRIORITY).toList();
    }

    /**
     * @param settlement a settlement
     * @return the requests with an open amount a producer can claim, highest priority first
     */
    public static List<Request> claimable(Settlement settlement) {
        return settlement.requests().stream().filter(request -> request.amount() > 0).sorted(BY_PRIORITY).toList();
    }

    /**
     * @param settlement a settlement
     * @param id         a request id
     * @return that request, if it is still there
     */
    public static Optional<Request> get(Settlement settlement, UUID id) {
        return settlement.requests().stream().filter(request -> request.id().equals(id)).findFirst();
    }

    /**
     * @param settlement a settlement
     * @param clerk      a Clerk JobBlock
     * @param slot       a rule slot
     * @return the request the rule in that slot posted, if any
     */
    public static Optional<Request> forRule(Settlement settlement, BlockPos clerk, int slot) {
        return settlement.requests().stream().filter(request -> request.postedBy(clerk, slot)).findFirst();
    }

    /**
     * @param settlement a settlement
     * @param clerk      a Clerk JobBlock
     * @return the requests that Clerk's rules posted, highest priority first
     */
    public static List<Request> ofClerk(Settlement settlement, BlockPos clerk) {
        return byPriority(settlement).stream().filter(request -> request.rule() != Request.NO_RULE
                && request.target().filter(clerk::equals).isPresent()).toList();
    }

    /**
     * Posts a request from a source other than a stock rule: priority {@value #DEFAULT_PRIORITY}, a sub-request the
     * priority of its parent.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param item       the requested item
     * @param amount     the amount
     * @param target     the Clerk the result goes to, if any
     * @param parent     the request this is a sub-request of
     * @return the posted request
     */
    public static Request post(SettlementData data, Settlement settlement, Item item, int amount, Optional<BlockPos> target,
            Optional<UUID> parent) {
        int priority = parent.flatMap(id -> get(settlement, id)).map(Request::priority).orElse(DEFAULT_PRIORITY);
        Request request = new Request(UUID.randomUUID(), item, amount, priority, target, Request.NO_RULE, parent, List.of(), Optional.empty());
        settlement.mutableRequests().add(request);
        data.setDirty();
        return request;
    }

    /**
     * Claims up to {@code max} of a request's open amount for a producer.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the request id
     * @param settler    the producer settler
     * @param name       its name
     * @param max        the most it can do in one round
     * @return the claimed amount, 0 if the request is gone or nothing is open
     */
    public static int claim(SettlementData data, Settlement settlement, UUID id, UUID settler, String name, int max) {
        Optional<Request> request = get(settlement, id);
        int claimed = request.map(found -> Math.min(Math.max(0, max), found.amount())).orElse(0);
        if (claimed > 0) {
            update(data, settlement, id, found -> found.withClaim(settler, name, claimed));
        }
        return claimed;
    }

    /**
     * A producer stops working on a request: its claimed amount is open again.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the request id
     * @param settler    the producer settler
     */
    public static void release(SettlementData data, Settlement settlement, UUID id, UUID settler) {
        update(data, settlement, id, found -> found.withoutClaim(settler, true));
    }

    /**
     * A producer stops working altogether (job switch, end of day, left the JobBlock, died): all its claims in all
     * settlements of the dimension are released.
     *
     * @param data    the dimension's settlement data
     * @param settler the producer settler
     */
    public static void releaseAll(SettlementData data, UUID settler) {
        for (Settlement settlement : data.settlements()) {
            for (Request request : List.copyOf(settlement.requests())) {
                if (request.claimOf(settler).isPresent()) {
                    release(data, settlement, request.id(), settler);
                }
            }
        }
    }

    /**
     * A producer delivered its claimed amount: the claim ends without reopening it; a request with nothing open and no
     * claim left is done and removed. The posting Clerk checks its rules right after the delivery.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the request id
     * @param settler    the producer settler
     */
    public static void finish(SettlementData data, Settlement settlement, UUID id, UUID settler) {
        update(data, settlement, id, found -> found.withoutClaim(settler, false));
    }

    /**
     * Sets what a producer misses to work on a request (shown as "Missing: 6× Cobblestone"); empty clears it.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the request id
     * @param missing    the missing input
     */
    public static void setMissing(SettlementData data, Settlement settlement, UUID id, Optional<Request.Missing> missing) {
        if (get(settlement, id).filter(found -> !found.missing().equals(missing)).isPresent()) {
            update(data, settlement, id, found -> found.withMissing(missing));
        }
    }

    /**
     * Withdraws a request and its sub-requests: nothing is open any more; running claims are finished and delivered
     * (the request goes once they are).
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the request id
     */
    public static void withdraw(SettlementData data, Settlement settlement, UUID id) {
        update(data, settlement, id, found -> found.withAmount(0));
        for (Request child : List.copyOf(settlement.requests())) {
            if (child.parent().filter(id::equals).isPresent()) {
                withdraw(data, settlement, child.id());
            }
        }
    }

    /**
     * Changes a request's priority, and with it the priority of its sub-requests.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param id         the request id
     * @param priority   the new priority
     */
    static void setPriority(SettlementData data, Settlement settlement, UUID id, int priority) {
        if (get(settlement, id).filter(found -> found.priority() != priority).isEmpty()) {
            return;
        }
        update(data, settlement, id, found -> found.withPriority(priority));
        for (Request child : List.copyOf(settlement.requests())) {
            if (child.parent().filter(id::equals).isPresent()) {
                setPriority(data, settlement, child.id(), priority);
            }
        }
    }

    /** Adds a request (a stock rule posted it). */
    static void add(SettlementData data, Settlement settlement, Request request) {
        settlement.mutableRequests().add(request);
        data.setDirty();
    }

    /**
     * Replaces a request; a request that is done afterwards is removed.
     */
    static void update(SettlementData data, Settlement settlement, UUID id, UnaryOperator<Request> change) {
        List<Request> requests = settlement.mutableRequests();
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            if (request.id().equals(id)) {
                Request changed = change.apply(request);
                if (changed.done()) {
                    requests.remove(i);
                } else if (!changed.equals(request)) {
                    requests.set(i, changed);
                } else {
                    return;
                }
                data.setDirty();
                return;
            }
        }
    }
}
