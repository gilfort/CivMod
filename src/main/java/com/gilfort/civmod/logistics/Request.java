package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;

/**
 * A request of the settlement (vault {@code Jobs/Clerk.md}, {@code Systems/Producing Jobs.md}): "produce
 * {@code amount} more of {@code item}". Posted by a Clerk's stock rule (at most one open request per rule) or by
 * another source (priority 0; a sub-request inherits the priority of its parent). Producers claim parts of it
 * ({@link Claim}); {@link #amount()} is what is still open, {@link #crafting()} what is being crafted.
 *
 * @param id       the request id
 * @param item     the requested thing (item and relevant components)
 * @param amount   the open amount nobody claimed yet
 * @param priority the priority (higher first)
 * @param target   the Clerk the result is delivered to, empty if the poster has none
 * @param rule     the slot of the stock rule that posted it, empty for other sources
 * @param parent   the request this is a sub-request of
 * @param claims   the producers crafting parts of it
 * @param missing  every base input a producer misses to work on it ("Missing: 6× Cobblestone, 4× Stick"), set by the
 *                 producer
 * @param reservedFor who the result is held for once it is in the Clerk (the tool supply: the settler the tool is for),
 *                 empty for a request whose result is free stock
 * @param oneTime  whether the player ordered it once at its target Clerk instead of a stock rule (vault
 *                 {@code Jobs/Clerk.md}); it stays open until it is delivered or withdrawn
 */
