package com.gilfort.civmod.registry;

import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.menu.DebugMenu;
import com.gilfort.civmod.menu.JobBlockMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for all menu types of CivMod.
 */
public final class ModMenuTypes {

    /** Deferred register for menu types in the {@code civmod} namespace. */
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, CivMod.MOD_ID);

    /** Development-only debug menu showcasing the GUI building blocks. */
    public static final Supplier<MenuType<DebugMenu>> DEBUG = MENU_TYPES.register(
            "debug", () -> new MenuType<>(DebugMenu::new, FeatureFlags.VANILLA_SET));

    /** Menu of all JobBlocks; the client receives a {@link com.gilfort.civmod.menu.JobBlockInfo} snapshot. */
    public static final Supplier<MenuType<JobBlockMenu>> JOB_BLOCK = MENU_TYPES.register(
            "job_block", () -> IMenuTypeExtension.create(JobBlockMenu::new));

    private ModMenuTypes() {
    }

    /**
     * Attaches the menu type register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);
    }
}
