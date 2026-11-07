package com.gilfort.civmod.gametest;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Keeps chance out of the GameTests: every settler that joins a level in a GameTest run gets a scripted meeting roll that is
 * neither a conversation nor a greeting. Without it a random conversation would stop a settler in the middle of a test or add
 * 0.5 to the Entertainment it counts. A test about the social life scripts its own rolls after the settler has joined
 * ({@code SettlerWork.scriptSocialRolls}), which replaces this default. The class exists only in the GameTest source set.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class QuietSocialLife {

    /** A roll of the meeting check that is neither a conversation nor a greeting. */
    public static final double QUIET_ROLL = 0.99;

    private QuietSocialLife() {
    }

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Settler settler) {
            settler.getWork().scriptSocialRolls(() -> QUIET_ROLL);
        }
    }
}
