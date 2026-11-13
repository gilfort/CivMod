package com.gilfort.civmod.job;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.gilfort.civmod.registry.ModEntityTypes;

/** Static standing geometry: where a settler can stand next to a target. */
public final class StandingSpots {

    private StandingSpots() {
    }

    /**
     * Finds a free spot to stand on next to a target (work place, container, bed), so a teleport never puts the
     * settler inside a block (e.g. into the trunk above a log) or on top of a bed. Spots are within reach of the
     * target, so the settler can work from there.
     *
     * @param level  the level
     * @param target the target block
     * @return the feet position
     */
    public static Vec3 standingSpot(ServerLevel level, BlockPos target) {
        return standingSpot(level, target, SettlerWork.REACH);
    }

    /**
     * Finds a free spot to stand on within the given reach of a target.
     *
     * @param level  the level
     * @param target the target block
     * @param reach  the horizontal reach
     * @return the feet position
     */
    public static Vec3 standingSpot(ServerLevel level, BlockPos target, double reach) {
        BlockState state = level.getBlockState(target);
        if (state.getBlock() instanceof AbstractBedBlock && state.hasProperty(BedBlock.FACING)) {
            Optional<Vec3> standUp = AbstractBedBlock.findStandUpPosition(ModEntityTypes.SETTLER.get(), level, target,
                    state.getValue(BedBlock.FACING), 0.0F);
            if (standUp.isPresent()) {
                return standUp.get();
            }
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int search = (int) Math.floor(reach);
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-search, -2, -search), target.offset(search, 3, search))) {
            int dx = pos.getX() - target.getX();
            int dz = pos.getZ() - target.getZ();
            if (dx * dx + dz * dz <= reach * reach && canStand(level, pos)) {
                // Prefer spots at the target's height.
                double distance = pos.distSqr(target) + 2.0 * Math.abs(pos.getY() - target.getY());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
        }
        if (best != null) {
            return Vec3.atBottomCenterOf(best);
        }
        BlockPos up = target.above();
        for (int i = 0; i < 32 && !canStand(level, up); i++) {
            up = up.above();
        }
        return Vec3.atBottomCenterOf(up);
    }

    static boolean canStand(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getFluidState(pos).isEmpty()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }
}
