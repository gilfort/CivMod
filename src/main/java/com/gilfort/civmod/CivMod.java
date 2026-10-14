package com.gilfort.civmod;

import org.slf4j.Logger;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.registry.ModBlockEntityTypes;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModCreativeModeTabs;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.registry.ModMenuTypes;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Main entry point of CivMod.
 * <p>
 * Constructed by FML during mod loading on both physical sides. Responsible for wiring all
 * deferred registers and lifecycle listeners onto the mod event bus.
 */
@Mod(CivMod.MOD_ID)
public final class CivMod {

    /** The mod id; must match {@code mod_id} in {@code gradle.properties}. */
    public static final String MOD_ID = "civmod";

    /** Shared logger for the whole mod. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Creates the mod instance and registers all content.
     *
     * @param modEventBus  the mod-specific event bus, injected by FML
     * @param modContainer the container describing this mod, injected by FML
     */
    public CivMod(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModBlockEntityTypes.register(modEventBus);
        ModDataComponents.register(modEventBus);
        ModEntityTypes.register(modEventBus);
        ModJobs.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.SERVER, CivModConfig.SPEC);

        modEventBus.addListener(this::commonSetup);
    }

    /**
     * Runs common (both sides) setup after registration has finished.
     *
     * @param event the common setup event
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("CivMod common setup");
    }

    /**
     * Creates an identifier in the {@code civmod} namespace.
     *
     * @param path the identifier path
     * @return the namespaced identifier
     */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
