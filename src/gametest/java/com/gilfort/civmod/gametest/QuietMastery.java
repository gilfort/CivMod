package com.gilfort.civmod.gametest;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Keeps Mastery out of the GameTests: every settler that joins a level on the GameTest server rolls a miss at every roll of a
 * craft. Without it a specialist at level 100 would save material or make extra output in 10 % of the crafts of any test and
 * turn the existing Producing tests flaky. A test about Mastery scripts its own rolls after the settler has joined
 * ({@code SettlerWork.scriptMasteryRandom}), which replaces this default. The class exists only in the GameTest source set.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class QuietMastery {

    private QuietMastery() {
    }

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        // The gametest source set is part of every dev run: only script the rolls on the GameTest server, so settlers in
        // runClient and runServer still roll.
        if (event.getLevel() instanceof ServerLevel level && level.getServer() instanceof GameTestServer
                && event.getEntity() instanceof Settler settler) {
            settler.getWork().scriptMasteryRandom(MasteryRolls.never());
        }
    }
}
