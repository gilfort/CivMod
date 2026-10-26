package com.gilfort.civmod.job;

import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.OccupiedPlaces;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.TriState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The player side of occupied work places (vault {@code Systems/Jobs.md}): while a settler actually works at its
 * occupied work place (a Producing Job crafting at its station), a player cannot open the block's GUI; the action bar
 * says "In use by <name>". Only the use of the block is refused: items still work and breaking is never refused. Trees,
 * stone and water have no player lock ({@link CategoryLoop#usesTarget}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class WorkPlaceLock {

    private WorkPlaceLock() {
    }

    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        refusal(level, event.getPos()).ifPresent(message -> {
            event.setUseBlock(TriState.FALSE);
            event.getEntity().sendOverlayMessage(message);
        });
    }

    /**
     * @param level the level
     * @param pos   the block a player wants to use
     * @return "In use by <name>" while the settler occupying the block works at it, empty if the player may use it
     */
    public static Optional<Component> refusal(ServerLevel level, BlockPos pos) {
        return OccupiedPlaces.occupant(SettlementData.get(level), pos, DayClock.day(level))
                .filter(occupation -> level.getEntity(occupation.settler()) instanceof Settler settler && settler.getWork().worksAt(pos))
                .map(occupation -> Component.translatable("civmod.work_place.in_use", occupation.name()));
    }
}
