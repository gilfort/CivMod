package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.StationTypes;

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
 * What the "Station Types" tab of a JobBlock shows (vault {@code Systems/JobBlock.md}, {@link StationTypes}): its 5
 * slots, how many are open, the Jobs unlocked there, the Jobs that keep a library and the "?" overview of every unlock
 * item. The server sends it while the menu is open whenever it changed ({@link Sync}); the client puts an item into a
 * slot with a menu button ({@link StationTypesTab#slotButtonId}).
 *
 * @param slots     the slots ({@value StationTypes#SLOTS} entries): the Base Job's station, then the unlock items
 *                  ({@link Items#AIR}: empty)
 * @param open      how many slots are in use at the JobBlock's level
 * @param unlocked  the Jobs of its Base Job the JobBlock offers (the Base Job first)
 * @param libraries the Jobs whose library holds a pattern (a locked one keeps its Patterns tab)
 * @param overview  every unlock item of the Base Job's Specializations with the Jobs it unlocks
 */
public record StationTypesTabData(List<Item> slots, int open, List<Job> unlocked, List<Job> libraries, List<StationTypes.Unlock> overview) {

    /** Nothing (before the first sync). */
    public static final StationTypesTabData EMPTY = new StationTypesTabData(List.of(), 1, List.of(), List.of(), List.of());

    private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM_CODEC = ByteBufCodecs.registry(Registries.ITEM);

    private static final StreamCodec<RegistryFriendlyByteBuf, StationTypes.Unlock> UNLOCK_CODEC = StreamCodec.composite(
            ITEM_CODEC, StationTypes.Unlock::item,
            Job.STREAM_CODEC.apply(ByteBufCodecs.list()), StationTypes.Unlock::jobs,
            StationTypes.Unlock::new);

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, StationTypesTabData> STREAM_CODEC = StreamCodec.composite(
            ITEM_CODEC.apply(ByteBufCodecs.list(StationTypes.SLOTS)), StationTypesTabData::slots,
            ByteBufCodecs.VAR_INT, StationTypesTabData::open,
            Job.STREAM_CODEC.apply(ByteBufCodecs.list()), StationTypesTabData::unlocked,
            Job.STREAM_CODEC.apply(ByteBufCodecs.list()), StationTypesTabData::libraries,
            UNLOCK_CODEC.apply(ByteBufCodecs.list()), StationTypesTabData::overview,
            StationTypesTabData::new);

    /**
     * @param slots     the slots
     * @param open      how many slots are in use
     * @param unlocked  the Jobs the JobBlock offers
     * @param libraries the Jobs whose library holds a pattern
     * @param overview  every unlock item with its Jobs
     */
    public StationTypesTabData {
        slots = List.copyOf(slots);
        unlocked = List.copyOf(unlocked);
        libraries = List.copyOf(libraries);
        overview = List.copyOf(overview);
    }

    /**
     * @param level the level
     * @param pos   the JobBlock position
     * @return the current tab of that JobBlock ({@link #EMPTY} if it has no Station Types)
     */
    public static StationTypesTabData of(ServerLevel level, BlockPos pos) {
        Optional<SettlementBlock> found = JobBlockSite.blockAt(level, pos).filter(block -> StationTypes.hasStationTypes(block.jobType()));
        if (found.isEmpty()) {
            return EMPTY;
        }
        SettlementBlock block = found.get();
        List<Item> slots = new ArrayList<>(block.stationTypes());
        slots.set(0, StationTypes.baseStation(block.jobType()));
        return new StationTypesTabData(slots, StationTypes.open(block.level()), StationTypes.unlocked(block), block.libraryJobs(),
                StationTypes.overview(block.jobType()));
    }

    /**
     * @param slot a slot
     * @return the item in it ({@link Items#AIR}: empty)
     */
    public Item item(int slot) {
        return slot >= 0 && slot < slots.size() ? slots.get(slot) : Items.AIR;
    }

    /**
     * @param slot a slot
     * @return whether it takes an unlock item ({@link StationTypes#isOpen(int, int)})
     */
    public boolean isOpen(int slot) {
        return StationTypes.isOpen(slot, open);
    }

    /**
     * @param item an item
     * @return the Jobs it unlocks at this JobBlock (from the overview)
     */
    public List<Job> jobsOf(Item item) {
        return overview.stream().filter(unlock -> unlock.item() == item).findFirst().map(StationTypes.Unlock::jobs).orElse(List.of());
    }

    /**
     * Server → client: the Station Types tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, StationTypesTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("station_type_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                StationTypesTabData.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }
}
