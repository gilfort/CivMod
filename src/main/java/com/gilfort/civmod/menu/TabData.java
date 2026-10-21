package com.gilfort.civmod.menu;

import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Data shown by one or more tabs of the JobBlock window that the server sends while the window is open: read once a
 * second ({@link JobBlockMenu#broadcastChanges}), sent only when it changed. The client keeps the last value it received.
 *
 * @param <T> the data type (value equality)
 */
public final class TabData<T> {

    /**
     * Reads the current data on the server.
     *
     * @param <T> the data type
     */
    @FunctionalInterface
    interface Reader<T> {

        /**
         * @param menu  the open window
         * @param level the level
         * @param pos   the JobBlock position
         * @return the current data
         */
        T read(JobBlockMenu menu, ServerLevel level, BlockPos pos);
    }

    private final Reader<T> reader;
    private final BiFunction<Integer, T, CustomPacketPayload> payload;
    private T value;

    /**
     * @param empty   the value before the first sync
     * @param reader  reads the current value on the server
     * @param payload the payload that sends a value to the window with a container id
     */
    TabData(T empty, Reader<T> reader, BiFunction<Integer, T, CustomPacketPayload> payload) {
        this.value = empty;
        this.reader = reader;
        this.payload = payload;
    }

    /**
     * @return the value as last read (server) or received (client)
     */
    public T get() {
        return value;
    }

    /**
     * Client: the server sent a new value.
     *
     * @param received the value
     */
    public void set(T received) {
        value = received;
    }

    /** Server: reads the current value and sends it if it changed. */
    void sync(JobBlockMenu menu, ServerPlayer player, ServerLevel level, BlockPos pos) {
        T now = reader.read(menu, level, pos);
        if (!now.equals(value)) {
            value = now;
            PacketDistributor.sendToPlayer(player, payload.apply(menu.containerId, now));
        }
    }
}
