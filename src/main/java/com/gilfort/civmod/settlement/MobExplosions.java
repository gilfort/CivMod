package com.gilfort.civmod.settlement;

import java.util.Collection;
import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Keeps explosions caused by mobs from breaking blocks inside a settlement border. The explosion loses every block position
 * inside a border from its list, so no block breaks, no drop appears and no fire starts there; entities take damage as before.
 * An explosion counts as caused by a mob when the entity that exploded or the one that is to blame for it is a {@link Mob}:
 * a creeper, a ghast's fireball, the wither and its skulls, TNT a creeper's blast set off. TNT lit by a player, dispenser TNT,
 * beds, end crystals and wind charges stay as they are.
 * <p>
 * Cost: one explosion visits the connected JobBlocks of its dimension once (the box test below), and only an explosion that
 * reaches a border checks its block positions, each against the few borders in reach. There is no per-tick work.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MobExplosions {

    /**
     * How far from its centre an explosion can reach, in radii. Rays start with up to 1.3 times the radius, vanilla's own
     * damage box uses 2, which also covers the block grid.
     */
    private static final double REACH_IN_RADII = 2.0;
    /** Extra blocks around the reach, for the rounding of positions to the block grid. */
    private static final double REACH_MARGIN_BLOCKS = 1.0;

    /** A connected JobBlock and the join radius around it, the circle that makes up (a part of) a settlement border. */
    private record Border(BlockPos jobBlock, int radius) {

        /** @return whether the circle can touch the square of the given half-width around the centre */
        boolean touches(Vec3 center, double halfWidth) {
            double dx = Math.max(0.0, Math.abs(jobBlock.getX() + 0.5 - center.x) - halfWidth);
            double dz = Math.max(0.0, Math.abs(jobBlock.getZ() + 0.5 - center.z) - halfWidth);
            return dx * dx + dz * dz <= (double) radius * radius;
        }

        /** @return whether the position lies inside, by the rule of {@link JobAssignments#insideBorder} (height ignored) */
        boolean contains(BlockPos pos) {
            return SettlementRules.horizontalDistance(pos, jobBlock) <= radius;
        }
    }

    private MobExplosions() {
    }

    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        if (!CivModConfig.MOB_EXPLOSION_PROTECTION.get() || !causedByMob(explosion)) {
            return;
        }
        removeInsideBorders(event.getAffectedBlocks(), explosion.center(), explosion.radius(), SettlementData.get(explosion.level()).settlements());
    }

    /**
     * @param explosion an explosion
     * @return whether a mob exploded or is to blame for it (a player is never a mob)
     */
    static boolean causedByMob(Explosion explosion) {
        return explosion.getDirectSourceEntity() instanceof Mob || explosion.getIndirectSourceEntity() instanceof Mob;
    }

    /**
     * Removes the positions that lie inside a border from the block list of an explosion.
     *
     * @param blocks      the affected blocks of the explosion; changed in place
     * @param center      the centre of the explosion
     * @param radius      the radius of the explosion
     * @param settlements the settlements of the dimension
     */
    static void removeInsideBorders(List<BlockPos> blocks, Vec3 center, float radius, Collection<Settlement> settlements) {
        double reach = radius * REACH_IN_RADII + REACH_MARGIN_BLOCKS;
        List<Border> inReach = settlements.stream().flatMap(settlement -> settlement.blocks().stream()).filter(SettlementBlock::connected)
                .map(block -> new Border(block.pos(), SettlementRules.joinRadius(block))).filter(border -> border.touches(center, reach)).toList();
        if (!inReach.isEmpty()) {
            blocks.removeIf(pos -> inReach.stream().anyMatch(border -> border.contains(pos)));
        }
    }
}
