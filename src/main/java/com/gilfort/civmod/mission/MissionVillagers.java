package com.gilfort.civmod.mission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

import com.gilfort.civmod.item.ScrollOfAssignmentItem;
import com.gilfort.civmod.registry.ModAttachments;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.Vec3;

/**
 * The vanilla Villagers a Villager mission brings (vault {@code Jobs/Bard.md}): they arrive as unemployed adults of the
 * mission's biome type (or nitwits), remember the mission's level ({@link ModAttachments#MISSION_LEVEL}, read by the
 * conversion into a Settler) and walk with the Bard to the Mission Board; from then on they are plain vanilla Villagers.
 */
public final class MissionVillagers {

    /** Walking speed of an arriving Villager (vanilla Villagers stroll at 0.5, flee at 0.6). */
    private static final float SPEED = 0.6F;
    /** How close a following Villager stays to the Bard. */
    private static final int FOLLOW_DISTANCE = 2;
    /** How close a released Villager walks to the Mission Board. */
    private static final int BOARD_DISTANCE = 2;
    /** How far around a spot an arriving Villager may stand. */
    private static final int SPOT_RANGE = 2;

    private MissionVillagers() {
    }

    /**
     * Spawns the Villagers of a successful Villager mission; each one is a nitwit with
     * {@link MissionRules#nitwitChance}.
     *
     * @param level       the level
     * @param at      where they appear (the Bard's spot, or the Mission Board during catch-up)
     * @param mission the Villager mission
     * @param skill   the Bard's effective Mission Job skill
     * @param random  the random source
     * @return the Villagers that arrived (none for a loot mission)
     */
    public static List<Villager> arrive(ServerLevel level, BlockPos at, Mission mission, int skill, RandomSource random) {
        if (mission.villagers().isEmpty()) {
            return List.of();
        }
        Mission.Villagers villagers = mission.villagers().get();
        int recommended = mission.recommendedLevel();
        double nitwit = MissionRules.nitwitChance(villagers, recommended, skill);
        Vec3 spot = Vec3.atBottomCenterOf(spotNear(level, at));
        List<Villager> arrived = new ArrayList<>();
        for (int i = 0; i < villagers.count(); i++) {
            Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
            if (villager == null) {
                continue;
            }
            villager.setVillagerData(villager.getVillagerData().withType(villagers.type())
                    .withProfession(level.registryAccess(), random.nextDouble() < nitwit ? VillagerProfession.NITWIT : VillagerProfession.NONE));
            villager.snapTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);
            villager.setData(ModAttachments.MISSION_LEVEL, recommended);
            level.addFreshEntity(villager);
            arrived.add(villager);
        }
        return arrived;
    }

    /**
     * @param villager a Villager
     * @return the recommended level of the Villager mission it came with, empty if it did not come from one
     */
    public static OptionalInt missionLevel(Villager villager) {
        int level = villager.getData(ModAttachments.MISSION_LEVEL);
        return level > 0 ? OptionalInt.of(level) : OptionalInt.empty();
    }

    /**
     * Lets the Villager walk after the Bard (its brain's walk target; renewed by the Bard while it leads).
     *
     * @param villager the Villager
     * @param leader   the Bard
     */
    public static void follow(Villager villager, Entity leader) {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(leader, SPEED, FOLLOW_DISTANCE));
    }

    /**
     * Sends the Villager the last steps to the Mission Board; after that its own vanilla behaviour takes over.
     *
     * @param villager the Villager
     * @param board    the Mission Board
     */
    public static void release(Villager villager, BlockPos board) {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(board, SPEED, BOARD_DISTANCE));
    }

    /**
     * The free spot nearest to {@code center} (standing room above sturdy ground, not on top of {@code center} itself, so
     * nobody stands on the Mission Board), else above it.
     */
    private static BlockPos spotNear(ServerLevel level, BlockPos center) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos ground : BlockPos.betweenClosed(center.offset(-SPOT_RANGE, -SPOT_RANGE, -SPOT_RANGE),
                center.offset(SPOT_RANGE, SPOT_RANGE, SPOT_RANGE))) {
            if (!ground.equals(center) && ScrollOfAssignmentItem.isStandable(level, ground)) {
                spots.add(ground.above().immutable());
            }
        }
        return spots.stream().min(Comparator.comparingDouble(spot -> spot.distSqr(center))).orElse(center.above());
    }
}
