package com.gilfort.civmod.client;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.client.gui.DebugScreen;
import com.gilfort.civmod.registry.ModMenuTypes;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

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
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
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
    }
}
