package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Warehouses;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What the Clerk's "Stock", "Requests" and "Deliveries" tabs show (vault {@code Jobs/Clerk.md},
 * {@code Jobs/Deliveryman.md}): its stock rules and the requests they posted with their status, the open Delivery
 * tasks to it (a Warehouse: to every Clerk it serves) and, for the info tab, its Served Clerks or the Warehouse serving
 * it. The server sends it while the menu is open whenever it changed ({@link Sync}); the client edits rules with
 * {@link Edit}.
 *
 * @param level      the Clerk JobBlock level (9 rules per level are active)
 * @param rules      the stock rules
 * @param requests   the requests of the rules, highest priority first
 * @param deliveries the open Delivery tasks, in creation order
 * @param served     a Warehouse's Served Clerks (inactive ones marked), empty for any other Clerk
 * @param servedBy   the Warehouse serving this Clerk, if any
 * @param nowhere    the slots of the rules whose surplus has nowhere to go ("Surplus: nowhere to go")
 */
public record ClerkTabData(int level, List<StockRule> rules, List<RequestRow> requests, List<DeliveryRow> deliveries, List<Component> served,
        Optional<Component> servedBy, List<Integer> nowhere) {

    /** Nothing (any other JobBlock, or before the first sync). */
    public static final ClerkTabData EMPTY = new ClerkTabData(1, List.of(), List.of(), List.of(), List.of(), Optional.empty(), List.of());

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ClerkTabData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClerkTabData::level,
            StockRule.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTabData::rules,
            RequestRow.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTabData::requests,
            DeliveryRow.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTabData::deliveries,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTabData::served,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs::optional), ClerkTabData::servedBy,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), ClerkTabData::nowhere,
            ClerkTabData::new);

    /**
     * @param level      the Clerk JobBlock level
     * @param rules      the stock rules
     * @param requests   the requests
     * @param deliveries the Delivery tasks
     * @param served     the Served Clerks
     * @param servedBy   the serving Warehouse
     * @param nowhere    the rule slots whose surplus has nowhere to go
     */
    public ClerkTabData {
        rules = List.copyOf(rules);
        requests = List.copyOf(requests);
        deliveries = List.copyOf(deliveries);
        served = List.copyOf(served);
        nowhere = List.copyOf(nowhere);
    }

    /**
     * One row of the Deliveries tab.
     *
     * @param icon   the source block
     * @param source the source's name (the job of its JobBlock)
     * @param items  how many items wait there
     * @param status the Deliveryman on it, "Waiting" or "No Deliveryman"
     */
    public record DeliveryRow(ItemStack icon, Component source, int items, Component status) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, DeliveryRow> STREAM_CODEC = StreamCodec.composite(
                ItemStack.OPTIONAL_STREAM_CODEC, DeliveryRow::icon,
                ComponentSerialization.STREAM_CODEC, DeliveryRow::source,
                ByteBufCodecs.VAR_INT, DeliveryRow::items,
                ComponentSerialization.STREAM_CODEC, DeliveryRow::status,
                DeliveryRow::new);

        // Item stacks have no value equality; the tab is only resent when a row really changed.
        @Override
        public boolean equals(Object other) {
            return other instanceof DeliveryRow row && ItemStack.matches(icon, row.icon) && source.equals(row.source) && items == row.items
                    && status.equals(row.status);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(icon.getItem(), source, items, status);
        }
    }

    /**
     * One row of the Requests tab.
     *
     * @param item     the requested item
     * @param amount   the missing amount (open plus being crafted)
     * @param priority the priority
     * @param status   why it is (not) fulfilled
     */
    public record RequestRow(Item item, int amount, int priority, Component status) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestRow> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.registry(Registries.ITEM), RequestRow::item,
                ByteBufCodecs.VAR_INT, RequestRow::amount,
                ByteBufCodecs.VAR_INT, RequestRow::priority,
                ComponentSerialization.STREAM_CODEC, RequestRow::status,
                RequestRow::new);
    }

    /**
     * @param slot a rule slot
     * @return the rule in that slot
     */
    public Optional<StockRule> rule(int slot) {
        return rules.stream().filter(rule -> rule.slot() == slot).findFirst();
    }

    /**
     * The rule slot Shift + right-click on an inventory item fills (vault {@code Systems/GUI.md}).
     *
     * @return the first active rule slot without a rule, {@code -1} if every one holds a rule
     */
    public int nextEmptyGhostSlot() {
        for (int slot = 0; slot < StockRule.slots(level); slot++) {
            if (rule(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * @param level the level
     * @param clerk the Clerk JobBlock position
     * @return the current tab of that Clerk ({@link #EMPTY} if it is none)
     */
    public static ClerkTabData of(ServerLevel level, BlockPos clerk) {
        Optional<Settlement> settlement = SettlementData.get(level).settlementAt(clerk);
        Optional<SettlementBlock> block = settlement.flatMap(found -> found.block(clerk)).filter(ClerkStorage::isClerk);
        if (block.isEmpty()) {
            return EMPTY;
        }
        List<RequestRow> rows = Requests.ofClerk(settlement.get(), clerk).stream()
                .map(request -> new RequestRow(request.item(), request.amount() + request.crafting(), request.priority(),
                        status(level, settlement.get(), request)))
                .toList();
        return new ClerkTabData(block.get().level(), block.get().stockRules(), rows, deliveries(level, settlement.get(), block.get()),
                served(settlement.get(), block.get()), Warehouses.servedBy(settlement.get(), clerk).map(ClerkStorage::name),
                StockRules.surplusNowhere(level, clerk));
    }

    /** The tasks to this Clerk; a Warehouse shows those of every Clerk it serves. */
    private static List<DeliveryRow> deliveries(ServerLevel level, Settlement settlement, SettlementBlock clerk) {
        boolean warehouse = Warehouses.warehouseOf(settlement, clerk.pos()).filter(clerk::equals).isPresent();
        List<BlockPos> clerks = warehouse ? Warehouses.handled(settlement, clerk) : List.of(clerk.pos());
        long day = DayClock.day(level);
        return Deliveries.toClerks(settlement, clerks).stream().map(task -> {
            Optional<JobBlockSite> site = JobBlockSite.at(level, task.jobBlock());
            Component source = site.map(found -> ClerkStorage.isClerk(found.block()) ? ClerkStorage.name(found.block())
                    : found.block().jobType().displayName()).orElse(Component.literal(task.source().toShortString()));
            int items;
            ItemStack icon;
            if (task.input().isPresent()) {
                // An input delivery: its first input and the item count, "<store> → <producer>"; surplus "<Clerk> → <Warehouse>".
                DeliveryTask.Input input = task.input().get();
                items = input.count();
                icon = input.items().keySet().stream().findFirst().map(ItemStack::new).orElse(ItemStack.EMPTY);
                Component storeName = settlement.block(task.source()).map(ClerkStorage::name).orElse(Component.literal(task.source().toShortString()));
                if (task.surplus()) {
                    Component to = settlement.block(input.to()).map(ClerkStorage::name).orElse(Component.literal(input.to().toShortString()));
                    source = Component.translatable("civmod.gui.clerk.delivery.surplus", storeName, to);
                } else {
                    source = Component.translatable("civmod.gui.clerk.delivery.input", storeName, source);
                }
            } else if (task.result().isPresent()) {
                // A result delivery: the result and its count, "<producer> → <requesting Clerk>".
                DeliveryTask.Result result = task.result().get();
                items = result.count();
                icon = new ItemStack(result.item());
                Component clerkName = settlement.block(result.clerk()).map(ClerkStorage::name).orElse(Component.literal(result.clerk().toShortString()));
                source = Component.translatable("civmod.gui.clerk.delivery.result", source, clerkName);
            } else {
                items = site.filter(found -> level.isLoaded(task.source()))
                        .map(found -> Pickups.contents(level, found, task.source()).items()).orElse(0);
                icon = level.isLoaded(task.source()) ? new ItemStack(level.getBlockState(task.source()).getBlock().asItem()) : ItemStack.EMPTY;
            }
            Component status = task.takerOn(day).<Component>map(taker -> Component.literal(taker.name()))
                    .orElseGet(() -> Component.translatable(task.carried() ? "civmod.gui.clerk.delivery.carried"
                            : Deliveries.warehouseOf(settlement, task).filter(Warehouses::hasDeliverymen).isPresent()
                                    ? "civmod.gui.clerk.delivery.waiting" : "civmod.gui.clerk.delivery.no_deliveryman"));
            return new DeliveryRow(icon, source, items, status);
        }).toList();
    }

    /** A Warehouse's Served Clerks; those beyond its level are marked inactive. */
    private static List<Component> served(Settlement settlement, SettlementBlock warehouse) {
        List<BlockPos> active = Warehouses.activeServed(warehouse);
        return warehouse.served().stream().<Component>map(pos -> {
            Component name = settlement.block(pos).map(ClerkStorage::name).orElse(Component.literal(pos.toShortString()));
            return active.contains(pos) ? name : Component.translatable("civmod.gui.clerk.served.inactive", name).withStyle(ChatFormatting.GRAY);
        }).toList();
    }

    /**
     * "Crafting – <name>", "Missing: 6× Cobblestone, 4× Stick", "No Craftsperson can make this" or "Waiting".
     */
    private static Component status(ServerLevel level, Settlement settlement, Request request) {
        if (!request.claims().isEmpty()) {
            return Component.translatable("civmod.gui.clerk.request.crafting", request.claims().get(0).name());
        }
        if (!request.missing().isEmpty()) {
            MutableComponent inputs = Component.empty();
            for (Request.Missing missing : request.missing()) {
                if (!inputs.getSiblings().isEmpty()) {
                    inputs.append(", ");
                }
                inputs.append(missing.text());
            }
            return Component.translatable("civmod.gui.clerk.request.missing", inputs);
        }
        if (!Producers.canMake(level, settlement, request.item())) {
            return Component.translatable("civmod.gui.clerk.request.no_producer");
        }
        return Component.translatable("civmod.gui.clerk.request.waiting");
    }

    /**
     * Server → client: the Clerk tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, ClerkTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("clerk_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                ClerkTabData.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: set the rule in a slot of the open Clerk menu, or delete it (empty rule).
     *
     * @param containerId the menu's container id
     * @param slot        the rule slot
     * @param rule        the new rule, empty to delete it
     */
    public record Edit(int containerId, int slot, Optional<StockRule> rule) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Edit> TYPE = new Type<>(CivMod.id("edit_stock_rule"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Edit::containerId,
                ByteBufCodecs.VAR_INT, Edit::slot,
                ByteBufCodecs.optional(StockRule.STREAM_CODEC), Edit::rule,
                Edit::new);

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }
}
