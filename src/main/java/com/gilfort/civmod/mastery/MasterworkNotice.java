package com.gilfort.civmod.mastery;

import java.util.Collection;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * What players see of Mastery in live play: for a masterwork, particles and a short sound at the settler and an action bar
 * message for every player near it ("Jacob made a Masterwork Iron Pickaxe!"); for a saving or an extra output, particles.
 * Catch-up never calls it.
 */
public final class MasterworkNotice {

    /** How far from the settler a player gets the message, in blocks: the same range as the other settler messages. */
    private static final double MESSAGE_RANGE_BLOCKS = 24.0;
    /** Where the particles start: this share of the settler's height, about the chest. */
    private static final double CHEST_HEIGHT_SHARE = 0.75;
    private static final int PARTICLE_COUNT = 12;
    private static final double PARTICLE_SPREAD = 0.4;
    private static final int BOOST_PARTICLE_COUNT = 5;
    private static final double BOOST_PARTICLE_SPREAD = 0.3;
    private static final float SOUND_VOLUME = 0.6F;
    private static final float SOUND_PITCH = 1.1F;

    private MasterworkNotice() {
    }

    /**
     * Shows a masterwork: particles and a sound at the settler, the message to the players within
     * {@link #MESSAGE_RANGE_BLOCKS} of it.
     *
     * @param level      the level
     * @param players    the players who may get the message: the level's players in live play
     * @param settler    the settler who made it
     * @param masterwork the finished masterwork
     */
    public static void show(ServerLevel level, Collection<? extends ServerPlayer> players, LivingEntity settler, ItemStack masterwork) {
        particles(level, settler, ParticleTypes.ENCHANTED_HIT, PARTICLE_COUNT, PARTICLE_SPREAD);
        level.playSound(null, settler.getX(), settler.getY(), settler.getZ(), SoundEvents.SMITHING_TABLE_USE, SoundSource.NEUTRAL, SOUND_VOLUME, SOUND_PITCH);
        Component message = Component.translatable("civmod.masterwork.made", settler.getName(), masterwork.getStyledHoverName());
        for (ServerPlayer player : players) {
            if (player.distanceToSqr(settler) <= MESSAGE_RANGE_BLOCKS * MESSAGE_RANGE_BLOCKS) {
                player.sendOverlayMessage(message);
            }
        }
    }

    /**
     * Shows a saving or an extra output: a few particles at the settler.
     *
     * @param level   the level
     * @param settler the settler who made it
     */
    public static void showBoost(ServerLevel level, LivingEntity settler) {
        particles(level, settler, ParticleTypes.HAPPY_VILLAGER, BOOST_PARTICLE_COUNT, BOOST_PARTICLE_SPREAD);
    }

    private static void particles(ServerLevel level, LivingEntity settler, ParticleOptions particle, int count, double spread) {
        level.sendParticles(particle, settler.getX(), settler.getY() + settler.getBbHeight() * CHEST_HEIGHT_SHARE, settler.getZ(),
                count, spread, spread, spread, 0.0);
    }
}
