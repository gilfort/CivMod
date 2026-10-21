package com.gilfort.civmod.client;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.client.gui.DebugScreen;
import com.gilfort.civmod.client.gui.JobBlockScreen;
import com.gilfort.civmod.client.gui.JobPickerScreen;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.client.gui.SettlerScreen;
import com.gilfort.civmod.item.JobPicker;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.registry.ModBlockEntityTypes;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModMenuTypes;
import com.gilfort.civmod.settlement.JobPointsDisplay;
import com.gilfort.civmod.settlement.SettlementOutline;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Client-only entry point of CivMod.
 * <p>
 * This class is never loaded on a dedicated server, so referencing client classes here is safe.
 */
@Mod(value = CivMod.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class CivModClient {

    /**
     * Creates the client-side mod instance and registers the config screen (Mods menu).
     *
     * @param modEventBus  the mod-specific event bus, injected by FML
     * @param modContainer the container describing this mod, injected by FML
     */
    public CivModClient(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, com.gilfort.civmod.config.CivModClientConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(CivModClient::onItemTooltip);
    }

    /**
     * Runs client setup after registration has finished.
     *
     * @param event the client setup event
     */
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        CivMod.LOGGER.info("CivMod client setup");
    }

    /**
     * Binds the menu types to their screens.
     *
     * @param event the menu screen registration event
     */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.DEBUG.get(), DebugScreen::new);
        event.register(ModMenuTypes.JOB_BLOCK.get(), JobBlockScreen::new);
        event.register(ModMenuTypes.SETTLER.get(), SettlerScreen::new);
    }

    /**
     * Registers entity and block entity renderers.
     *
     * @param event the renderer registration event
     */
    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntityTypes.JOB_BLOCK.get(), JobBlockRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.SETTLER.get(), SettlerRenderer::new);
    }

    /**
     * Registers handlers for server → client payloads.
     *
     * @param event the client payload registration event
     */
    @SubscribeEvent
    static void onRegisterClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(SettlementOutline.Sync.TYPE, SettlementOutlineRenderer::handle);
        event.register(JobPointsDisplay.Sync.TYPE, JobPointsRenderer::handle);
        event.register(ClerkTabData.Sync.TYPE, JobBlockScreen::handleClerkTab);
        event.register(JobPicker.Open.TYPE, JobPickerScreen::handle);
        event.register(com.gilfort.civmod.menu.PatternsTabData.Sync.TYPE, JobBlockScreen::handlePatternTab);
        event.register(StationTypesTabData.Sync.TYPE, JobBlockScreen::handleStationTypeTab);
        event.register(com.gilfort.civmod.menu.FoundTabData.Sync.TYPE, JobBlockScreen::handleFoundTab);
        event.register(com.gilfort.civmod.menu.JobBlockInfo.Sync.TYPE, JobBlockScreen::handleInfo);
        event.register(com.gilfort.civmod.npc.ThoughtBubble.Show.TYPE, ThoughtBubbleRenderer::handle);
    }

    private static void onItemTooltip(ItemTooltipEvent event) {
        if (JobPointsDisplay.isScroll(event.getItemStack())) {
            Component line = ScrollOfAssignmentItem.binding(event.getItemStack())
                    .map(binding -> binding.jobBlock().isPresent()
                            ? Component.translatable("civmod.tooltip.scroll.bound_job_block", binding.label())
                            : Component.translatable("civmod.tooltip.scroll.bound_settler", binding.label()))
                    .orElse(Component.translatable("civmod.tooltip.scroll.unbound"));
            event.getToolTip().add(1, line.copy().withStyle(ChatFormatting.GRAY));
            event.getToolTip().add(2, Component.translatable("civmod.tooltip.scroll.help.bind").withStyle(ChatFormatting.DARK_GRAY));
            event.getToolTip().add(3, Component.translatable("civmod.tooltip.scroll.help.clear").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (SettlementOutline.isJobBlockItem(event.getItemStack())) {
            int level = event.getItemStack().getOrDefault(ModDataComponents.JOB_LEVEL.get(), SettlementRules.MIN_LEVEL);
            event.getToolTip().add(1, Component.translatable("civmod.tooltip.job_level", level, SettlementRules.MAX_LEVEL)
                    .withStyle(ChatFormatting.GRAY));
            Holder<Item> starterTool =event.getItemStack().get(ModDataComponents.STARTER_TOOL.get());
            if (starterTool != null) {
                event.getToolTip().add(2, Component.translatable("civmod.tooltip.starter_tool", new ItemStack(starterTool).getHoverName())
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
