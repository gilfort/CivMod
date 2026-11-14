package com.gilfort.civmod.datagen;

import com.gilfort.civmod.CivMod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Registers CivMod's data providers for {@code ./gradlew runData}. The output goes to {@code src/generated/resources}
 * and is committed, so a normal build needs no data run. Every provider writes data the mod could also ship by hand;
 * generating it only saves typing the same file 16 times.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CivModDataGenerators {

    private CivModDataGenerators() {
    }

    @SubscribeEvent
    static void onGatherData(GatherDataEvent.Client event) {
        event.createProvider(ConcreteMixerRecipes::new);
    }
}