public record Request(UUID id, ItemKey item, int amount, int priority, Optional<BlockPos> target, Optional<Integer> rule, Optional<UUID> parent,
        List<Claim> claims, List<Missing> missing, Optional<ReservedFor> reservedFor, boolean oneTime) {

    /**
     * A part of a request a producer is crafting.
     *
     * @param settler the producer settler
     * @param name    its name (for the Tasks tab)
     * @param amount  the claimed amount
     */
    public record Claim(UUID settler, String name, int amount) {

        /** Save codec. */
        public static final Codec<Claim> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("settler").forGetter(Claim::settler),
                Codec.STRING.fieldOf("name").forGetter(Claim::name),
                Codec.INT.fieldOf("amount").forGetter(Claim::amount)
        ).apply(instance, Claim::new));
    }

    /**
     * An input a producer misses for a request.
     *
     * @param item  the missing thing
     * @param count how many are missing
     */
    public record Missing(ItemKey item, int count) {

        /**
         * Fuel of any kind (vault {@code Jobs/Smelter.md}): a producer burning fuel with several or no fuels listed
         * misses fuel, not one item ("Missing: Fuel").
         */
        public static final Missing ANY_FUEL = new Missing(ItemKey.EMPTY, 0);

        /**
         * @return whether this is {@link #ANY_FUEL}
         */
        public boolean anyFuel() {
            return equals(ANY_FUEL);
        }

        /**
         * @return the text of the request line and the job status ("6× Cobblestone", "Fuel")
         */
        public Component text() {
            return anyFuel() ? Component.translatable("civmod.gui.clerk.request.missing_fuel")
                    : Component.translatable("civmod.gui.clerk.request.missing_input", count, item.toStack().getHoverName());
        }

        /** Save codec. */
        public static final Codec<Missing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemKey.CODEC.fieldOf("item").forGetter(Missing::item),
                Codec.INT.fieldOf("count").forGetter(Missing::count)
        ).apply(instance, Missing::new));
    }

    /** Save codec. */
    public static final Codec<Request> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Request::id),
            ItemKey.CODEC.fieldOf("item").forGetter(Request::item),
            Codec.INT.fieldOf("amount").forGetter(Request::amount),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(Request::priority),
            BlockPos.CODEC.optionalFieldOf("target").forGetter(Request::target),
            // Saves before the review fixes of #54 wrote -1 for "no rule".
            Codec.INT.optionalFieldOf("rule").xmap(slot -> slot.filter(found -> found >= 0), slot -> slot).forGetter(Request::rule),
            UUIDUtil.CODEC.optionalFieldOf("parent").forGetter(Request::parent),
            Claim.CODEC.listOf().optionalFieldOf("claims", List.of()).forGetter(Request::claims),
            // Saves before the review fixes of #54 hold one missing input instead of a list.
            Codec.withAlternative(Missing.CODEC.listOf(), Missing.CODEC.xmap(List::of, list -> list.getFirst()))
                    .optionalFieldOf("missing", List.of()).forGetter(Request::missing),
            ReservedFor.CODEC.optionalFieldOf("reserved_for").forGetter(Request::reservedFor),
            Codec.BOOL.optionalFieldOf("one_time", false).forGetter(Request::oneTime)
    ).apply(instance, Request::new));

    /**
     * A request that no player ordered once: a stock rule, a sub-request or the tool supply posted it.
     *
     * @param id          the request id
     * @param item        the requested thing (item and relevant components)
     * @param amount      the open amount
     * @param priority    the priority
     * @param target      the Clerk the result is delivered to
     * @param rule        the posting rule slot, empty otherwise
     * @param parent      the parent request
     * @param claims      the claims
     * @param missing     what a producer misses
     * @param reservedFor who the result is held for
     */
    public Request(UUID id, ItemKey item, int amount, int priority, Optional<BlockPos> target, Optional<Integer> rule, Optional<UUID> parent,
            List<Claim> claims, List<Missing> missing, Optional<ReservedFor> reservedFor) {
        this(id, item, amount, priority, target, rule, parent, claims, missing, reservedFor, false);
    }

    /**
     * @param id       the request id
     * @param item     the requested thing (item and relevant components)
     * @param amount   the open amount
     * @param priority the priority
     * @param target   the Clerk the result is delivered to
     * @param rule     the posting rule slot, empty otherwise
     * @param parent   the parent request
     * @param claims   the claims
     * @param missing  what a producer misses
     * @param reservedFor who the result is held for
     * @param oneTime     whether the player ordered it once
     */
    public Request {
        amount = Math.max(0, amount);
        claims = List.copyOf(claims);
        missing = List.copyOf(missing);
    }

    /**
     * @param clerk a Clerk JobBlock
     * @param slot  a rule slot
     * @return whether the rule in that slot of that Clerk posted this request
     */
    public boolean postedBy(BlockPos clerk, int slot) {
        return rule.filter(posted -> posted == slot).isPresent() && target.filter(clerk::equals).isPresent();
    }

    /**
     * @return the amount being crafted (sum of the claims)
     */
    public int crafting() {
        return claims.stream().mapToInt(Claim::amount).sum();
    }

    /**
     * @param settler a producer settler
     * @return its claim on this request, if any
     */
    public Optional<Claim> claimOf(UUID settler) {
        return claims.stream().filter(claim -> claim.settler().equals(settler)).findFirst();
    }

    /**
     * @return whether nothing is open and nobody crafts it (the request is done)
     */
    public boolean done() {
        return amount == 0 && claims.isEmpty();
    }

    /**
     * @param newAmount the open amount
     * @return a copy with another open amount
     */
    public Request withAmount(int newAmount) {
        return new Request(id, item, newAmount, priority, target, rule, parent, claims, missing, reservedFor, oneTime);
    }

    /**
     * @param newPriority the priority
     * @return a copy with another priority
     */
    public Request withPriority(int newPriority) {
        return new Request(id, item, amount, newPriority, target, rule, parent, claims, missing, reservedFor, oneTime);
    }

    /**
     * @return a copy without a target Clerk (the result goes to the producer's own drop-off)
     */
    public Request withoutTarget() {
        return new Request(id, item, amount, priority, Optional.empty(), rule, parent, claims, missing, reservedFor, oneTime);
    }

    /**
     * @return a copy whose result is free stock again (the holder no longer needs it)
     */
    public Request withoutReservation() {
        return new Request(id, item, amount, priority, target, rule, parent, claims, missing, Optional.empty(), oneTime);
    }

    /**
     * @param newMissing what a producer misses, empty if nothing
     * @return a copy with other missing inputs
     */
    public Request withMissing(List<Missing> newMissing) {
        return new Request(id, item, amount, priority, target, rule, parent, claims, newMissing, reservedFor, oneTime);
    }

    /**
     * Adds to a settler's claim (or adds a claim) and takes the amount off the open amount.
     *
     * @param settler the producer settler
     * @param name    its name
     * @param claimed the amount it claims (at most {@link #amount()})
     * @return the changed request
     */
    public Request withClaim(UUID settler, String name, int claimed) {
        List<Claim> changed = new ArrayList<>();
        int total = claimed;
        for (Claim claim : claims) {
            if (claim.settler().equals(settler)) {
                total += claim.amount();
            } else {
                changed.add(claim);
            }
        }
        changed.add(new Claim(settler, name, total));
        return new Request(id, item, amount - claimed, priority, target, rule, parent, changed, List.of(), reservedFor, oneTime);
    }

    /**
     * Removes a settler's claim, its whole amount open again (the settler released it).
     *
     * @param settler the producer settler
     * @return the changed request
     */
    public Request withReleasedClaim(UUID settler) {
        return withoutClaim(settler, Claim::amount);
    }

    /**
     * Removes a settler's claim after it delivered all of it: the amount is gone.
     *
     * @param settler the producer settler
     * @return the changed request
     */
    public Request withDeliveredInFull(UUID settler) {
        return withoutClaim(settler, claim -> 0);
    }

    /**
     * Removes a settler's claim after it delivered part of it: the delivered amount is gone, the rest is open again.
     *
     * @param settler   the producer settler
     * @param delivered how many items it delivered (a claim of at most this is delivered in full)
     * @return the changed request
     */
    public Request withDelivered(UUID settler, int delivered) {
        return withoutClaim(settler, claim -> Math.max(0, claim.amount() - delivered));
    }

    private Request withoutClaim(UUID settler, ToIntFunction<Claim> reopened) {
        int back = claimOf(settler).map(reopened::applyAsInt).orElse(0);
        List<Claim> changed = claims.stream().filter(claim -> !claim.settler().equals(settler)).toList();
        return new Request(id, item, amount + back, priority, target, rule, parent, changed, missing, reservedFor, oneTime);
    }
}
