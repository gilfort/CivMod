package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A request of the settlement (vault {@code Jobs/Clerk.md}, {@code Systems/Producing Jobs.md}): "produce
 * {@code amount} more of {@code item}". Posted by a Clerk's stock rule (at most one open request per rule) or by
 * another source (priority 0; a sub-request inherits the priority of its parent). Producers claim parts of it
 * ({@link Claim}); {@link #amount()} is what is still open, {@link #crafting()} what is being crafted.
 *
 * @param id       the request id
 * @param item     the requested item
 * @param amount   the open amount nobody claimed yet
 * @param priority the priority (higher first)
 * @param target   the Clerk the result is delivered to, empty if the poster has none
 * @param rule     the slot of the stock rule that posted it, empty for other sources
 * @param parent   the request this is a sub-request of
 * @param claims   the producers crafting parts of it
 * @param missing  every base input a producer misses to work on it ("Missing: 6× Cobblestone, 4× Stick"), set by the
 *                 producer
 */
public record Request(UUID id, Item item, int amount, int priority, Optional<BlockPos> target, Optional<Integer> rule, Optional<UUID> parent,
        List<Claim> claims, List<Missing> missing) {

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
     * @param item  the missing item
     * @param count how many are missing
     */
    public record Missing(Item item, int count) {

        /**
         * Fuel of any kind (vault {@code Jobs/Smelter.md}): a producer burning fuel with several or no fuels listed
         * misses fuel, not one item ("Missing: Fuel").
         */
        public static final Missing ANY_FUEL = new Missing(Items.AIR, 0);

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
                    : Component.translatable("civmod.gui.clerk.request.missing_input", count, new ItemStack(item).getHoverName());
        }

        /** Save codec. */
        public static final Codec<Missing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Missing::item),
                Codec.INT.fieldOf("count").forGetter(Missing::count)
        ).apply(instance, Missing::new));
    }

    /** Save codec. */
    public static final Codec<Request> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Request::id),
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Request::item),
            Codec.INT.fieldOf("amount").forGetter(Request::amount),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(Request::priority),
            BlockPos.CODEC.optionalFieldOf("target").forGetter(Request::target),
            // Saves before the review fixes of #54 wrote -1 for "no rule".
            Codec.INT.optionalFieldOf("rule").xmap(slot -> slot.filter(found -> found >= 0), slot -> slot).forGetter(Request::rule),
            UUIDUtil.CODEC.optionalFieldOf("parent").forGetter(Request::parent),
            Claim.CODEC.listOf().optionalFieldOf("claims", List.of()).forGetter(Request::claims),
            // Saves before the review fixes of #54 hold one missing input instead of a list.
            Codec.withAlternative(Missing.CODEC.listOf(), Missing.CODEC.xmap(List::of, list -> list.getFirst()))
                    .optionalFieldOf("missing", List.of()).forGetter(Request::missing)
    ).apply(instance, Request::new));

    /**
     * @param id       the request id
     * @param item     the requested item
     * @param amount   the open amount
     * @param priority the priority
     * @param target   the Clerk the result is delivered to
     * @param rule     the posting rule slot, empty otherwise
     * @param parent   the parent request
     * @param claims   the claims
     * @param missing  what a producer misses
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
        return new Request(id, item, newAmount, priority, target, rule, parent, claims, missing);
    }

    /**
     * @param newPriority the priority
     * @return a copy with another priority
     */
    public Request withPriority(int newPriority) {
        return new Request(id, item, amount, newPriority, target, rule, parent, claims, missing);
    }

    /**
     * @return a copy without a target Clerk (the result goes to the producer's own drop-off)
     */
    public Request withoutTarget() {
        return new Request(id, item, amount, priority, Optional.empty(), rule, parent, claims, missing);
    }

    /**
     * @param newMissing what a producer misses, empty if nothing
     * @return a copy with other missing inputs
     */
    public Request withMissing(List<Missing> newMissing) {
        return new Request(id, item, amount, priority, target, rule, parent, claims, newMissing);
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
        return new Request(id, item, amount - claimed, priority, target, rule, parent, changed, List.of());
    }

    /**
     * Removes a settler's claim.
     *
     * @param settler  the producer settler
     * @param reopened whether its amount is open again (released) or gone (finished and delivered)
     * @return the changed request
     */
    public Request withoutClaim(UUID settler, boolean reopened) {
        int back = reopened ? claimOf(settler).map(Claim::amount).orElse(0) : 0;
        List<Claim> changed = claims.stream().filter(claim -> !claim.settler().equals(settler)).toList();
        return new Request(id, item, amount + back, priority, target, rule, parent, changed, missing);
    }
}
