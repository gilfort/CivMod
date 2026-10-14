package com.gilfort.civmod.gametest;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.SettlerWork;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Freezes the GameTest world at 08:00 (work hours). The GameTest world is reused between runs, so its clock would
 * otherwise make schedule-dependent tests (settlers only work 06:00–17:30) flaky.
 * <p>
 * The time lies after the first {@link SettlerWork#SCAN_SPREAD_TICKS} ticks of the workday: inside that window every
 * daily scan waits a random delay of up to a minute before it starts, which made tests waiting for a scan result
 * time out at random.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GameTestClock {

    /** The frozen day time (08:00). */
    private static final long DAY_TIME = 2000L;

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
        server.getCommands().performPrefixedCommand(source, "time set " + DAY_TIME);
    }
}
