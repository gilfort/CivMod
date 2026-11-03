package com.gilfort.civmod.settlement;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Shows the shrine's glow on the Center Block (vault {@code Jobs/Priest.md}): once per second per dimension each
 * settlement's loaded Center Block is compared with {@link Shrine#glowing} and its glow state synced when it changes.
 * When the glow starts, a solid block above the Center Block is checked once; without one a beam rises like a
 * beacon's, with one only sparks rise. While the settlement's blessing acts, green sparks surround the block as well.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ShrineGlow {

    private static final int CHECK_INTERVAL = 20;

    private ShrineGlow() {
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % CHECK_INTERVAL == 0) {
            for (Settlement settlement : List.copyOf(SettlementData.get(level).settlements())) {
                settlement.blocks().stream().filter(SettlementBlock::isCenter).map(SettlementBlock::pos).filter(level::isLoaded)
                        .forEach(center -> update(level, settlement, center));
            }
        }
    }

    private static void update(ServerLevel level, Settlement settlement, BlockPos center) {
        if (!(level.getBlockEntity(center) instanceof JobBlockEntity entity)) {
            return;
        }
        long clock = level.getOverworldClockTime();
        if (settlement.bonuses().remaining(Bonuses.Kind.WORK_SPEED, clock) > 0) {
            // The blessing of a prayer: golden sparks round the block while it acts.
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, 3, 0.4, 0.3, 0.4, 0.0);
        }
        boolean glowing = Shrine.glowing(settlement.id(), DayClock.day(clock), DayClock.dayTime(clock), clock, settlement.shrine());
        if (!glowing) {
            entity.setGlow(JobBlockEntity.GLOW_NONE);
            return;
        }
        if (entity.getGlow() == JobBlockEntity.GLOW_NONE) {
            entity.setGlow(solidAbove(level, center) ? JobBlockEntity.GLOW_SPARKS : JobBlockEntity.GLOW_BEAM);
        }
        level.sendParticles(ParticleTypes.END_ROD, center.getX() + 0.5, center.getY() + 1.1, center.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.05);
    }

    /**
     * @param level  the level
     * @param center the Center Block
     * @return whether a solid block stands anywhere above it
     */
    static boolean solidAbove(ServerLevel level, BlockPos center) {
        for (int y = center.getY() + 1; y < level.getMaxY(); y++) {
            BlockState state = level.getBlockState(center.atY(y));
            if (state.canOcclude()) {
                return true;
            }
        }
        return false;
    }
}
