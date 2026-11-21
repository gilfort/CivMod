package com.gilfort.civmod.mastery;

import java.util.Collection;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * What players see of a masterwork in live play: particles and a short sound at the settler, and an action bar message for
 * every player near it ("Jacob made a Masterwork Iron Pickaxe!"). Catch-up never calls it.
 */
public final class MasterworkNotice {

    /** How far from the settler a player gets the message, in blocks: the same range as the other settler messages. */
    private static final double MESSAGE_RANGE_BLOCKS = 24.0;
    private static final int PARTICLE_COUNT = 12;
    private static final double PARTICLE_SPREAD = 0.4;
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
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, settler.getX(), settler.getY() + settler.getBbHeight() * 0.75, settler.getZ(),
                PARTICLE_COUNT, PARTICLE_SPREAD, PARTICLE_SPREAD, PARTICLE_SPREAD, 0.0);
        level.playSound(null, settler.getX(), settler.getY(), settler.getZ(), SoundEvents.SMITHING_TABLE_USE, SoundSource.NEUTRAL, SOUND_VOLUME, SOUND_PITCH);
        Component message = Component.translatable("civmod.masterwork.made", settler.getName(), masterwork.getStyledHoverName());
        for (ServerPlayer player : players) {
            if (player.distanceToSqr(settler) <= MESSAGE_RANGE_BLOCKS * MESSAGE_RANGE_BLOCKS) {
                player.sendOverlayMessage(message);
            }
        }
    }
}
