package com.gilfort.civmod.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Players for GameTests that need one in the level, for example to stand in the radius of a Service act.
 */
final class TestPlayers {

    private TestPlayers() {
    }

    /**
     * Adds a player to the level with a connection that goes nowhere. It is not put into the player list: joining it would
     * send every mod payload to a connection that has none registered.
     *
     * @param helper the test
     * @param at     where the player stands
     * @return the player; remove it with {@link #remove}
     */
    static ServerPlayer add(GameTestHelper helper, Vec3 at) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test-listener"), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, cookie);
        player.setPos(at.x, at.y, at.z);
        level.addNewPlayer(player);
        helper.assertTrue(channel.isOpen(), "The player's connection is open");
        return player;
    }

    /**
     * @param player a player made by {@link #add}
     */
    static void remove(ServerPlayer player) {
        ((ServerLevel) player.level()).removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
    }
}
