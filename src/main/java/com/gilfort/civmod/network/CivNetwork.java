package com.gilfort.civmod.network;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.command.CivModCommand;
import com.gilfort.civmod.item.JobPicker;
import com.gilfort.civmod.job.JobDrops;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.menu.FuelTab;
import com.gilfort.civmod.menu.FuelTabData;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.menu.PatternsTab;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.menu.StockTab;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.settlement.JobPointsDisplay;
import com.gilfort.civmod.settlement.SettlementOutline;

import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Central registration of all custom network payloads of CivMod.
 * <p>
 * Bump {@link #PROTOCOL_VERSION} whenever a payload changes incompatibly.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CivNetwork {

    /** Network protocol version; client and server must match. */
    public static final String PROTOCOL_VERSION = "5";

    private CivNetwork() {
    }

    /**
     * Client → server: reopen the debug GUI (sent from the debug popup). Ignored in production and without
     * gamemaster permission.
     */
    public record OpenDebugMenu() implements CustomPacketPayload {

        /** Singleton instance; the payload has no data. */
        public static final OpenDebugMenu INSTANCE = new OpenDebugMenu();

        /** Payload type. */
        public static final Type<OpenDebugMenu> TYPE = new Type<>(CivMod.id("open_debug_menu"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenDebugMenu> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<OpenDebugMenu> type() {
            return TYPE;
        }
    }

    /**
     * Registers all payloads.
     *
     * @param event the payload registration event
     */
    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(OpenDebugMenu.TYPE, OpenDebugMenu.STREAM_CODEC, (payload, context) -> {
            if (CivModCommand.isDebugEnabled() && context.player() instanceof ServerPlayer player
                    && Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
                CivModCommand.openDebugMenu(player);
            }
        });
        registrar.playToServer(ClerkTabData.Edit.TYPE, ClerkTabData.Edit.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof JobBlockMenu menu && menu.containerId == payload.containerId()) {
                menu.tab(StockTab.class).ifPresent(tab -> tab.editRule(payload.slot(), payload.rule()));
            }
        });
        registrar.playToServer(PatternsTabData.Edit.TYPE, PatternsTabData.Edit.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof JobBlockMenu menu && menu.containerId == payload.containerId()) {
                PatternsTab.of(menu, payload.job()).ifPresent(tab -> tab.edit(payload));
            }
        });
        registrar.playToServer(FuelTabData.Edit.TYPE, FuelTabData.Edit.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof JobBlockMenu menu && menu.containerId == payload.containerId()) {
                menu.tab(FuelTab.class).ifPresent(tab -> tab.edit(payload.op(), payload.slot(), payload.item()));
            }
        });
        registrar.playToServer(FoundTabData.Toggle.TYPE, FoundTabData.Toggle.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof JobBlockMenu menu && menu.containerId == payload.containerId()) {
                menu.tab(FoundTab.class).ifPresent(tab -> tab.toggleExcluded(payload.job(), payload.resource()));
            }
        });
        registrar.playToServer(JobPicker.Choose.TYPE, JobPicker.Choose.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                JobPicker.choose(player, payload);
            }
        });
        registrar.playToServer(SettlerMenu.SwitchJob.TYPE, SettlerMenu.SwitchJob.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        payload.handle(player);
                    }
                });
        // Client handler is registered in CivModClient (RegisterClientPayloadHandlersEvent).
        registrar.playToClient(JobPicker.Open.TYPE, JobPicker.Open.STREAM_CODEC);
        registrar.playToClient(PatternsTabData.Sync.TYPE, PatternsTabData.Sync.STREAM_CODEC);
        registrar.playToClient(StationTypesTabData.Sync.TYPE, StationTypesTabData.Sync.STREAM_CODEC);
        registrar.playToClient(FuelTabData.Sync.TYPE, FuelTabData.Sync.STREAM_CODEC);
        registrar.playToClient(SettlementOutline.Sync.TYPE, SettlementOutline.Sync.STREAM_CODEC);
        registrar.playToClient(JobPointsDisplay.Sync.TYPE, JobPointsDisplay.Sync.STREAM_CODEC);
        registrar.playToClient(ClerkTabData.Sync.TYPE, ClerkTabData.Sync.STREAM_CODEC);
        registrar.playToClient(FoundTabData.Sync.TYPE, FoundTabData.Sync.STREAM_CODEC);
        registrar.playToClient(JobBlockInfo.Sync.TYPE, JobBlockInfo.Sync.STREAM_CODEC);
        registrar.playToClient(ThoughtBubble.Show.TYPE, ThoughtBubble.Show.STREAM_CODEC);
        registrar.playToClient(JobDrops.Sync.TYPE, JobDrops.Sync.STREAM_CODEC);
    }
}
