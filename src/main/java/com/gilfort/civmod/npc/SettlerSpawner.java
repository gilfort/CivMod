package com.gilfort.civmod.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Brings the starting settlers to new settlements (vault {@code Systems/NPC Population.md}).
 * <p>
 * Checked once per second per dimension. When a settlement's timer has run out and its anchor JobBlock is loaded,
 * the missing settlers up to the configured starting count spawn near the anchor; then the settlers name the
 * settlement and nearby players are told.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlerSpawner {

    private static final int CHECK_INTERVAL = 20;
    private static final int SPAWN_RADIUS = 5;
    private static final int SPAWN_ATTEMPTS = 16;
    private static final double ANNOUNCE_DISTANCE = 64.0;

    private SettlerSpawner() {
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % CHECK_INTERVAL == 0) {
            tick(level);
        }
    }

    private static void tick(ServerLevel level) {
        SettlementData data = SettlementData.get(level);
        for (Settlement settlement : List.copyOf(data.settlements())) {
            long due = settlement.settlersDue();
            if (due != Settlement.SETTLERS_ARRIVED && level.getGameTime() >= due) {
                settlement.anchor().map(SettlementBlock::pos).filter(level::isLoaded).ifPresent(anchor -> arrive(level, data, settlement, anchor));
            }
        }
    }

    /**
     * @param existing number of settlers the settlement already has
     * @param target   the configured starting count
     * @return how many settlers to spawn
     */
    public static int missingSettlers(int existing, int target) {
        return Math.max(0, target - existing);
    }

    /**
     * Spawns the missing starting settlers, lets them name the settlement and announces it.
     *
     * @param level      the dimension
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param anchor     the position the settlers gather around
     * @return the spawned settlers
     */
    public static List<Settler> arrive(ServerLevel level, SettlementData data, Settlement settlement, BlockPos anchor) {
        Set<String> usedNames = new HashSet<>();
        Map<Identifier, Integer> races = new HashMap<>();
        for (UUID member : settlement.members()) {
            if (level.getEntity(member) instanceof Settler settler) {
                usedNames.add(settler.getName().getString());
                races.merge(settler.getRace(), 1, Integer::sum);
            }
        }
        List<Settler> spawned = new ArrayList<>();
        int missing = missingSettlers(settlement.members().size(), CivModConfig.STARTING_SETTLERS.get());
        for (int i = 0; i < missing; i++) {
            Settler settler = spawn(level, data, settlement, anchor, Races.HUMAN, usedNames);
            if (settler != null) {
                spawned.add(settler);
                usedNames.add(settler.getName().getString());
                races.merge(settler.getRace(), 1, Integer::sum);
            }
        }
        if (settlement.name().isEmpty()) {
            Identifier race = races.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Races.HUMAN);
            String name = RaceNames.pick(RaceNames.get(race).settlement(), List.of(), level.getRandom())
                    .orElse("Settlement #" + settlement.id());
            data.setName(settlement, name);
            announce(level, settlement, anchor);
        }
        data.settlersArrived(settlement);
        return spawned;
    }

    /**
     * Recruits one Settler at a Center Block, as the shrine's prayer does (vault {@code Jobs/Priest.md}): only with
     * room under {@code maxSettlersPerSettlement} (running conversions count) and a free bed, which the new Settler
     * takes. The Settler has level 1 in every Job and 2 bread, appears with a cloud puff and sound, and nearby players
     * are told.
     *
     * @param level      the dimension
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param center     the Center Block
     * @return the new Settler, empty if the settlement is full or has no free bed
     */
    public static Optional<Settler> recruit(ServerLevel level, SettlementData data, Settlement settlement, BlockPos center) {
        if (isFull(settlement)) {
            return Optional.empty();
        }
        Optional<BlockPos> bed = SettlerBeds.freeBeds(level, settlement, center).stream().findFirst();
        if (bed.isEmpty()) {
            return Optional.empty();
        }
        Identifier race = level.getBlockState(center).getBlock() instanceof JobBlock block
                ? block.centerRace().orElse(Races.HUMAN) : Races.HUMAN;
        Settler settler = spawn(level, data, settlement, center, race, usedNames(level, settlement));
        if (settler == null) {
            return Optional.empty();
        }
        for (Job job : ModJobs.REGISTRY) {
            settler.getSkills().set(job, new SettlerSkills.Skill(1, 0));
        }
        SettlerBeds.claim(level, data, settlement, settler.getUUID(), bed.get());
        announce(level, Vec3.atCenterOf(center), Component.translatable("civmod.conversion.joined", settler.getName(), settlement.displayName()));
        return Optional.of(settler);
    }

    /**
     * @param settlement a settlement
     * @return whether Settlers and running conversions reached {@code maxSettlersPerSettlement} (0 = unlimited)
     */
    static boolean isFull(Settlement settlement) {
        int max = CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.get();
        return max > 0 && settlement.members().size() + settlement.bedReservations().size() >= max;
    }

    private static Settler spawn(ServerLevel level, SettlementData data, Settlement settlement, BlockPos anchor, Identifier race,
            Set<String> usedNames) {
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        if (settler == null) {
            return null;
        }
        RandomSource random = level.getRandom();
        Vec3 pos = findSpawnPos(level, anchor, random);
        settler.snapTo(pos.x, pos.y, pos.z, random.nextFloat() * 360.0F, 0.0F);
        settler.setRace(race);
        settler.setSkin(random.nextInt(SettlerSkins.COUNT));
        String name = RaceNames.pick(RaceNames.get(race).npc(), usedNames, random).orElse("Settler");
        settler.setCustomName(Component.literal(name));
        settler.joinSettlement(data, settlement);
        // Provisions for the first days, before a Clerk has food.
        settler.getInventory().setItem(Settler.POCKETS_START, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 2));
        level.addFreshEntity(settler);
        puff(level, pos);
        return settler;
    }

    /**
     * The cloud puff and sound of a Settler appearing.
     *
     * @param level the level
     * @param pos   where the Settler appears
     */
    static void puff(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 1.0, pos.z, 12, 0.3, 0.5, 0.3, 0.02);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4F, 0.8F);
    }

    /**
     * @param level      the level
     * @param settlement a settlement
     * @return the names of its loaded Settlers (a new Settler avoids them)
     */
    static Set<String> usedNames(ServerLevel level, Settlement settlement) {
        Set<String> names = new HashSet<>();
        for (UUID member : settlement.members()) {
            if (level.getEntity(member) instanceof Settler settler) {
                names.add(settler.getName().getString());
            }
        }
        return names;
    }

    /**
     * Finds a random safe spot (solid ground, two free blocks, no fluid) within {@value #SPAWN_RADIUS} blocks.
     *
     * @param level  the dimension
     * @param anchor the centre
     * @param random the random source
     * @return the spawn position (on top of the anchor if nothing safe was found)
     */
    static Vec3 findSpawnPos(ServerLevel level, BlockPos anchor, RandomSource random) {
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            int dx = random.nextIntBetweenInclusive(-SPAWN_RADIUS, SPAWN_RADIUS);
            int dz = random.nextIntBetweenInclusive(-SPAWN_RADIUS, SPAWN_RADIUS);
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos feet = anchor.offset(dx, dy, dz);
                if (isSafe(level, feet)) {
                    return Vec3.atBottomCenterOf(feet);
                }
            }
        }
        return Vec3.atBottomCenterOf(anchor.above());
    }

    private static boolean isSafe(ServerLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        return level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(feet.above()).isEmpty();
    }

    private static void announce(ServerLevel level, Settlement settlement, BlockPos anchor) {
        announce(level, Vec3.atCenterOf(anchor), Component.translatable("civmod.settlement.named", settlement.displayName()));
    }

    /**
     * Tells all players within {@value #ANNOUNCE_DISTANCE} blocks.
     *
     * @param level   the level
     * @param pos     where it happened
     * @param message the chat message
     */
    static void announce(ServerLevel level, Vec3 pos, Component message) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= ANNOUNCE_DISTANCE * ANNOUNCE_DISTANCE) {
                player.sendSystemMessage(message);
            }
        }
    }
}
