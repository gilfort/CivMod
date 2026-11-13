package com.gilfort.civmod.logistics;

import java.util.UUID;

/**
 * Who an amount of stock in a Clerk is held for (vault {@code Systems/Sub-Requests.md}). Today only a request holds
 * stock: the result of a sub-request waits for its parent. The tool supply adds the kind settler.
 *
 * @param kind what kind of holder it is
 * @param id   the id of that holder (a request id)
 */
public record ReservedFor(Kind kind, UUID id) {

    /** The kinds of holders; every kind says in {@code Holds} when its hold ends. */
    public enum Kind {
        /** A request: the hold ends once the request is gone. */
        REQUEST
    }

    /**
     * @param request a request id
     * @return the holder "this request"
     */
    public static ReservedFor request(UUID request) {
        return new ReservedFor(Kind.REQUEST, request);
    }
}
