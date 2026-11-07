package com.gilfort.civmod.entertainment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The seats around a Pantry (vault {@code Systems/Entertainment.md}, Seats): the vanilla Cushion and every entity of the
 * entity type tag {@code civmod:seat_entities}, and every block of the block tag {@code civmod:seat_blocks} (empty by
 * default, for chairs of furniture mods). Only finds seats and tells whether a player sits on one; which settler sits where
 * is the settler's own booking ({@code SettlerTavern}).
 */
public final class TavernSeats {

    /** Entities that are seats: the vanilla Cushion by default. */
    static final TagKey<EntityType<?>> SEAT_ENTITIES = TagKey.create(Registries.ENTITY_TYPE, CivMod.id("seat_entities"));
    /** Blocks that are seats: none by default. */
    static final TagKey<Block> SEAT_BLOCKS = TagKey.create(Registries.BLOCK, CivMod.id("seat_blocks"));

    private static final String ENTITY_KEY = "e:";
    private static final String BLOCK_KEY = "b:";

    private TavernSeats() {
    }

    /**
     * One seat.
     *
     * @param key      tells the seat apart from the others while it exists: the UUID of a seat entity, the position of a
     *                 seat block
     * @param position where a settler sits: the bottom of its hitbox, on the seat's surface
     * @param facing   the direction the guest looks at (yaw in degrees): the yaw of the seat entity, the facing of the seat
     *                 block
     * @param walkTo   the block a settler walks to before it sits down
     * @param entity   the seat entity, empty for a seat block
     */
    public record Seat(String key, Vec3 position, float facing, BlockPos walkTo, Optional<Entity> entity) {
    }

    /**
     * @param level  the level
     * @param pantry the Pantry
     * @param radius blocks around the Pantry
     * @return the seats within the radius, the nearest to the Pantry first
     */
    public static List<Seat> around(ServerLevel level, BlockPos pantry, int radius) {
        Vec3 center = Vec3.atCenterOf(pantry);
        List<Seat> seats = new ArrayList<>();
        AABB box = AABB.ofSize(center, 2 * radius, 2 * radius, 2 * radius);
        for (Entity entity : level.getEntities((Entity) null, box, found -> found.typeHolder().is(SEAT_ENTITIES))) {
            if (within(entity.blockPosition(), center, radius)) {
                seats.add(entitySeat(entity));
            }
        }
        if (hasSeatBlocks()) {
            for (BlockPos pos : BlockPos.betweenClosed(pantry.offset(-radius, -radius, -radius), pantry.offset(radius, radius, radius))) {
                if (within(pos, center, radius) && level.isLoaded(pos) && level.getBlockState(pos).is(SEAT_BLOCKS)) {
                    seats.add(blockSeat(level, pos.immutable()));
                }
            }
        }
        seats.sort(Comparator.comparingDouble((Seat seat) -> seat.position().distanceToSqr(center)).thenComparing(Seat::key));
        return seats;
    }

    /**
     * Looks one seat up by its key, without scanning the area.
     *
     * @param level  the level
     * @param pantry the Pantry
     * @param radius blocks around the Pantry
     * @param key    the key of a seat
     * @return the seat as it is now, empty once it is gone (its entity removed, its block broken or too far)
     */
    public static Optional<Seat> find(ServerLevel level, BlockPos pantry, int radius, String key) {
        Vec3 center = Vec3.atCenterOf(pantry);
        if (key.startsWith(ENTITY_KEY)) {
            return Optional.ofNullable(level.getEntity(UUID.fromString(key.substring(ENTITY_KEY.length()))))
                    .filter(entity -> entity.typeHolder().is(SEAT_ENTITIES) && within(entity.blockPosition(), center, radius))
                    .map(TavernSeats::entitySeat);
        }
        BlockPos pos = BlockPos.of(Long.parseLong(key.substring(BLOCK_KEY.length())));
        return Optional.of(pos).filter(found -> within(found, center, radius) && level.isLoaded(found) && level.getBlockState(found).is(SEAT_BLOCKS))
                .map(found -> blockSeat(level, found));
    }

    /**
     * @param level the level
     * @param seat  a seat
     * @return whether somebody rides the seat: a player sits on the Cushion, or on a furniture mod's seat entity at a seat
     *         block. Settlers never ride a seat.
     */
    public static boolean occupied(ServerLevel level, Seat seat) {
        if (seat.entity().isPresent()) {
            return seat.entity().get().isVehicle();
        }
        AABB block = new AABB(seat.walkTo()).inflate(0.5);
        return !level.getEntities((Entity) null, block, Entity::isPassenger).isEmpty();
    }

    /** Distance between block centres, so a Cushion on the same level as the Pantry is exactly as far as it looks. */
    private static boolean within(BlockPos pos, Vec3 center, int radius) {
        return Vec3.atCenterOf(pos).distanceToSqr(center) <= (double) radius * radius;
    }

    /** The tag is read from the registry instead of scanning the area for nothing. */
    private static boolean hasSeatBlocks() {
        return BuiltInRegistries.BLOCK.get(SEAT_BLOCKS).map(set -> set.size() > 0).orElse(false);
    }

    private static Seat entitySeat(Entity entity) {
        return new Seat(ENTITY_KEY + entity.getUUID(), new Vec3(entity.getX(), entity.getBoundingBox().maxY, entity.getZ()),
                entity.getYRot(), entity.blockPosition(), Optional.of(entity));
    }

    private static Seat blockSeat(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        double height = shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
        float facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot() : 0.0F;
        return new Seat(BLOCK_KEY + pos.asLong(), new Vec3(pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5), facing, pos.above(), Optional.empty());
    }
}

