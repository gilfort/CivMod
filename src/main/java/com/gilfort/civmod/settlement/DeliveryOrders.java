package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.registry.ModDataComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

/**
 * The "Delivery Orders" of a Clerk JobBlock (vault {@code Jobs/Deliveryman.md}): the filled Delivery Slips in its slots,
 * the clock of each slip (cooldown, empty visits, round robin position) and the "Auto" switch of its Deliverymen. The
 * slots hold {@code (level + Ledgers) x 9} slips in use ({@link StockRule#slots(int, int)}, like the stock rule slots); the order is the
 * priority. Kept in the {@link SettlementBlock}, so it is saved with the settlement.
 */
public final class DeliveryOrders {

    /** The slots a Clerk JobBlock can have at most: level 5 and 3 Ledgers. */
    public static final int MAX_SLOTS = StockRule.slots(SettlementRules.MAX_LEVEL, SettlementRules.MAX_LEDGERS);

    /**
     * When a slip may be visited again and how its last visits went.
     *
     * @param readyAt     the game time the cooldown ends
     * @param emptyVisits visits in a row that moved nothing
     * @param next        the target a round robin slip serves next
     */
    public record Clock(long readyAt, int emptyVisits, int next) {

        /** A slip never visited. */
        public static final Clock NEW = new Clock(0L, 0, 0);

        /** Save codec. */
        public static final Codec<Clock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("ready_at", 0L).forGetter(Clock::readyAt),
                Codec.INT.optionalFieldOf("empty_visits", 0).forGetter(Clock::emptyVisits),
                Codec.INT.optionalFieldOf("next", 0).forGetter(Clock::next)
        ).apply(instance, Clock::new));
    }

    /**
     * The saved form.
     *
     * @param slips  the slip in each slot (empty stacks for empty slots)
     * @param auto   whether the Deliverymen also take the automatic tasks
     * @param clocks the clock of each slip by its id
     */
    public record Saved(List<ItemStack> slips, boolean auto, Map<UUID, Clock> clocks) {

        /** Save codec. */
        public static final Codec<Saved> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("slips", List.of()).forGetter(Saved::slips),
                Codec.BOOL.optionalFieldOf("auto", true).forGetter(Saved::auto),
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Clock.CODEC).optionalFieldOf("clocks", Map.of()).forGetter(Saved::clocks)
        ).apply(instance, Saved::new));

        /** Nothing saved: no slips, Auto on. */
        public static final Saved NONE = new Saved(List.of(), true, Map.of());
    }

    private final List<ItemStack> slips = new ArrayList<>();
    private boolean auto = true;
    private final Map<UUID, Clock> clocks = new HashMap<>();

    DeliveryOrders() {
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            slips.add(ItemStack.EMPTY);
        }
    }

    /**
     * @param saved the saved form
     */
    DeliveryOrders(Saved saved) {
        this();
        for (int slot = 0; slot < Math.min(MAX_SLOTS, saved.slips().size()); slot++) {
            slips.set(slot, saved.slips().get(slot).copy());
        }
        auto = saved.auto();
        clocks.putAll(saved.clocks());
    }

    /**
     * @return the saved form
     */
    Saved saved() {
        return new Saved(slips.stream().map(ItemStack::copy).toList(), auto, Map.copyOf(clocks));
    }

    /**
     * @param slot a slot, {@code 0} to {@value #MAX_SLOTS} − 1
     * @return the stack in it (not a copy; empty for an empty slot)
     */
    public ItemStack slip(int slot) {
        return slips.get(slot);
    }

    /**
     * @param slot  a slot
     * @param stack the stack to put (empty clears it)
     */
    public void setSlip(int slot, ItemStack stack) {
        slips.set(slot, stack);
        // The clock of a slip that left every slot is of no use any more.
        clocks.keySet().retainAll(slipIds());
    }

    /**
     * @return the id of every filled slip in the slots
     */
    public List<UUID> slipIds() {
        return slips.stream().flatMap(stack -> slip(stack).stream()).map(DeliverySlip::id).toList();
    }

    /**
     * @param stack a stack
     * @return the filled slip it holds, empty for any other stack
     */
    public static Optional<DeliverySlip> slip(ItemStack stack) {
        return Optional.ofNullable(stack.get(ModDataComponents.DELIVERY_SLIP.get())).filter(DeliverySlip::filled);
    }

    /**
     * @return whether the Deliverymen also take the automatic tasks (Auto on, the default)
     */
    public boolean auto() {
        return auto;
    }

    /**
     * @param auto whether the Deliverymen also take the automatic tasks
     */
    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    /**
     * @param slip a slip id
     * @return its clock
     */
    public Clock clock(UUID slip) {
        return clocks.getOrDefault(slip, Clock.NEW);
    }

    /**
     * @param slip  a slip id
     * @param clock its new clock
     */
    public void setClock(UUID slip, Clock clock) {
        clocks.put(slip, clock);
    }
}
