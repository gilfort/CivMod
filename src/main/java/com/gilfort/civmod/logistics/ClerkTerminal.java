package com.gilfort.civmod.logistics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Who has the Storage tab of a Clerk open (vault {@code Jobs/Clerk.md}, terminal). While anybody does, that Clerk's
 * requests and surplus are not checked, so a misclick cannot trigger a mass order; the check runs once when the last
 * viewer closes the tab or disconnects.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkTerminal {

    /** Viewers per level and Clerk JobBlock position. */
    private static final Map<ServerLevel, Map<BlockPos, Set<UUID>>> VIEWERS = new WeakHashMap<>();

    private ClerkTerminal() {
    }

    /**
     * @param level the level
     * @param clerk the Clerk JobBlock position
     * @return whether a player has the Clerk's Storage tab open
     */
    public static boolean viewed(ServerLevel level, BlockPos clerk) {
        Map<BlockPos, Set<UUID>> clerks = VIEWERS.get(level);
        return clerks != null && clerks.containsKey(clerk);
    }

    /**
     * @param level  the level
     * @param clerk  the Clerk JobBlock position
     * @param player the player who opened the Storage tab
     */
    public static void open(ServerLevel level, BlockPos clerk, UUID player) {
        VIEWERS.computeIfAbsent(level, key -> new HashMap<>()).computeIfAbsent(clerk.immutable(), key -> new HashSet<>()).add(player);
    }

    /**
     * Removes a viewer; the last one leaving checks the Clerk's rules and surplus once.
     *
     * @param level  the level
     * @param clerk  the Clerk JobBlock position
     * @param player the player who closed the Storage tab
     */
    public static void close(ServerLevel level, BlockPos clerk, UUID player) {
        Map<BlockPos, Set<UUID>> clerks = VIEWERS.get(level);
        Set<UUID> viewers = clerks == null ? null : clerks.get(clerk);
        if (viewers == null || !viewers.remove(player) || !viewers.isEmpty()) {
            return;
        }
        clerks.remove(clerk);
        StockRules.check(level, clerk, DayClock.dayTime(level));
    }

    /** A disconnect closes every Storage tab of the player (the menu may not be told). */
    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID player = event.getEntity().getUUID();
        for (Map.Entry<ServerLevel, Map<BlockPos, Set<UUID>>> level : List.copyOf(VIEWERS.entrySet())) {
            for (BlockPos clerk : List.copyOf(level.getValue().keySet())) {
                close(level.getKey(), clerk, player);
            }
        }
    }
}
