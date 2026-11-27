package com.gilfort.civmod.gametest;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.StoryShare;

import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Keeps the daily story roll out of the GameTests: on the GameTest server no settler has a story aspect to share unless a test
 * forces its roll, so no story bubble shows up in the bubbles another test counts. A test about the story share forces or releases the
 * roll itself and gives the quiet roll back with {@link #restore}. The class exists only in the GameTest source set.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class QuietStories {

    private QuietStories() {
    }

    @SubscribeEvent
    static void onServerStarting(ServerStartingEvent event) {
        // The gametest source set is part of every dev run: only quiet the roll on the GameTest server.
        if (event.getServer() instanceof GameTestServer) {
            restore();
        }
    }

    /** Gives back the quiet roll: nobody has a story aspect to share. */
    static void restore() {
        StoryShare.script(false);
    }
}
