package com.gilfort.civmod.gametest;

import com.gilfort.civmod.CivMod;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Freezes the GameTest world at 07:00 (work hours). The GameTest world is reused between runs, so its clock would
 * otherwise make schedule-dependent tests (settlers only work 06:00–17:30) flaky.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GameTestClock {

    private GameTestClock() {
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // The gametest source set is part of every dev run: only touch the clock on the GameTest server.
        if (!(server instanceof net.minecraft.gametest.framework.GameTestServer)) {
            return;
        }
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "gamerule advance_time false");
        server.getCommands().performPrefixedCommand(source, "time set 1000");
    }
}
