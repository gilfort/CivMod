package com.gilfort.civmod.registry;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for the creative mode tabs of CivMod.
 */
public final class ModCreativeModeTabs {

    /** Deferred register for creative mode tabs in the {@code civmod} namespace. */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CivMod.MOD_ID);

    /**
     * The main creative tab. Automatically lists every item registered in {@link ModItems#ITEMS} except render-only
     * items.
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.civmod.main"))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .icon(() -> ModItems.LUMBERJACK_BLOCK.get().getDefaultInstance())
                    .displayItems((parameters, output) ->
                            ModItems.ITEMS.getEntries().stream()
                                    .filter(item -> !ModItems.RENDER_ONLY.contains(item))
                                    .forEach(item -> output.accept(item.get())))
                    .build());

    private ModCreativeModeTabs() {
    }

    /**
     * Attaches the creative tab register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
