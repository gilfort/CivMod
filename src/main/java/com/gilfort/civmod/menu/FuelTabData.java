package com.gilfort.civmod.menu;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.Fuels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What the "Fuel" tab of a JobBlock whose Job burns fuel shows (vault {@code Jobs/Smelter.md}, {@link Fuels}): its fuel
 * list in priority order, at most {@value Fuels#SLOTS} items without gaps. The server sends it while the menu is open
 * whenever it changed ({@link Sync}); the client changes it with {@link Edit}.
 *
 * @param fuel the fuel items in priority order
 */
public record FuelTabData(List<Item> fuel) {

    /** Nothing (before the first sync). */
    public static final FuelTabData EMPTY = new FuelTabData(List.of());

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, FuelTabData> STREAM_CODEC = ByteBufCodecs.registry(Registries.ITEM)
            .apply(ByteBufCodecs.list(Fuels.SLOTS)).map(FuelTabData::new, FuelTabData::fuel);

    /**
     * @param fuel the fuel items in priority order
     */
    public FuelTabData {
        fuel = List.copyOf(fuel);
    }

    /**
     * @param level the level
     * @param pos   the JobBlock position
     * @return the current tab of that JobBlock (empty if its Job burns no fuel)
     */
    public static FuelTabData of(ServerLevel level, BlockPos pos) {
        return new FuelTabData(Fuels.of(level, pos));
    }

    /**
     * @param slot a slot
     * @return the item in it ({@link Items#AIR}: empty)
     */
    public Item item(int slot) {
        return slot >= 0 && slot < fuel.size() ? fuel.get(slot) : Items.AIR;
    }

    /**
     * @return the slot Shift + right-click fills: the first empty one, {@code -1} if all {@value Fuels#SLOTS} are set
     */
    public int nextEmptyGhostSlot() {
        return fuel.size() < Fuels.SLOTS ? fuel.size() : -1;
    }

    /** A change of the fuel list. */
    public enum Op {
        /** Puts an item into a slot (not consumed). */
        SET,
        /** Empties a slot; the following items move up. */
        CLEAR,
        /** Swaps the item with its left neighbour (◀, higher priority). */
        LEFT,
        /** Swaps the item with its right neighbour (▶). */
        RIGHT
    }

    /**
     * Server → client: the Fuel tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, FuelTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("fuel_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                FuelTabData.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: change the fuel list of the open JobBlock menu.
     *
     * @param containerId the menu's container id
     * @param op          the change
     * @param slot        the slot it applies to
     * @param item        the item to put ({@link Op#SET}; {@link Items#AIR} otherwise)
     */
    public record Edit(int containerId, Op op, int slot, Item item) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Edit> TYPE = new Type<>(CivMod.id("edit_fuel"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Edit::containerId,
                ByteBufCodecs.idMapper(index -> Op.values()[index], Op::ordinal), Edit::op,
                ByteBufCodecs.VAR_INT, Edit::slot,
                ByteBufCodecs.registry(Registries.ITEM), Edit::item,
                Edit::new);

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }
}
