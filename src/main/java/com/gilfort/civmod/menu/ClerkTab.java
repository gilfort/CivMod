package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

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
 * What the Clerk's "Rules" and "Requests" tabs show (vault {@code Jobs/Clerk.md}): its stock rules and the requests
 * they posted with their status. The server sends it while the menu is open whenever it changed ({@link Sync}); the
 * client edits rules with {@link Edit}.
 *
 * @param level    the Clerk JobBlock level (9 rules per level are active)
 * @param rules    the stock rules
 * @param requests the requests of the rules, highest priority first
 */
public record ClerkTab(int level, List<StockRule> rules, List<RequestRow> requests) {

    /** Nothing (any other JobBlock, or before the first sync). */
    public static final ClerkTab EMPTY = new ClerkTab(1, List.of(), List.of());

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ClerkTab> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClerkTab::level,
            StockRule.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTab::rules,
            RequestRow.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTab::requests,
            ClerkTab::new);

    /**
     * @param level    the Clerk JobBlock level
     * @param rules    the stock rules
     * @param requests the requests
     */
    public ClerkTab {
        rules = List.copyOf(rules);
        requests = List.copyOf(requests);
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
     * @param level the level
     * @param clerk the Clerk JobBlock position
     * @return the current tab of that Clerk ({@link #EMPTY} if it is none)
     */
    public static ClerkTab of(ServerLevel level, BlockPos clerk) {
        Optional<Settlement> settlement = SettlementData.get(level).settlementAt(clerk);
        Optional<SettlementBlock> block = settlement.flatMap(found -> found.block(clerk)).filter(ClerkStorage::isClerk);
        if (block.isEmpty()) {
            return EMPTY;
        }
        List<RequestRow> rows = Requests.ofClerk(settlement.get(), clerk).stream()
                .map(request -> new RequestRow(request.item(), request.amount() + request.crafting(), request.priority(),
                        status(level, settlement.get(), request)))
                .toList();
        return new ClerkTab(block.get().level(), block.get().stockRules(), rows);
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
                inputs.append(Component.translatable("civmod.gui.clerk.request.missing_input", missing.count(),
                        new ItemStack(missing.item()).getHoverName()));
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
    public record Sync(int containerId, ClerkTab tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("clerk_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                ClerkTab.STREAM_CODEC, Sync::tab,
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
