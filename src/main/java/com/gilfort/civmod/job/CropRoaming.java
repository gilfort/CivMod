package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * The Veggy Farmer's walk through the field (vault {@code Jobs/Veggy Farmer.md}): he starts at the Scarecrow and every
 * 5 seconds picks a fully grown crop from a small box around where he is now, walks next to it and hits it with the
 * hoe. The crops are never harvested. The crops of the box are cached for 30 seconds, so one search serves several moves,
 * and a leash keeps every pick inside the Scarecrow's own scan, so the plants he hits are the ones that decide his rolls.
 * The anchor and the roll place stay the Scarecrow.
 */
final class CropRoaming implements Roaming {

    /** Ticks between two moves: 5 seconds. */
    private static final int MOVE_WAIT_TICKS = 100;
    /** Ticks the crop list of a search is used: 30 seconds. */
    private static final int CACHE_TICKS = 600;
    /** How far along x and z around him the crops are searched. */
    private static final int BOX_RADIUS = 5;
    /** How far up and down around him the crops are searched. */
    private static final int BOX_HALF_HEIGHT = 3;

    private final BlockPos anchor;
    private final int leashRadius;
    private final int leashHalfHeight;
    private final RandomSource random;
    private BlockPos stand;
    private long moveAt;
    private long cacheUntil;
    private final List<BlockPos> crops = new ArrayList<>();

    /**
     * @param anchor          the Scarecrow, where he starts
     * @param leashRadius     how far along x and z from the Scarecrow he may pick a crop (the radius of its scan)
     * @param leashHalfHeight how far up and down from the Scarecrow he may pick a crop (the height of its scan)
     * @param gameTime        the game time now
     * @param random          the source of the picks
     */
    CropRoaming(BlockPos anchor, int leashRadius, int leashHalfHeight, long gameTime, RandomSource random) {
        this.anchor = anchor;
        this.leashRadius = leashRadius;
        this.leashHalfHeight = leashHalfHeight;
        this.random = random;
        this.stand = anchor;
        this.moveAt = gameTime + MOVE_WAIT_TICKS;
    }

    @Override
    public BlockPos anchor() {
        return anchor;
    }

    @Override
    public BlockPos stand() {
        return stand;
    }

    @Override
    public BlockPos rollPlace() {
        return anchor;
    }

    @Override
    public boolean isDue(ServerLevel level, long gameTime) {
        return gameTime >= moveAt;
    }

    @Override
    public Optional<BlockPos> moveOn(RoamingContext context) {
        if (!isDue(context.level(), context.gameTime())) {
            return Optional.empty();
        }
        moveAt = context.gameTime() + MOVE_WAIT_TICKS;
        if (context.gameTime() >= cacheUntil) {
            refresh(context);
        }
        Optional<BlockPos> next = pickCrop(context.level());
        next.ifPresent(crop -> stand = crop);
        return next;
    }

    /** The one box search: the fully grown crops around him that lie inside the leash. */
    private void refresh(RoamingContext context) {
        crops.clear();
        BlockSearch.all(context.level(), context.position(), BOX_RADIUS, BOX_HALF_HEIGHT, VeggyFarmerProfile::counts).stream()
                .filter(this::insideLeash).forEach(crops::add);
        cacheUntil = context.gameTime() + CACHE_TICKS;
    }

    private boolean insideLeash(BlockPos pos) {
        return Math.abs(pos.getX() - anchor.getX()) <= leashRadius && Math.abs(pos.getZ() - anchor.getZ()) <= leashRadius
                && Math.abs(pos.getY() - anchor.getY()) <= leashHalfHeight;
    }

    /** A random cached crop other than the one he stands at that still is fully grown; crops that are not any more leave the list. */
    private Optional<BlockPos> pickCrop(ServerLevel level) {
        List<BlockPos> candidates = new ArrayList<>(crops);
        candidates.remove(stand);
        while (!candidates.isEmpty()) {
            BlockPos crop = candidates.remove(random.nextInt(candidates.size()));
            if (VeggyFarmerProfile.counts(level.getBlockState(crop))) {
                return Optional.of(crop);
            }
            crops.remove(crop);
        }
        return Optional.empty();
    }
}
