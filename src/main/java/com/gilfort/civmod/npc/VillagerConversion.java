package com.gilfort.civmod.npc;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.mission.MissionVillagers;
import com.gilfort.civmod.registry.ModAttachments;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The Villager conversion (vault {@code Systems/NPC Population.md}): an item of {@link #PRIMER} (or a splash water
 * bottle) makes an adult Villager Receptive for {@value #RECEPTIVE_TICKS} ticks; an item of {@link #FOOD} then starts
 * the conversion, which reserves a free bed of the settlement the Villager stands in. After {@value #DURATION_TICKS}
 * ticks of world time the Villager becomes a Settler of that settlement and claims the bed.
 * <p>
 * The timer is only checked when the converting Villager ticks, so an unloaded Villager completes on its next tick
 * (catch-up).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VillagerConversion {

    /** Items that make a Villager Receptive (default: water bottle). */
    public static final TagKey<Item> PRIMER = TagKey.create(Registries.ITEM, CivMod.id("conversion_primer"));
    /** Items that start the conversion of a Receptive Villager (default: bread). */
    public static final TagKey<Item> FOOD = TagKey.create(Registries.ITEM, CivMod.id("conversion_food"));
    /** How long a Villager stays Receptive (60 s). */
    public static final int RECEPTIVE_TICKS = 1200;
    /** How long the conversion takes (5 minutes). */
    public static final int DURATION_TICKS = 6000;
    /** Number of dice averaged for each starting skill. */
    private static final int SKILL_DICE = 4;
    /** Ticks between two particle bursts of a Receptive or converting Villager. */
    private static final int PARTICLE_INTERVAL = 10;
    /** Radius around a splash potion's impact in which Villagers get wet (vanilla's water splash). */
    private static final double SPLASH_RADIUS = 4.0;

    private VillagerConversion() {
    }

    /**
     * A running conversion, kept on the Villager.
     *
     * @param dimension  the dimension of the settlement
     * @param settlement the id of the settlement the Villager was fed in
     * @param due        the game time at which it completes
     */
    public record Conversion(ResourceKey<Level> dimension, int settlement, long due) {

        /** Placeholder default of the attachment; a Villager without conversion has no attachment. */
        public static final Conversion NONE = new Conversion(Level.OVERWORLD, -1, 0L);

        /** Save codec. */
        public static final Codec<Conversion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Conversion::dimension),
                Codec.INT.fieldOf("settlement").forGetter(Conversion::settlement),
                Codec.LONG.fieldOf("due").forGetter(Conversion::due)
        ).apply(instance, Conversion::new));

        /** Sync codec (clients only show the shaking). */
        public static final StreamCodec<ByteBuf, Conversion> STREAM_CODEC = StreamCodec.composite(
                ResourceKey.streamCodec(Registries.DIMENSION), Conversion::dimension,
                ByteBufCodecs.VAR_INT, Conversion::settlement,
                ByteBufCodecs.VAR_LONG, Conversion::due,
                Conversion::new);

        /**
         * @param due a new completion time
         * @return this conversion completing at {@code due}
         */
        public Conversion withDue(long due) {
            return new Conversion(dimension, settlement, due);
        }
    }

    /** Why a Receptive Villager refuses the conversion food. */
    public enum Refusal {
        /** A baby Villager. */
        BABY("civmod.conversion.too_young"),
        /** Outside every settlement border. */
        OUTSIDE("civmod.conversion.outside"),
        /** The settlement reached {@code maxSettlersPerSettlement}. */
        FULL("civmod.conversion.full"),
        /** The settlement has no free bed. */
        NO_BED("civmod.conversion.no_bed");

        private final String key;

        Refusal(String key) {
            this.key = key;
        }

        /**
         * @return the action bar message
         */
        public Component message() {
            return Component.translatable(key);
        }
    }

    /**
     * @param villager a Villager
     * @return whether it is Receptive for the conversion food
     */
    public static boolean isReceptive(Villager villager) {
        return villager.getData(ModAttachments.RECEPTIVE_UNTIL) > villager.level().getGameTime();
    }

    /**
     * @param villager a Villager
     * @return whether it is converting into a Settler
     */
    public static boolean isConverting(Villager villager) {
        return villager.hasData(ModAttachments.CONVERSION);
    }

    /**
     * Rolls the starting level of one Job of a Villager that came from a mission: the average of {@value #SKILL_DICE} dice
     * from 1 to the mission level, rounded half to even (so the result is centred on the middle).
     *
     * @param missionLevel the mission level (at least 1)
     * @param random       the random source
     * @return the level, 1 to {@code missionLevel}
     */
    public static int rollLevel(int missionLevel, RandomSource random) {
        int sum = 0;
        for (int i = 0; i < SKILL_DICE; i++) {
            sum += random.nextIntBetweenInclusive(1, Math.max(1, missionLevel));
        }
        return (int) Math.rint((double) sum / SKILL_DICE);
    }

    /**
     * The checks at feeding, in order: a baby, outside every settlement border, the settlement full, no free bed.
     *
     * @param level    the level
     * @param villager a Receptive Villager
     * @return why it refuses the food, empty if it accepts it
     */
    public static Optional<Refusal> refusal(ServerLevel level, Villager villager) {
        if (villager.isBaby()) {
            return Optional.of(Refusal.BABY);
        }
        Optional<Settlement> settlement = SettlementData.get(level).settlementContaining(villager.blockPosition());
        if (settlement.isEmpty()) {
            return Optional.of(Refusal.OUTSIDE);
        }
        if (isFull(settlement.get())) {
            return Optional.of(Refusal.FULL);
        }
        if (bedFor(level, settlement.get(), villager).isEmpty()) {
            return Optional.of(Refusal.NO_BED);
        }
        return Optional.empty();
    }

    /** Settlers and running conversions count towards {@code maxSettlersPerSettlement} (0 = unlimited). */
    private static boolean isFull(Settlement settlement) {
        int max = CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.get();
        return max > 0 && settlement.members().size() + settlement.bedReservations().size() >= max;
    }

    /** The Villager's own bed if it is free, else the free bed closest to it. */
    private static Optional<BlockPos> bedFor(ServerLevel level, Settlement settlement, Villager villager) {
        List<BlockPos> free = SettlerBeds.freeBeds(level, settlement, villager.blockPosition());
        Optional<BlockPos> own = villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(home -> home.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(free::contains);
        return own.or(() -> free.stream().findFirst());
    }

    /**
     * @param stack an item stack
     * @return whether it makes a Villager Receptive (a potion only when it is plain water)
     */
    static boolean isPrimer(ItemStack stack) {
        PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
        return stack.is(PRIMER) && (potion == null || potion.is(Potions.WATER));
    }

    @SubscribeEvent
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level) || !villager.isAlive()) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        boolean primer = isPrimer(stack);
        boolean food = stack.is(FOOD) && (isReceptive(villager) || isConverting(villager));
        if (!primer && !food) {
            return;
        }
        // Neither item ever opens trading; a converting Villager takes nothing more.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS_SERVER);
        if (isConverting(villager)) {
            return;
        }
        if (primer) {
            if (villager.isBaby()) {
                refuse(villager, player, Refusal.BABY);
                return;
            }
            makeReceptive(level, villager);
            ItemStack rest = remainder(stack);
            player.setItemInHand(event.getHand(), rest.isEmpty() ? consumed(stack, player) : ItemUtils.createFilledResult(stack, player, rest));
            return;
        }
        Optional<Refusal> refusal = refusal(level, villager);
        if (refusal.isPresent()) {
            refuse(villager, player, refusal.get());
            return;
        }
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementContaining(villager.blockPosition()).orElseThrow();
        BlockPos bed = bedFor(level, settlement, villager).orElseThrow();
        stack.consume(1, player);
        start(level, data, settlement, villager, bed);
    }

    private static ItemStack remainder(ItemStack stack) {
        UseRemainder remainder = stack.get(DataComponents.USE_REMAINDER);
        return remainder == null ? ItemStack.EMPTY : remainder.convertInto().create();
    }

    private static ItemStack consumed(ItemStack stack, Player player) {
        stack.consume(1, player);
        return stack;
    }

    /**
     * Makes a Villager Receptive (the water bottle or a splash water bottle).
     *
     * @param level    the level
     * @param villager the Villager
     */
    public static void makeReceptive(ServerLevel level, Villager villager) {
        villager.setData(ModAttachments.RECEPTIVE_UNTIL, level.getGameTime() + RECEPTIVE_TICKS);
        level.sendParticles(ParticleTypes.SPLASH, villager.getX(), villager.getY(1.0), villager.getZ(), 20, 0.3, 0.2, 0.3, 0.0);
    }

    private static void start(ServerLevel level, SettlementData data, Settlement settlement, Villager villager, BlockPos bed) {
        villager.removeData(ModAttachments.RECEPTIVE_UNTIL);
        data.reserveBed(settlement, villager.getUUID(), bed);
        villager.setData(ModAttachments.CONVERSION, new Conversion(level.dimension(), settlement.id(), level.getGameTime() + DURATION_TICKS));
        RandomSource random = villager.getRandom();
        level.playSound(null, villager.getX(), villager.getEyeY(), villager.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, villager.getSoundSource(),
                1.0F + random.nextFloat(), random.nextFloat() * 0.7F + 0.3F);
    }

    /** The Villager shakes its head (as vanilla Villagers without trades do) and the action bar names the reason. */
    private static void refuse(Villager villager, Player player, Refusal refusal) {
        refuse(villager);
        player.sendOverlayMessage(refusal.message());
    }

    private static void refuse(Villager villager) {
        villager.setUnhappyCounter(40);
        villager.makeSound(SoundEvents.VILLAGER_NO);
    }

    @SubscribeEvent
    static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownSplashPotion potion) || !(potion.level() instanceof ServerLevel level)
                || !potion.getItem().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER)) {
            return;
        }
        for (Villager villager : level.getEntitiesOfClass(Villager.class, potion.getBoundingBox().inflate(SPLASH_RADIUS, 2.0, SPLASH_RADIUS))) {
            if (villager.isAlive() && !villager.isBaby() && !isConverting(villager)
                    && potion.distanceToSqr(villager) < SPLASH_RADIUS * SPLASH_RADIUS) {
                makeReceptive(level, villager);
            }
        }
    }

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level) || !villager.isAlive()) {
            return;
        }
        Optional<Conversion> conversion = villager.getExistingData(ModAttachments.CONVERSION);
        if (conversion.isPresent()) {
            if (level.getGameTime() >= conversion.get().due()) {
                finish(level, villager, conversion.get());
            } else if ((villager.tickCount + villager.getId()) % PARTICLE_INTERVAL == 0) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY(0.7), villager.getZ(), 3, 0.3, 0.4, 0.3, 0.0);
            }
        } else if ((villager.tickCount + villager.getId()) % PARTICLE_INTERVAL == 0 && isReceptive(villager)) {
            level.sendParticles(ParticleTypes.DRIPPING_WATER, villager.getX(), villager.getY(1.0), villager.getZ(), 2, 0.25, 0.3, 0.25, 0.0);
        }
    }

    /** A converting Villager that dies (or is otherwise destroyed) frees its bed reservation. */
    @SubscribeEvent
    static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Villager villager && villager.level() instanceof ServerLevel level
                && villager.getRemovalReason() != null && villager.getRemovalReason().shouldDestroy()) {
            villager.getExistingData(ModAttachments.CONVERSION).ifPresent(conversion -> release(level, villager, conversion));
        }
    }

    /** Frees the bed reservation of a conversion (in the dimension of its settlement). */
    private static void release(ServerLevel level, Villager villager, Conversion conversion) {
        ServerLevel home = level.getServer().getLevel(conversion.dimension());
        if (home != null) {
            SettlementData data = SettlementData.get(home);
            data.settlement(conversion.settlement()).ifPresent(settlement -> data.releaseBedReservation(settlement, villager.getUUID()));
        }
    }

    /**
     * Completes a conversion: the reserved bed (else another free bed) goes to the new Settler; without a bed or settlement
     * the conversion fails and the Villager stays a Villager.
     */
    private static void finish(ServerLevel level, Villager villager, Conversion conversion) {
        villager.removeData(ModAttachments.CONVERSION);
        SettlementData data = SettlementData.get(level);
        Optional<Settlement> settlement = conversion.dimension().equals(level.dimension()) ? data.settlement(conversion.settlement()) : Optional.empty();
        if (settlement.isEmpty()) {
            // Dissolved, or the Villager changed dimension: free the reservation where it was made.
            release(level, villager, conversion);
        }
        // Freed first, so the reserved bed counts as free again if it still is one.
        Optional<BlockPos> reserved = settlement.flatMap(found -> data.releaseBedReservation(found, villager.getUUID()));
        Optional<BlockPos> bed = settlement.flatMap(found -> {
            List<BlockPos> free = SettlerBeds.freeBeds(level, found, villager.blockPosition());
            return reserved.filter(free::contains).or(() -> free.stream().findFirst());
        });
        if (settlement.isEmpty() || bed.isEmpty()) {
            refuse(villager);
            return;
        }
        becomeSettler(level, data, settlement.get(), villager, bed.get());
    }

    private static void becomeSettler(ServerLevel level, SettlementData data, Settlement settlement, Villager villager, BlockPos bed) {
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.CONVERSION);
        if (settler == null) {
            refuse(villager);
            return;
        }
        RandomSource random = level.getRandom();
        Vec3 pos = villager.position();
        settler.snapTo(pos.x, pos.y, pos.z, villager.getYRot(), villager.getXRot());
        settler.setRace(Races.HUMAN);
        settler.setSkin(random.nextInt(SettlerSkins.COUNT));
        Component name = villager.hasCustomName() ? villager.getCustomName()
                : Component.literal(RaceNames.pick(RaceNames.get(Races.HUMAN).npc(), SettlerSpawner.usedNames(level, settlement), random).orElse("Settler"));
        settler.setCustomName(name);
        rollSkills(settler.getSkills(), villager, random);
        settler.joinSettlement(data, settlement);

        villager.releasePoi(MemoryModuleType.HOME);
        villager.releasePoi(MemoryModuleType.JOB_SITE);
        villager.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
        villager.releasePoi(MemoryModuleType.MEETING_POINT);
        villager.discard();
        level.addFreshEntity(settler);
        SettlerBeds.claim(level, data, settlement, settler.getUUID(), bed);
        SettlerSpawner.puff(level, pos);
        Component message = Component.translatable("civmod.conversion.joined", settler.getName(), settlement.displayName());
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= SettlerSpawner.ANNOUNCE_DISTANCE * SettlerSpawner.ANNOUNCE_DISTANCE) {
                player.sendSystemMessage(message);
            }
        }
    }

    /**
     * Starting skills: with a mission level every registered Job is rolled on its own ({@link #rollLevel}); nitwits and
     * Villagers without one start at level 1 everywhere.
     */
    private static void rollSkills(SettlerSkills skills, Villager villager, RandomSource random) {
        boolean nitwit = villager.getVillagerData().profession().is(VillagerProfession.NITWIT);
        int missionLevel = nitwit ? 0 : MissionVillagers.missionLevel(villager).orElse(0);
        for (Job job : ModJobs.REGISTRY) {
            int level = missionLevel > 0 ? rollLevel(missionLevel, random) : 1;
            skills.set(job, new SettlerSkills.Skill(level, 0));
        }
    }
}
