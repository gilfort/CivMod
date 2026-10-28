package com.gilfort.civmod.settlement;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.registry.ModRecipes;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Keeps the innate recipes ({@code civmod:job_crafting}) in step with the datapacks: every Producing library lays them
 * out again when the server starts and after {@code /reload} ({@link Patterns#syncInnate(ServerLevel)}), and the
 * recipes are sent to the clients for the recipe viewers' "Job Recipes" category.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
final class InnateRecipeEvents {

    private InnateRecipeEvents() {
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        syncAll(event.getServer());
    }

    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        event.sendRecipes(ModRecipes.JOB_CRAFTING.get());
        if (event.getPlayer() == null) {
            // A reload (not a player joining): the innate recipes may have changed.
            syncAll(event.getPlayerList().getServer());
        }
    }

    private static void syncAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            Patterns.syncInnate(level);
        }
    }
}
